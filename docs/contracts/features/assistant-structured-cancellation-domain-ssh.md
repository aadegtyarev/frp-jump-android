# Structured cancellation for the domain API, profile information and SSH command (F4b)

Status: Approved by the operator on 2026-11-12 (PR 4b of phase F4, decision R2; the
F4 start-check wording decision "variant 1" applies, and the SSH start window is
not wider than today, so no further SSH decision is needed). Decisions: D1, D2 and
D4 accepted as recommended; D3 (extra check right before `session.exec`) declined
for 4b and kept for the confirmation phase. Implement on `main` at `bf71eb5`
(0.3.61).

Companion notes (not part of the contract, same directory): `ledger.md` (promise ledger),
`test-map.md` (old to new tests), `risks.md` (risks, plan corrections, SSH comparison).
Line references in these notes are to the exported tree at `bf71eb5`.

## Goal

Replace the remaining hand-built "enqueue + callback + executor-exit" and "independent worker scope
+ NonCancellable join" machinery with the structured primitives already merged in 4a
(`blockingCancellable`, `SideEffectAdmission`, `admitSideEffect`), without changing anything a user,
the model, the control server or a remote SSH host can observe, except the explicitly approved
weakening of "start is atomic with the check" (operator "variant 1", 2026-11-12) and the G4
delta below.

- Stop, disable, provider revision, pop/close and timeouts still reach the in-flight HTTP call
  (`Call.cancel()`) and the in-flight SSH client (`SSHClient.disconnect()`) promptly, from an IO
  thread, never from Main or from a source publisher.
- The one app-wide chat slot is still freed only after the HTTP exchange, or the SSH worker, both
  stream readers and the final disconnect, have actually returned.
- No new network or remote side effect starts after a committed disable/Stop is visible to the
  start check. A start whose check passed just before the commit may begin and is then cancelled
  (variant 1); the SSH window is not wider than today (see "SSH start window").
- Ordinary (UI/background) `FrpJumpApi` calls behave as before.

Removed by this PR: `ApiCallbackExitExecutor` (third and last `CallbackExitExecutor` copy),
`ApiRequestDispatch`, `FrpJumpApi.ownedCall`, `Call.await()`, the three `beforeOwned*` hooks, the
`dispatch` parameters of `FrpJumpApi.version/devices/call`, `DeviceDirectory.pull/fetch/checkFeatures`,
`ProfileInformation.refreshAll`, `AssistantProfileInformationTool`, `AssistantSshCommandTool`,
`AssistantSshCommands.execute`, `SshCommandOperation.run`; the `WebDispatch` typealias and its file;
the guarded `(category, action)` lambdas in `AssistantConversation`; in `SshCommandOperation` the
independent `CoroutineScope(Dispatchers.IO + SupervisorJob())`, the `NonCancellable` join, the
`closeRequested` deferred and the `cancelled` flag.

## Scenarios

Normal flow
1. Profile information: the model calls `profile_information`; `AppTool` admits `tool-start`; the tool
   runs one ordinary all-profile pull (`ProfileInformation.refreshAll`). For each runtime `pull` runs
   `checkFeatures` (`GET /version`) and `fetch` (`GET /devices`) as structured children. Each
   `FrpJumpApi.call` asks the ambient admission (category `version` or `devices`, mapped by the tool to
   `profile-version-dispatch` / `profile-devices-dispatch`, see Interfaces), then runs
   `Call.execute()` on IO inside `blockingCancellable(call::cancel)`. Texts, stale/error
   behaviour, cache writes and `ProfileInformationState` are unchanged.
2. Ordinary UI refresh (poller, pull to refresh, Connections refresh), enroll, forget-profile
   `deleteDevice`, tunnel heartbeat/desired-state/connect/disconnect, device display save: the same
   `call()` with no ambient admission (the admission step only checks that the caller is active).
   Responses, `ApiException`, `NetworkException` (reasons and texts), redirect refusal and
   `ProtocolMismatchException` are unchanged.
