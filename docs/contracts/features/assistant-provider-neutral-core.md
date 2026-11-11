# Provider-neutral assistant core

Status: Approved by the operator on 2026-11-11 (phase F3 of the assistant
architecture review). Implemented; independent review passed with notes,
which are fixed.

## Goal

Chat, history and tool code in `app/.../assistant/` stop depending on OpenAI
types. They use provider-neutral types from `agent-core`, so a second provider
can later be a new module without edits in `assistant/`.

Behavior-neutral: nothing the model, the user or stored files can observe
changes. Saved chats, continuation strings, error texts, statuses and
outcomes stay identical.

## Scenarios

1. **Persistent continuation.** With the OpenAI provider, a reply's
   continuation is saved as the same string as today and restored from it.
2. **Non-persistent provider.** For a provider that cannot persist
   continuations, the continuation stays local (in memory) exactly as today;
   restoring a saved continuation string with such a provider fails the same
   way as today (`checkNotNull`).
3. **Provider failures.** Timeout, invalid, incomplete and too-large responses
   set the same chat flags (`transportTimeout`, `invalidReply`) and statuses as
   today; the summary failure status is read the same way.
4. **Image inspection failures.** `inspect_image` failure texts (HTTP with
   status, timeout, network, request/response too large, incomplete) are
   byte-identical; `WebException` carries the same information.
5. **Image inspection availability.** The tool gets an inspector exactly when
   the active provider supports image inspection, as today.

## Non-goals

- Removing the `complete(request, dispatch)` overload and the
  `is OpenAiAgentProvider` check in `AssistantConversation.complete` (F4).
- Provider setup, the provider store/factory (`AssistantProviderStore.kt`) and
  the provider settings screen and its error texts
  (`ui/settings/AssistantProviderScreen.kt`, `AssistantProviderErrors.kt`) may
  stay OpenAI-specific.
- No change to wire formats, the OpenAI request/response codec, saved formats
  (`SavedChat` version 1, the provider file), string resources or prompts.

## Affected surface contracts

- [`docs/agent-contract.md`](../../agent-contract.md): "Separation", "Chat
  history" and "Optimistic same-model images". They describe behavior, which
  does not change. If a sentence names an OpenAI-only type as the mechanism
  for chat/history code, update it to the neutral type after verification;
  otherwise no edit.

## Interfaces and constraints

In `agent-core` (`ru.adsrv.frpjump.agent`):

- `interface PersistentContinuations { fun saveContinuation(value: AgentContinuation): String; fun restoreContinuation(saved: String): AgentContinuation }`
  (use the actual continuation type name from `AgentProtocol.kt`).
- `ImageInspector` moves from `agent-openai` to `agent-core` unchanged in
  shape. `OpenAiAgentProvider` implements both interfaces.
- `enum class ProviderErrorKind` with exactly the values of today's
  `ProviderFailure`, and `open class ProviderException(val kind: ProviderErrorKind, val status: Int?, message: String?) : Exception(message)`.
  `OpenAiProviderException` extends it; its message and existing properties
  stay as they are (a compatibility alias such as
  `typealias ProviderFailure = ProviderErrorKind` in `agent-openai` is fine if
  it keeps that module's code and tests unchanged).
- `agent-core` must not depend on `agent-openai` or Android.

In `app`:

- `AssistantChatHistory.kt`: `as? PersistentContinuations` instead of
  `OpenAiAgentProvider`, same semantics (scenario 2).
- `AssistantConversation.kt`, `AssistantWebTools.kt`, `PublicWebTransport.kt`:
  core types (`ImageInspector`, `ProviderException`, `ProviderErrorKind`)
  instead of `agent.openai` ones, except the F4 line above.

## Acceptance criteria

- `grep -rn "agent\.openai" app/src/main/java/ru/adsrv/frpjump/assistant`
  finds only `AssistantProviderStore.kt` and the single
  `is ...OpenAiAgentProvider` check in `AssistantConversation.complete`.
- `OpenAiAgentProviderTest`, `OpenAiCheckpointTest` and all other
  `agent-openai` tests pass; `AssistantModelSurfaceTest` (F0, including the
  image failure texts) passes unchanged; existing tests change only in
  imports or type names (list each in the PR).
- New tests: a fake non-persistent provider keeps the continuation local and
  saves no string; the `ProviderFailure` to `ProviderErrorKind` mapping is
  one-to-one by name; `OpenAiProviderException` is a `ProviderException` with
  the same kind and status.
- No edits under `app/src/main/res`; no `@SerialName` or saved-format change.

## Validation

```sh
./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
git diff --check
```

Inspect the real test counts and failures. Run the repository documentation
checker. No screen changes, so no emulator run is required. This feature
contract is temporary: after verified completion, delete it.
