# Structured cancellation for the assistant transports (F4a)

Status: Approved by the operator on 2026-11-12 (PR 4a of phase F4, decision R2,
of `docs/reviews/assistant-architecture-review.md`; decisions D1-D6 below, with
D2 and D3 explicitly approved by the operator as weakened wording). Prepared
against `209b84b`; implemented on `main` at `5ef5d82` (0.3.60; the assistant tree is identical to `083af43`). PR 4b
(domain API, device directory, profile information, SSH) is a separate contract.

The plan was adjusted where the code showed it to be wrong or underspecified
(see "Plan corrections" in the preparation notes, summarized under the
interfaces below).

## Goal

Replace the hand-built "enqueue + callback + CallbackExitExecutor" machinery of
the two assistant HTTP transports (provider chat/image/model discovery, and the
public web transport) with one structured-concurrency primitive, without changing
anything a user, the model, the provider or a remote server can observe:

- Stop, assistant disable, provider revision change, pop/close and timeouts still
  reach the in-flight exchange immediately (`call.cancel()`).
- The one app-wide chat slot is still freed only after the blocking exchange,
  including body read and close, has actually returned.
- No new network side effect starts after a committed disable/Stop is visible to
  the start check.
- Ordinary (non-assistant) calls behave as before.

Removed by this PR: two of the three `CallbackExitExecutor` copies (provider and
public web), every `dispatch` parameter of the provider/inspector and public-fetch
path, the `is OpenAiAgentProvider` branch in `AssistantConversation`, and the three
adapter test hooks. Added: `agent-core/.../Cancellation.kt`.

## Scenarios

Normal flow
1. A Send calls the model: the chat checks admission, then the OpenAI adapter
   builds the `Call` and runs `execute()` on an IO thread. The reply, the byte
   limits, the 503 handling, error mapping and timeouts are as before.
2. `read_page`/`web_search`: per hop, the transport checks admission for the DNS
   lookup, resolves on IO, validates addresses, checks admission for the HTTP
   start, charges the budget, runs the exchange on IO and reads the capped body.
3. `inspect_image`: the tool checks admission (`image-dispatch`), the adapter runs
   the image request exactly like a chat request.
4. Model discovery (`listModels`) and any call outside a Send run through the same
   adapter path with no ambient admission and behave as before.

Cancellation and races (each must have a named test, see Acceptance criteria)
5. Stop/disable/revision/pop while the request is waiting on the server: the
   `Call` is cancelled promptly from an IO thread (never from the Main thread that
   called `stop()`), the coroutine ends only after `execute()` returned, the slot is
   freed then, and the outcome is a cancellation, never a provider/tool failure.
6. Stop while the body is being read (server stalls mid-body): same as 5.
7. Stop while the thread is still inside the transport after the response was
   produced (the old "callback exit" hold): the slot stays occupied until the
   thread leaves; a replacement Send is refused, not queued.
8. Stop while DNS runs: DNS cannot be cancelled; the slot is held until the
   lookup returns; no HTTP request starts afterwards.
9. A disable/Stop/revision commit lands before the admission check of a start:
   the check fails, nothing starts, the search/request budgets are not charged.
10. A commit lands after the check but before the exchange begins (the
    check-then-start window). The request may start; its `Call` is cancelled
    by the cancel that follows the commit. A call cancelled before OkHttp's
    `RetryAndFollowUpInterceptor` loop sends nothing; one cancelled later is
    cancelled in flight. This is the same class as today's "admitted before the
    commit" request (see Guarantees, G3).
11. A source is published only while the Send still owns the run; a revoked
    owner cannot publish, and the dedup list is not updated for it.
12. A timeout (`withTimeout` of model/tool/Send) cancels like Stop and the existing
    TIMEOUT/`transportTimeout` mapping is unchanged.
13. The exchange throws after the coroutine was cancelled: the caller sees
    `CancellationException`, not `OpenAiProviderException`/`WebException`.
14. A neutral provider that does not call any admission API is still checked:
    the chat admits `dispatch` before every provider call (Decision D1).

## Non-goals

- Any change of timeouts (connect/read/call), byte limits, the 12-request/3-redirect
  budget, search quotas and cooldowns, address and redirect policy, headers, user
  agent, texts, error kinds, UI strings, saved files, prompts or tool schemas.
- `runInterruptible` or any thread interrupt. Cancellation of the exchange is
  only `call.cancel()`.