3. SSH command: `AppTool` admits `tool-start`; `AssistantSshCommands.execute` runs its unchanged
   target checks, starts the unchanged watchers as children of its `coroutineScope`, then
   `SshCommandOperation.run` admits `ssh-dispatch` and runs the worker (client creation, connect,
   authenticate, session, exec, two stream readers, join, cleanup) as a child of the same scope.
   Result texts and exit-fact rules are byte-identical.

Cancellation and races (each has a named test, see Acceptance criteria)
4. Stop/disable/revision/pop/timeout while an HTTP call waits on the server or reads the body: the exact
   `Call` is cancelled from an IO thread; the coroutine ends only after `execute()` returned and the
   body was closed; the slot is freed then; the outcome is a cancellation, never `NetworkException`.
5. Same while a thread is inside an application interceptor or an uninterruptible DNS lookup: the slot
   stays occupied until it returns; a replacement Send is refused, not queued.
6. A commit lands before the admission check of a request: the check fails, nothing is sent (asserted on
   the server side, a loopback listener sees no connection), for every endpoint and profile.
7. A commit lands after the check, before the exchange: the request may begin and is cancelled by the
   cancel that follows the commit (variant 1). A call cancelled before OkHttp's retry loop sends
   nothing; its application interceptors still run. The old "enqueue under the guard" assertion is
   removed with the promise it pinned (ledger row P2).
8. Stop/disable/revision/pop/timeout during SSH connect, auth, session open, exec start, stream
   reads, join or cleanup: `ssh.disconnect()` is called once, promptly, from an IO thread (not Main,
   not the caller, not a source publisher); the coroutine ends only after the worker, both readers
   and every disconnect have returned. Cancellation wins over a local target failure
   ("Stop/timeout/owner revocation wins even when it races a local target failure").
9. Target loss (profiles, hidden/Undo, connections, tunnels, session, tunnel plan) while the SSH worker
   runs: the worker child is cancelled and the client is disconnected the same way; the caller is not
   cancelled; after actual exit the tool returns the existing `targetFailure()` text (no command / exit
   facts with "output withheld" / unknown outcome), unchanged.
10. A commit lands after the `ssh-dispatch` check but before the worker body, between phases, or after
    the last phase check: see "SSH start window"; never wider than today.
11. A stream reader fails while its sibling blocks: the sibling is released by `disconnect()` (existing
    catch kept) or by the closer; the result text is the existing "SSH command failed ..." text.
12. Admission refusal (`CancellationException("Not admitted: ...")`): a `devices` refusal propagates through `fetch`, `pull` and
    `refreshAll` (cancelling the sibling `checkFeatures` and the other profiles' pulls); a `version` refusal ends inside the
    `launch` child of `pull` and, as on `main`, does not cancel the sibling fetch (which asks its own check). After a real
    commit every check refuses, so the whole pull is cancelled either way.

## Non-goals

- Any change of timeouts (OkHttp 10/30 s for the control API, SSH connect timeout, 30 s tool timeout,
  120 s Send deadline), result/error texts, `userMessage()` mapping, cache/stale logic, SSH login, host-key
  and refusal policy, `captureSshStream` limits, tool schemas, prompts, UI strings, saved files.
- `runInterruptible` or any thread interrupt. Cancellation is `Call.cancel()` and `SSHClient.disconnect()` only.
- New admission categories. Categories stay exactly: `profile-version-dispatch`, `profile-devices-dispatch`,
  `ssh-dispatch` (plus the 4a set).
- Terminal, Files and MQTT clients; `SshConnector`, `SshEnvironment`; tunnel control; `TunnelManager`.
- Making the `DeviceDirectory.background` launches ambient-aware (they are not on the assistant path, risk R5).
- TV, icon, clipboard code; releases, tags, version bumps.

## Affected surface contracts

Read as source of truth:

- [`docs/agent-contract.md`](../../agent-contract.md): "Optional profile information" (enqueue/worker
  sentences), "Optional SSH command" (worker admission paragraph), "Minimal execution", the shared-guard
  paragraph, the deterministic-tests inventory. Edited at completion, see `ledger.md`.
- [`docs/contract.md`](../../contract.md): "Optional LLM assistant" profile-information paragraph
  (information-owned request starts / actual HTTP worker cleanup). Edited at completion.
- Read-only context, expected no edit: [`docs/contracts/meta-contract.md`](../meta-contract.md),
  `docs/files-contract.md`, `docs/ssh-persist-contract.md` (shared connector/login/host-key policy only),
  `docs/mqtt-contract.md`, `docs/terminal-*-contract.md`. None mentions `FrpJumpApi`, owned API requests or
  assistant worker admission.
- `docs/contract.md` device-list, features-check and Connections-refresh paragraphs (lines 168-178, 288,
  518-523) stay true and are not edited.

If implementation shows another surface or guarantee is affected, stop and obtain approval of a revised contract.

## Interfaces and constraints

### `FrpJumpApi` (one path)

```kotlin
class FrpJumpApi(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) : AgentApi {
    suspend fun version(controlUrl: String): VersionResponse
    suspend fun devices(c: Credentials): List<OwnedDevice>
    // every other public signature unchanged

    private suspend fun call(request: Request): String {
        admitSideEffect(request.url.pathSegments.last())   // no-op outside an owned run (apart from ensureActive)
        val call = http.newCall(request)
        try {
            return blockingCancellable(call::cancel) {
                val response = try { call.execute() }
                    catch (e: IOException) { throw e }
                    catch (e: RuntimeException) { throw IOException("canceled due to $e", e) } // what AsyncCall.run did
                response.use { readResponse(request, it) }
            }
        } catch (e: IOException) {
            throw NetworkException(NetworkException.Reason.UNREACHABLE, request.url.host, e.message ?: e.javaClass.simpleName, e)
        }
    }
}
```

- `readResponse` is moved verbatim (redirect refusal, `BROKE`, `ApiException`); it now always runs on IO.
- The default client no longer installs a custom `Dispatcher`; nothing else about the client changes.
- `endpoint(...)` (https check, `NOT_HTTPS`) still runs before `call`, so an invalid URL throws before
  any admission, as today.
- Deleted: `ApiCallbackExitExecutor.kt` entirely (including the typealias), `ownedCall`, `Call.await()`,
  `beforeOwned*`, the imports they need.
- No test seam is added: tests inject `http` through the existing constructor parameter.

### `DeviceDirectory`, `ProfileInformation`

- `pull(): Boolean`, `private fetch(wait: Boolean): Boolean`, `checkFeatures(): Boolean`,
  `refreshAll()`: the `dispatch` parameter is removed; bodies otherwise unchanged. `pull` keeps
  `coroutineScope { launch { checkFeatures() }; fetch(wait = true) }` and `refreshAll` keeps
  `coroutineScope { ... async { r.devices.pull() } ... awaitAll() }`: the ambient `SideEffectAdmission`
  element reaches `FrpJumpApi` by ordinary context inheritance; no `CoroutineScope(...)` and no
  `background` scope may sit between the tool and `FrpJumpApi` on this path (constraint, pinned by test).

### `AssistantProfileInformationTool`

- Constructors: `(refresh: suspend () -> Unit, read: suspend () -> ProfileInformationState)` and
  `(information: ProfileInformation)`; no `dispatch`.
- The `ProfileInformation` constructor builds `refresh` as: read the ambient `SideEffectAdmission`; if
  present run `information.refreshAll()` under
  `SideEffectAdmission { operation -> outer.check("profile-$operation-dispatch") }`, else run it plainly.
  This keeps the category strings used by `beforeAction`/`checkedAction` probes byte-identical
  (`profile-version-dispatch`, `profile-devices-dispatch`) and keeps `FrpJumpApi` free of assistant
  vocabulary (Decision D1; the plan's literal `admitSideEffect(lastSegment)` would change the strings).

### `SshCommandOperation`, `AssistantSshCommands`, `AssistantSshCommandTool`

```kotlin
internal class SshCommandOperation {
    fun loseTarget()                                   // flag + cancel worker + trigger closer; non-blocking, any thread
    suspend fun run(work: suspend () -> ToolOutput): ToolOutput
    suspend fun execute(connector: SshConnector, command: String, available: () -> Boolean): ToolOutput
}
suspend fun run(...) {
    admitSideEffect("ssh-dispatch")                    // first statement; nothing else started before it
    var result: ToolOutput? = null
    try {
        coroutineScope {
            val finished = AtomicBoolean(false)
            val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally {
                    if (!finished.get()) withContext(NonCancellable + Dispatchers.IO) { yield(); disconnectClient() }
                }
            }
            val worker = async(Dispatchers.IO) { try { checkActive(); work() } finally { finished.set(true) } }
            publish worker and closer under `lock`; if targetLost already: worker.cancel(); closer.cancel()
            try { result = worker.await() } finally { closer.cancel() }
        }
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        if (!synchronized(lock) { targetLost }) throw failure
    }
    currentCoroutineContext().ensureActive()           // Stop/timeout/revocation wins over a local failure
    return synchronized(lock) { if (targetLost) targetFailure() else checkNotNull(result) }
}
```

- `checkActive()` becomes a suspend function: `currentCoroutineContext().ensureActive()` then
  `if (targetLost) throw CancellationException()`; it is called at exactly today's check sites in
  `execute` (before client, after connect, after authenticate, after startSession, and at worker start).
- `disconnectClient()` = `synchronized(lock) { client }?.let { runCatching { it.disconnect() } }`; it runs
  only on an IO worker (the `yield()` forces the hop even when the scope was already cancelled at
  launch, the 4a lesson). `loseTarget()` runs on whatever thread published the loss (possibly Main) and
  only sets a flag and calls `Job.cancel()` on two jobs; it never touches the socket.
- `execute` keeps its structure, texts, `receivedExit()`, `execAdmitted`, `targetFailure()`, the inner
  `coroutineScope` with two `async(Dispatchers.IO)` readers and its `catch (failure: Exception) { runCatching
  { ssh.disconnect() }; throw failure }`, and the `finally { session.close(); ssh.disconnect() }`.
  The `ssh-dispatch` check moves from "inside the guard together with `worker.start()`" to
  `admitSideEffect`.
- Why not `blockingCancellable` around each blocking call: it cannot cover the cleanup phase
  (`session.close()` after cancellation, entered with a cancelled coroutine), the gaps between calls, and
  the reader sibling; one closer child covers the whole operation lifetime (risks R3).
- `AssistantSshCommands.execute(profile, connection, command)` and `AssistantSshCommandTool(commands)`
  lose `dispatch`; the six watchers and `try/finally { watchers.cancel }` are unchanged and remain
  children of `execute`'s `coroutineScope`; `SshConnector`/`SshEnvironment` are untouched.

### `AssistantConversation`

- The two `guarded(owner, category) { action(); true } ?: throw CancellationException()` lambdas for the
  profile and SSH tools disappear: the tools are built as `AssistantProfileInformationTool(profileInformation)`
  and `AssistantSshCommandTool(sshCommands)`. The ambient admission built in 4a already runs them under
  `guarded`, so `beforeAction`/`checkedAction` probes keep firing for `profile-*-dispatch` and `ssh-dispatch`.
- `WebDispatch.kt` is deleted.

### Guarantees (replace the mechanism wording in the surface contracts)

- **G1.** Stop, disable (including off/on), provider revision/read failure, pop and timeouts cancel the exact
  in-flight HTTP call (`Call.cancel()`) and the exact in-flight SSH client (`SSHClient.disconnect()`), from an
  IO thread; they cancel nothing unrelated.
- **G2.** The one app-wide slot is released only after the HTTP exchange (including body read and close), or the
  SSH worker, both readers and every disconnect, have returned and the run coroutine has finished.
- **G3.** Before each profile-information request and each SSH command start, the owner's authority is checked
  under the chat guard; after a committed disable/Stop/revision/pop the check fails and nothing starts. A start
  whose check passed before the commit may still begin; it is then cancelled by G1. For SSH, caller
  cancellation is visible to the worker immediately and the worker re-checks it before the client, after
  connect, after authentication and after opening the session.
- **G4.** Ordinary calls behave as before. Differences: after cancellation their coroutine also waits for the
  actual exit of the HTTP thread; consequently the profile's `fetching` and the tunnel manager's `syncing`
  mutexes are released at actual exit; HTTP bodies are read on IO even for callers on Main; calls no longer
  pass through OkHttp's async-dispatcher caps (64 total, 5 per host); a `RuntimeException` from the interceptor chain is mapped to
  `NetworkException(UNREACHABLE)` (message, reason, host unchanged) instead of crashing the OkHttp thread (strictly better).

## SSH start window (operator condition)

Compared with today, between "worker admitted" and "remote exec started" (details and evidence in `risks.md`):
today the admission and `worker.start()` are under the guard, but connect/auth/session/exec run outside it,
guarded only by `checkActive()` (flag set only after the run coroutine observed its own cancellation) and by
the closer. After 4b the window is the same or narrower in every case and wider in none: the caller's
cancellation is visible to the worker synchronously, the check sites are the same, the last interval
(after the post-`startSession` check until the exec message is sent) is unchanged. No situation lets a remote
command start that cannot start today. No operator decision is required for SSH beyond variant 1; an optional
extra check immediately before `session.exec` is listed as D3 and not recommended here.

## Acceptance criteria

Greps (repository root, on the final tree). Must print nothing unless an accepted output is listed.

1. `grep -rnE "ApiCallbackExitExecutor|ApiRequestDispatch|WebDispatch|beforeOwned|ownedCall|ExitExecutor|Call\.await" app/src agent-core/src agent-openai/src` prints nothing except `app/src/main/java/ru/adsrv/frpjump/update/ReleaseSource.kt:171` (`private suspend fun Call.await()` of the in-app updater, out of scope).
2. `grep -rnE "\bdispatch\s*:|\bdispatch\(|, dispatch\b|\(dispatch\b|dispatch\)" app/src agent-core/src agent-openai/src --include=*.kt` prints nothing except 5 pre-existing false positives: the `CoroutineDispatcher.dispatch` overrides in `AssistantToolsBindingTest.kt`, `AssistantConversationTest.kt` and androidTest `FilesSessionVisitTest.kt`, and the two `"dns-dispatch:"` string lines in `PublicWebTransportTest.kt`.
3. `grep -rnE "CoroutineScope\(|SupervisorJob|closeRequested" app/src/main/java/ru/adsrv/frpjump/assistant/AssistantSshCommand.kt app/src/main/java/ru/adsrv/frpjump/api app/src/main/java/ru/adsrv/frpjump/devices/DeviceDirectory.kt app/src/main/java/ru/adsrv/frpjump/profiles/ProfileInformation.kt` prints nothing.
4. `grep -rn "NonCancellable" app/src/main/java/ru/adsrv/frpjump/api app/src/main/java/ru/adsrv/frpjump/assistant/AssistantSshCommand.kt`: exactly one line, the closer's `withContext(NonCancellable + Dispatchers.IO)`.
5. `grep -rnE "runInterruptible|\.interrupt\(\)" app/src/main agent-core/src agent-openai/src` prints nothing.
6. `grep -rnE "profile-\$operation-dispatch|\"ssh-dispatch\"" app/src/main`: exactly the mapping in `AssistantProfileInformationTool.kt` and the `admitSideEffect("ssh-dispatch")` in `AssistantSshCommand.kt`.
7. `grep -rn "getDeclaredField" app/src/test/java/ru/adsrv/frpjump/assistant`: only the two existing fixture lines that swap `ProfileRuntime.tunnels` (`AssistantSshCommandTest.kt`, `AssistantModelSurfaceTest.kt`); no probe into FrpJumpApi, DeviceDirectory or SshCommandOperation. (4a criterion 6 as worded is already false on `main` for those two lines; the intent is "no transport probe".)
8. `grep -n "background\." app/src/main/java/ru/adsrv/frpjump/devices/DeviceDirectory.kt`: exactly two lines (`refresh`, `setDisplay`), neither reachable from `refreshAll`/`pull` (risk R5).
9. `grep -rn "dispatcher(" app/src/main/java/ru/adsrv/frpjump/api` prints nothing.

Unchanged-policy review (no hunk may touch): `AgentApi` interface, `FrpJumpApi` timeouts/redirect flags, `endpoint`/`authorized`/`readResponse` bodies,
`NetworkException`/`ApiException` texts, `SshConnector`, `SshEnvironment`, `captureSshStream`, `receivedExit`, `targetFailure`, every
`ToolOutput` text in `AssistantSshCommand.kt`, `DeviceDirectory` cache/stale/feature logic, `ProfileInformationState`.

Named tests (every old scenario is mapped in `test-map.md`; the number of tests must not decrease except by explicit rows there):

- new `FrpJumpApiCancellationTest` (replaces `OwnedApiRequestTest`): ambient refusal sends nothing for every
  endpoint (server-side count); ordinary and ambient calls map success/HTTP/body/network/redirect/non-IO failure
  identically; cancel cancels the exact call and waits for exit (application-interceptor latch and loopback
  stalled server/body); IOException after cancel is a cancellation; already-cancelled caller sends nothing;
  admission is asked before the request is built only for valid URLs (invalid URL: no admission).
- new `DeviceDirectoryAdmissionTest`: the ambient admission reaches `GET /version` and `GET /devices` of every profile
  through `ProfileInformation.refreshAll` (exact categories and counts); a refusing admission sends nothing and cancels;
  no ambient element leaves `pull` ordinary; a `CoroutineScope`/`background` hop in `pull` would fail it.
- rewritten `AssistantProfileRefreshOwnershipTest` (4 tests, see map): revoke-before-start for four starts and every
  cause; start wins the guard then commit cancels it; interceptor/body hold keeps the slot across every cause and reopen
  with Stop reaching the exact calls (`EventListener.canceled`); tool-switch-only commit.
- `AssistantSshCommandTest`: existing tests keep their names and scenarios (constructor/ambient-admission edits only) plus:
  `commit after the ssh-dispatch check constructs no client`, `cancel at each phase never starts the command and disconnects`
  (factory, connect, auth, session, read, join/cleanup), `disconnect runs on an IO thread for Stop and for target loss, never on the caller or
  publisher thread`, `cancel while the sibling reader blocks releases it`, and new real-SSHJ
  `AssistantSshCommandRealSshTest::disconnect unblocks a real blocked exec read and join` (loopback Apache SSHD, ephemeral key).
- mechanical (constructor/ambient edits, same scenarios): `AssistantProfileInformationToolTest`, `AssistantToolPipelineTest`,
  `AssistantProfileResultsTest`, `AssistantModelSurfaceTest`, `ProfileInformationTest`, `SyntheticProfileInformation`.
- instrumented: `AssistantChatScreenTest` (chat-component runner) and `SavedConnectionOutcomeTest` compile and pass;
  `ProfileInformationFixture` updated.

Every start/cancel check introduced or moved has a test that fails when that check is removed (review lesson 2):
`admitSideEffect` in `call` (ambient refusal test), `ssh-dispatch` admission, `checkActive()` at each of its sites (phase
tests), the closer (disconnect-promptly tests), the IO hop (thread-identity tests), the tool-level category mapping (probe
tests on the exact strings), context propagation through `pull` (admission test). "Nothing sent" is asserted on the server
side (loopback listener count or `callStart` absence after an admission refusal); application interceptors are not used
for that claim (lesson 3). `withTimeoutOrNull`/`delay` negative windows are extra detection only; every synchronization is a latch (lesson 4).

## Validation

- Focused first:
  `./gradlew --no-daemon :agent-core:test :agent-openai:test` then
  `:app:testDebugUnitTest --tests '*FrpJumpApiCancellationTest' --tests '*DeviceDirectoryAdmissionTest' --tests '*ProfileInformationTest' --tests '*AssistantProfileRefreshOwnershipTest' --tests '*AssistantProfileInformationToolTest' --tests '*AssistantSshCommandTest' --tests '*AssistantSshCommandRealSshTest' --tests '*AssistantToolPipelineTest' --tests '*AssistantProfileResultsTest' --tests '*AssistantModelSurfaceTest' --tests '*ServerFeaturesTest' --tests '*DeviceDisplaySaveTest' --tests '*VersionResponseTest' --tests '*ProfileLoadOwnershipTest' --tests '*SavedProfileRetryTest' --tests '*ProfilesTest' --tests '*AssistantChatOwnershipTest' --tests '*AssistantWebOwnershipTest'`.
- Full gates:

  ```sh
  ./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest \
    :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
  git diff --check
  ```

  Inspect real test counts from the result XML (before/after table in `test-map.md`).
- `repo-docs` skill checker on the edited `docs/agent-contract.md`, `docs/contract.md` and this contract.
- Emulator `emulator-5580` (API 35), reuse, no wipe; synthetic credentials, loopback servers and the fake SSH client only:
  1. `AssistantChatScreenTest` with the chat-component runner (`-PassistantChatComponentTests=true`,
     `:app:connectedDebugAndroidTest`, `ANDROID_SERIAL=emulator-5580`, class filter): chat Stop/cancel,
     `profileInformationRequiresExplicitEnabledInvocationAndShowsKnownUnavailableResult`,
     `profileSearchShowsAllVariantsAndRetainsSelectedFactsAfterProviderFailure` (use `ProfileInformationFixture`),
     `sshCommandRequiresOptInAndDisclosesFullAccessAndPrivateOutput`, plus the four 4a-named tests. Count executed
     tests from the instrumentation output (21 `@Test` today).
  2. `SavedConnectionOutcomeTest` with the default runner (enroll through `FrpJumpApi`, 9 `@Test` today).
  3. Not required, because 4b touches no UI/Terminal/Files/Devices/Connections code path: `DeviceIconsTest`,
     `FilesSessionVisitTest`, `TerminalDiagnosticsTest`, `SshSessionLifecycleTest`, `MultiSessionTextModeTest`
     (they use `SshSession`/`SftpBrowser`, not `FrpJumpApi` or `SshCommandOperation`). If the implementer touches a UI
     file, run the portrait/landscape/200% font matrix and restore font scale 1.0.
  Emulator results are scoped evidence, not physical-device, vendor-IME or live-service validation.
- No production profile, key, provider, SSH, tunnel or broker is accessed.
- Gitleaks scan of new commits before pushing. This contract is temporary: after verified completion update the surface
  contracts per `ledger.md`, mark it complete, then delete it.

## Decisions (2026-11-12: D1, D2, D4 accepted; D3 declined for 4b)

- **D1. Where the `profile-<op>-dispatch` category is formed.** Recommended: in `AssistantProfileInformationTool`
  (wrapping the ambient admission), `FrpJumpApi` uses the neutral endpoint name. Alternative: hard-code
  `"profile-$op-dispatch"` in `FrpJumpApi.call` (shorter, but the domain API then knows assistant vocabulary and
  every ambient call from a future owned tool would be reported as a "profile" dispatch).
- **D2. G4 delta** (see G4) is accepted as the "ordinary calls unchanged except waiting for actual exit" of the plan, including
  the mutex-hold consequence and the removed 5-per-host cap. Informational; no code mitigates it.
- **D3. Optional extra start check immediately before `session.exec`** (a second `admitSideEffect` or `ensureActive`).
  Not recommended in 4b (adds a category/behaviour; the window is already not wider than today). Needs a decision only
  if the operator wants the SSH window narrower than today.
- **D4. Real-SSHD test** (`AssistantSshCommandRealSshTest`, one loopback Apache SSHD, ephemeral key, about 1-2 s). Recommended:
  it is the only evidence that SSHJ really unblocks reads/join on `disconnect()`; unit tests use a fake client.