- Everything listed under "Left for PR 4b".
- TV, icon, clipboard code; releases, tags, version bumps.

## Left for PR 4b (must keep compiling and keep every guarantee meanwhile)

- `FrpJumpApi`: `ApiCallbackExitExecutor`, `ApiRequestDispatch`, `ownedCall`,
  `Call.await()`, `beforeOwned*` hooks, the 4 `dispatch` parameters
  (`version`, `devices`, `call`, plus callers).
- `DeviceDirectory.pull/fetch/checkFeatures` and `ProfileInformation.refreshAll`
  `dispatch` parameters.
- `AssistantProfileInformationTool` constructor `dispatch` and the categories
  `profile-<op>-dispatch`.
- `AssistantSshCommands.execute(..., dispatch)`, `SshCommandOperation.run(dispatch, ...)`,
  its independent worker scope, `closeRequested`, category `ssh-dispatch`.
- The typealias `WebDispatch` and the guarded lambda that `AssistantConversation`
  still builds for the two tools above.
- Tests: `OwnedApiRequestTest`, `AssistantProfileRefreshOwnershipTest`,
  `ProfileInformationTest`, `ProfileInformationFixture`, the SSH worker-admission
  tests in `AssistantSshCommandTest`, and the surface-contract sentences about
  "actual nonblocking enqueue" (profile) and "actual nonblocking worker admission"
  (SSH) in `docs/agent-contract.md` and `docs/contract.md`.

## Affected surface contracts

Read as source of truth for this feature:

- [`docs/agent-contract.md`](../../agent-contract.md): "Separation", "Minimal
  execution", Chat Send/Stop paragraph, shared-guard paragraph, "Automatic working
  summaries", "Optional public Web tools" and the Web ownership paragraph,
  "Search and static documents", transport paragraph, adapter limits paragraph,
  the test inventory paragraphs. Edited at completion (see `ledger.md`).
- [`docs/contract.md`](../../contract.md): "Optional LLM assistant" (slot and
  actual-exit sentences). Expected: no wording change in 4a; the owned-API-request
  sentence stays true until 4b.
- Read-only context: [`docs/contracts/meta-contract.md`](../meta-contract.md),
  `docs/files-contract.md`, `docs/ssh-persist-contract.md`, `docs/mqtt-contract.md`,
  `docs/terminal-*-contract.md` (no assistant transport content; no edit).

If implementation shows another surface or guarantee is affected, stop and
obtain approval of a revised contract.

## Interfaces and constraints

### `agent-core/src/main/kotlin/ru/adsrv/frpjump/agent/Cancellation.kt` (new)

```kotlin
/** Runs blocking [block] on Dispatchers.IO. If the caller is cancelled while [block] runs, calls
 *  [onCancel] promptly in an IO context (never on a non-IO caller thread such as Main). Returns or
 *  throws only after [block] has actually returned. [onCancel] runs at most once and its failures are
 *  swallowed; the decision to call it is closed once [block] returns, but a call already decided may
 *  still be running and the caller waits for it. If the caller was cancelled, the result of [block] is
 *  discarded and CancellationException is thrown; an exception from [block] is dropped (best effort
 *  suppressed). */
suspend fun <T> blockingCancellable(onCancel: () -> Unit, block: () -> T): T = coroutineScope {
    currentCoroutineContext().ensureActive()
    val finished = AtomicBoolean(false)
    val signal = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally {
            if (!finished.get()) withContext(NonCancellable + Dispatchers.IO) { yield(); runCatching { onCancel() } } // yield: real hop off the caller thread
        }
    }
    try { withContext(Dispatchers.IO) { block() } } finally { finished.set(true); signal.cancel() }
}

/** Ambient start check for owned work; absent for ordinary UI work. [check] runs under the owner's short guard,
 *  on the calling thread, and must neither block nor suspend. */
class SideEffectAdmission(val check: (category: String) -> Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SideEffectAdmission>
}

/** Throws CancellationException when the caller is cancelled or the ambient admission refuses [category];
 *  does nothing outside an owned run. Call immediately before starting a network side effect. */
suspend fun admitSideEffect(category: String) {
    currentCoroutineContext().ensureActive()
    val admission = currentCoroutineContext()[SideEffectAdmission] ?: return
    if (!admission.check(category)) throw CancellationException("Not admitted: $category")
}
```

Differences from the plan's draft (all required, see `risks.md`): the leading
`ensureActive()`; the `onCancel` hop to IO inside `finally` (the plan's
UNDISPATCHED launch would run `onCancel` synchronously on the caller's thread
when the scope is already cancelled); `runCatching` around `onCancel`; the
exact discard/suppress semantics.

`ImageInspector.inspectImage(mime, bytes, question)` in `ProviderContracts.kt`
loses its `dispatch` parameter.

### `AssistantConversation`

- `withContext(io)` that wraps `module.run(...)` becomes
  `withContext(io + SideEffectAdmission { category -> guarded(owner, category) { true } ?: false })`.
  `guarded` is unchanged, so the `beforeAction` (before the guard) and
  `checkedAction` (inside the guard) probes of `AssistantChatRuntime` keep firing
  for every category.
- `complete(owner, active, request)` becomes
  `complete(active, request) { admitSideEffect("dispatch"); return active.complete(request) }`
  (Decision D1). No `is OpenAiAgentProvider`, no `async(UNDISPATCHED)`: a neutral
  provider's first segment no longer runs under the chat gate.
- The `AssistantWebTools` `publish` lambda becomes the guarded source mutation:
  `{ source -> guarded(owner, "source") { /* the existing document/state update */ } ?: throw CancellationException() }`.
- Still built here until 4b, unchanged: the `(category, action) -> guarded(...)`
  lambda passed to `AssistantProfileInformationTool` and `AssistantSshCommandTool`.
- Category strings are exactly: `admission`, `dispatch`, `tool-start`,
  `dns-dispatch`, `public-dispatch`, `image-dispatch`, `source`, `progress`,
  `history`, `window`, `summary`, `presentation`, `answer`, `finish-error`,
  `setup-error`, and (4b) `profile-<op>-dispatch`, `ssh-dispatch`.

### `OpenAiAgentProvider`

- `complete(request)` and `inspectImage(mime, bytes, question)` take no `dispatch`;
  the second `complete(request, dispatch)` overload is deleted.
- `execute(request)`:

  ```kotlin
  val call = template.newCall(request)
  return blockingCancellable(call::cancel) {
      try { call.execute().use { readBounded(it) } }
      catch (e: OpenAiProviderException) { throw e }
      catch (e: IOException) { throw ioFailure(e) }
      catch (e: Exception) { throw ioFailure(IOException(e)) } // AsyncCall.run converted these before
  }
  ```

  `readBounded(response)` is the existing body of `onResponse` (status check,
  `contentLength` cap, 8 KiB chunk loop with the `maxResponseBytes` cap, strict
  UTF-8 decode) moved verbatim; the `isActive` shortcut disappears because
  `call.cancel()` makes the read fail fast.
- Deleted: `beforeCallbackExit`, `beforeResponseRead`, `beforeAwaitCallbackExit`,
  `CallbackExitExecutor`, `Dispatcher(callbackExit)`, `CompletableDeferred`,
  `suspendCancellableCoroutine`, the `NonCancellable` wait.
- Kept bit-for-bit: `retryOnConnectionFailure(false)`, `followRedirects(false)`,
  `followSslRedirects(false)`, the 503 `Retry-After` network interceptor, timeouts
  10/30/60 s, no logging/cookies/cache/authenticator, `ioFailure` mapping
  (`InterruptedIOException` -> TIMEOUT, other IO -> NETWORK).
- Test seam (Decision D5): an `internal` constructor parameter
  `template: OkHttpClient = client`, mirroring `PublicWebTransport(dns, template)`.
  Production code constructs it with the default.
- `OpenAiAgentProvider` does not call `admitSideEffect` (Decision D1: the caller
  that knows the category does).

### `PublicWebTransport` and `PublicFetcher`

- `PublicFetcher.fetch(url, budget, attempt: (category: String) -> Unit)`;
  `WebDispatch` is no longer part of this interface. `attempt` is invoked on the
  caller's coroutine, once per admitted DNS lookup (`"dns-dispatch"`) and once
  per admitted HTTP start (`"public-dispatch"`), right after the admission check
  and before the blocking work; it may throw `WebException` (quota) and must not
  block (Decision D3).
- DNS hop: `admitSideEffect("dns-dispatch"); attempt("dns-dispatch");
  blockingCancellable({}) { PublicWebPolicy.resolve(host, dns) }`. DNS is not
  cancellable; the slot is held until it returns (as today).
- HTTP hop: `admitSideEffect("public-dispatch"); attempt("public-dispatch");`
  `if (budget.requests >= 12) webFail(LIMIT); budget.requests++;`
  `val call = client.newCall(request);`
  `blockingCancellable(call::cancel) { call.execute().use { read capped body -> WebResponse } }`.
  Non-IO and IO failures of the exchange map to `WebException(NETWORK)` exactly as
  the old `onFailure`; the body/charset/redirect/Retry-After handling is moved
  verbatim; `WebException` raised while reading passes through.
- Kept: fresh `ConnectionPool(0, 1s)` per hop, per-hop `Dns` returning only the
  validated set, `Proxy.NO_PROXY`, no retries, no redirects, headers, caps
  (`MAX_BYTES`), timeouts 10/20/30 s, `PublicWebPolicy` untouched.
- Deleted: `ExitExecutor`, both `Dispatcher(executor)` uses, `execute(call,
  dispatch, charge)`, `WebDispatch` use, the typealias from this file.

### `AssistantWebTools` and `AppTool`

- `AssistantWebTools(enabled, fetcher, searchState, inspector, publish)`: the
  `dispatch` parameter is removed; `publish: (ChatSource) -> Unit` is invoked at
  most once per new source and throws `CancellationException` when the Send no
  longer owns the run. `source()` = `if (source in sources) return; publish(source); sources.add(source)`.
- `search()` passes an `attempt` that does the existing
  `if (!charged) searchState.charge(budget)` and, for `"public-dispatch"`,
  `searchState.httpDispatch()` once. `read()`/`image()` pass `{}`.
- `image()` calls `admitSideEffect("image-dispatch")` immediately before
  `operation.inspectImage(...)`.
- `AppTool(id, description, schema)`: no `dispatch`; `execute()` starts with
  `admitSideEffect("tool-start")` (still before parsing and before any effect).
- `AssistantProfileInformationTool` and `AssistantSshCommandTool` keep their own
  `dispatch` parameter for 4b; they pass nothing to `AppTool`. The typealias
  `WebDispatch` moves to a small file with a "removed in 4b" comment.

### Guarantees (these replace the mechanism wording in the surface contracts)

- **G1.** Stop, disable (including off/on), provider revision/read failure, pop
  and timeouts cancel the exact in-flight exchange (`Call.cancel()`), from an IO
  thread; they cancel nothing unrelated.
- **G2.** The one app-wide slot is released only after the blocking exchange
  (including response-body read and close, and an uninterruptible DNS lookup) has
  actually returned and the run coroutine has finished. No replacement Send is
  queued.
- **G3.** Before each network side effect (model request, DNS lookup, public HTTP
  request, image request) the owner's authority is checked under the chat guard;
  after a committed disable/Stop/revision/pop the check fails and nothing starts.
  A start whose check passed before the commit may still begin; it is cancelled
  by G1, and OkHttp starts no exchange for a call cancelled before the exchange.
  Source/progress/answer/error publications stay atomic with their check.
- **G4.** Calls made outside a Send behave as before. The only difference:
  their coroutine also waits for the actual exit of the blocking call on
  cancellation.

## Acceptance criteria

Greps (run from the repository root; each must print nothing):

1. `grep -rnE "CallbackExitExecutor|ExitExecutor|beforeCallbackExit|beforeResponseRead|beforeAwaitCallbackExit|Dispatcher\(" agent-openai/src app/src/main/java/ru/adsrv/frpjump/assistant`
2. `grep -rnE "is ru\.adsrv\.frpjump\.agent\.openai|as\? ru\.adsrv\.frpjump\.agent\.openai|OpenAiAgentProvider" app/src/main/java/ru/adsrv/frpjump/assistant/AssistantConversation.kt`
3. `grep -rnwE "dispatch|WebDispatch" agent-core/src agent-openai/src app/src/main/java/ru/adsrv/frpjump/assistant/PublicWebTransport.kt app/src/main/java/ru/adsrv/frpjump/assistant/AssistantWebTools.kt app/src/main/java/ru/adsrv/frpjump/assistant/tools/AppTool.kt`
4. `grep -rn "inspectImage(.*dispatch" app/src agent-openai/src agent-core/src`
5. `grep -rnE "runInterruptible|\.interrupt\(\)" app/src/main agent-core/src agent-openai/src`
6. `grep -rn "getDeclaredField" app/src/test/java/ru/adsrv/frpjump/assistant`
7. `WebDispatch` appears only in the typealias file, `AssistantProfileInformationTool.kt`,
   `AssistantSshCommand.kt` (4b leftovers).

Accepted non-empty output (the greps are broader than the invariant they protect):
- Grep 3 matches only the contract's own category strings (`"dispatch"`, `"dns-dispatch"`, `"public-dispatch"`,
  `"image-dispatch"`, `"ssh-dispatch"`, `"profile-x-dispatch"`), their doc comments, and two pre-existing words
  in test names (`model dispatch` in `AgentModuleTest`, `network dispatch` in `OpenAiAgentProviderTest`);
  no `dispatch` parameter remains. Matching files and lines at implementation time (file:text):
```
agent-core/src/test/kotlin/ru/adsrv/frpjump/agent/SideEffectAdmissionTest.kt:        admitSideEffect("dispatch")
agent-core/src/test/kotlin/ru/adsrv/frpjump/agent/SideEffectAdmissionTest.kt:        try { withContext(SideEffectAdmission { false }) { admitSideEffec
agent-core/src/test/kotlin/ru/adsrv/frpjump/agent/SideEffectAdmissionTest.kt:        catch (refused: CancellationException) { assertTrue(refused.messa
agent-core/src/test/kotlin/ru/adsrv/frpjump/agent/SideEffectAdmissionTest.kt:                admitSideEffect("dispatch")
agent-core/src/test/kotlin/ru/adsrv/frpjump/agent/SideEffectAdmissionTest.kt:        val categories = listOf("admission", "dispatch", "tool-start", "d
agent-core/src/test/kotlin/ru/adsrv/frpjump/agent/AgentModuleTest.kt:    @Test fun `reply size and schema overhead are bounded before tools or model d
agent-openai/src/test/kotlin/ru/adsrv/frpjump/agent/openai/OpenAiAgentProviderTest.kt:    @Test fun `request cap fails before network dispatch`() {
app/src/main/java/ru/adsrv/frpjump/assistant/PublicWebTransport.kt:/** [attempt] runs on the caller's coroutine once per admitted DNS lookup ("dns-dis
app/src/main/java/ru/adsrv/frpjump/assistant/PublicWebTransport.kt:            admitSideEffect("dns-dispatch")
app/src/main/java/ru/adsrv/frpjump/assistant/PublicWebTransport.kt:            attempt("dns-dispatch")
app/src/main/java/ru/adsrv/frpjump/assistant/PublicWebTransport.kt:            admitSideEffect("public-dispatch")
app/src/main/java/ru/adsrv/frpjump/assistant/PublicWebTransport.kt:            attempt("public-dispatch")
app/src/main/java/ru/adsrv/frpjump/assistant/AssistantWebTools.kt:            if (category == "public-dispatch" && !httpStarted) { searchState.httpDis
app/src/main/java/ru/adsrv/frpjump/assistant/AssistantWebTools.kt:            admitSideEffect("image-dispatch")
```
- Grep 6 matches only pre-existing reflection into `ProfileRuntime.tunnels` (unrelated to the adapter), counted per file:
```
1 app/src/test/java/ru/adsrv/frpjump/assistant/AssistantModelSurfaceTest.kt
      1 app/src/test/java/ru/adsrv/frpjump/assistant/AssistantSshCommandTest.kt
```

Unchanged-policy check (review the diff, no hunk may touch): `PublicWebPolicy`,
`MAX_BYTES`, `USER_AGENT`, the 10/20/30 s and 10/30/60 s timeouts, `retryOnConnectionFailure`,
`followRedirects`, `followSslRedirects`, the 503 interceptors, `OpenAiCodec`, `WebDocuments`,
`WebSearchState`, any resource string, `AgentModule`.

Named tests (new or rewritten; every old scenario is mapped in `test-map.md`; the test count must not
decrease except by explicit rows there):

- `agent-core`: `BlockingCancellableTest` (cancel -> prompt `onCancel` off caller thread, returns only
  after block; normal completion no `onCancel`; block exception propagates; already-cancelled does not
  start block and never runs `onCancel` on the caller thread; exception after cancel surfaces as
  cancellation; `onCancel` failure swallowed; `onCancel` at most once; cancel-vs-completion stress;
  uncooperative block holds the caller) and `SideEffectAdmissionTest`.
- `agent-openai`: `OpenAiAgentProviderTest` additions for G1 (stalled response and stalled body),
  G2 (application-interceptor latch holds the coroutine after cancel), already-cancelled sends no
  request, non-IO runtime failure maps to NETWORK, `listModels` without ambient admission.
- `app`: `PublicWebTransportTest` (DNS latch, body hold, refused admissions, attempt order),
  `AssistantChatOwnershipTest` (rewritten transport slot test, G3 second-request test),
  `AssistantWebOwnershipTest`, `AssistantToolPipelineTest`, `AssistantSideEffectAdmissionTest` (new).
- `AssistantChatScreenTest` (instrumented): compiles with the new signatures and passes on the emulator.

No test may use reflection into the adapter, sleeps as synchronization, or probe hooks in
production transports. Latches are real: OkHttp application `Interceptor` with `CountDownLatch`,
fake `Dns` with a latch, loopback server, a fake provider that uses `blockingCancellable`.

## Validation

- Focused first:
  `./gradlew --no-daemon :agent-core:test :agent-openai:test` then
  `:app:testDebugUnitTest --tests '*AssistantChatOwnershipTest' --tests '*AssistantWebOwnershipTest' --tests '*PublicWebTransportTest' --tests '*AssistantToolPipelineTest' --tests '*AssistantSshCommandTest' --tests '*AssistantProfileRefreshOwnershipTest'`.
- Full gates:

  ```sh
  ./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest \
    :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
  git diff --check
  ```

  Inspect real test counts in the result XML (before/after table in `test-map.md`).
- `repo-docs` skill checker on the edited `docs/agent-contract.md` and on this contract.
- Emulator (required): `AssistantChatScreenTest` on `emulator-5580` (API 35), because chat
  Stop/cancel paths and fake fetcher/inspector signatures change; check `sendFollowupStopErrorAndRecovery`,
  `popDuringPreparationAndReopenKeepsGlobalSlotWithoutOldText`, `toolsFlowShowsFetchedSourcesAndFailureWithoutMakingMarkdownActive`,
  `bothToolsBindingsKeepPendingAcrossBackRecreationAndCancelPublicationOnActualPop`. Count executed
  tests from the instrumentation output. No layout change is expected, so the portrait/landscape/200% font
  matrix is not required unless a UI file is touched; if one is, run it and restore font scale 1.0.
  Emulator results are scoped evidence, not physical-device, vendor or live-provider validation.
- Synthetic credentials and loopback servers only; no production profile, key, provider, SSH,
  tunnel or broker.
- Scan new commits with Gitleaks before pushing. This feature contract is temporary: after verified
  completion, update the surface contracts per `ledger.md`, mark it complete, then delete it.

## Decisions (approved 2026-11-12)

- **D1. Who admits `dispatch`/`image-dispatch`.** Plan: the OpenAI adapter calls
  `admitSideEffect("dispatch")`. Recommended here: the chat calls
  `admitSideEffect("dispatch")` before every provider call and the image tool calls
  `admitSideEffect("image-dispatch")`; the adapter is a pure transport. Reasons: the
  category vocabulary is app-level; `ImageInspector` cannot pass a category;
  `AssistantChatOwnershipTest` pins `dispatch` probes with a neutral fake provider;
  a neutral provider keeps today's guarantee; no double admission. Cost: the check
  precedes request encoding (CPU only) instead of the `newCall`.
- **D2. Weakened atomicity of "start".** Old text: provider call start is enqueued
  under the chat guard. New: the check is under the guard, the start follows.
  The plan argues equivalence (a request admitted just before a commit can also reach
  the network today and is stopped by `call.cancel()`). Approved by the operator as
  an explicit weakening of the promise wording (AGENTS.md: implementation behaviour
  alone cannot authorize it); the new wording is ledger row 8 and G3.
- **D3. `attempt` callback for search charging.** The plan is silent on how
  `searchState.charge/httpDispatch` (now under the guard together with the start) is
  kept. Recommended: the callback above. Consequence: a quota charge and the start
  check are no longer atomic; one attempt may be charged when a commit lands between
  check and charge (conservative direction; the contract already says charged
  attempts are never rolled back). Approved by the operator.
- **D4. Source publication stays a guarded mutation** (not an admission check), since it
  changes `document`/`mutable` atomically with the ownership check.
- **D5. Test seam** `internal template: OkHttpClient` in `OpenAiAgentProvider`.
- **D6. `onCancel` dispatcher.** Plan: an IO thread. Kept; known residual risk: IO pool
  saturation (64 threads). A dedicated cached daemon pool for `onCancel` would remove
  it at the cost of a second pool; not recommended now (see R7).
