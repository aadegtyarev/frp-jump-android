# Split `AssistantConversation` (behavior-neutral)

Status: Approved by the parent on 2026-11-12 (phase F6; decisions D1-D5
accepted). Implemented; complete for final verification (emulator run of
`AssistantChatScreenTest` still pending; delete this file after it).

Decisions: D1 real package `ru.adsrv.frpjump.assistant.chat` (import-only
edits); D2 `ChatProbes` replaces `beforeAction`/`checkedAction` (mechanical
test edits accepted, scenarios unchanged); D3 `MAX_*`/`INSTRUCTIONS*` stay as
aliases in the facade companion; D4 `AssistantSummary` and
`AssistantChatHistory` stay in place; D5 250-line cap for every file under
`assistant/**` (recursive), `send()` at most 40 lines, the long `INSTRUCTIONS`
line stays for F7. The `AssistantChatRuntime` KDoc is corrected (wording only)
to say that `persist()` takes the store's `queue` monitor inside the gate. Tool
order is exactly today's: web_search, read_page, inspect_image,
profile_information, ssh_command (the catalog order, pinned by
`AssistantModelSurfaceTest`).

## Goal

`AssistantConversation` mixes saved-document edits, UI projection, run
ownership, the Send pipeline, tool assembly and error mapping in one 353-line
class. Split it into small units that are understandable and testable apart
(pure functions without coroutines where possible). The assistant, its chat
screen, the saved history and every guarantee stay exactly as they are.

User-visible behavior: none. This is a refactoring.

## Scenarios (all must behave as before)

- Send, Stop, close, refresh (settings/provider/availability change), reload,
  retry, hide and delete of history, edit of the draft, TOO_LONG, SETUP/READ,
  HISTORY_FULL, busy admission, history-read-failed lock.
- A run finishing with every `AgentFinish`, provider/transport failure kind and
  setup failure maps to the same `ChatProblem` (and `problemStatus`) as today.
- A revoked owner (stopped, disabled, revision changed, closed) commits no
  document, progress, source, summary or dispatch; same cancellation exceptions.
- History saved before this change loads; history saved after it loads in the
  previous build (byte-identical JSON).
- Summary-model preparation, window omission, tool progress, receipts and
  sources are published in the same order, under the same checks.

## Non-goals

- No change of behavior, UI, texts, saved/encrypted file format, instruction
  text (`INSTRUCTIONS`/`INSTRUCTIONS_VERSION` stay; F7 owns them), limits, tool
  set or order, provider interfaces, timeouts or cancellation semantics.
- No new lock, no new suspension point, thread hop or IO inside the gate. Lock
  order unchanged: store IO monitor -> gate; gate -> the store's short `queue`
  monitor through `persist()`; notifications after the gate is released.
  `persist()` stays under the gate exactly where it is today.
- No change to `AssistantSummary`, the I/O code of `AssistantChatHistory`, tool
  classes, `AgentModule`, `SideEffectAdmission`/`blockingCancellable`.
- No release, version bump or tag.

## Affected surface contracts

- [Agent contract](../../agent-contract.md): "Text chat", "Chat history",
  "Automatic working summaries", "Minimal execution" and shared guard
  paragraphs, and "Related surfaces and implementation references" (link to the
  conversation owner).
- [App and shared UI rules](../../contract.md): only through the unchanged chat
  screen; no change expected.
- Other legacy surface contracts are not affected. Process:
  [meta-contract](../meta-contract.md).

## Interfaces and constraints

New package `ru.adsrv.frpjump.assistant.chat` in
`app/src/main/java/ru/adsrv/frpjump/assistant/chat/`:

| File | Content |
| --- | --- |
| `ChatModels.kt` | display models `ChatTurn`, `ChatState`, `ChatProblem`, `ChatToolProgress`, `ChatToolFailure`, `ChatProfileInformation`, `ChatToolReceipt`; `ChatLimits` constants |
| `ChatSaved.kt` | the serializable `Saved*`/`ChatBinding` classes moved unchanged (same properties, order, defaults, `@Transient`) |
| `ChatDocument.kt` | pure `SavedChat` transforms used inside guarded sections: append turn, update last turn, finish, interrupt, add source, set summary, mark omitted |
| `ChatProjection.kt` | pure `projectChat(...)`: `SavedChat` + pre-projected turns + flags + previous state -> new `ChatState` (today's `render()` body) |
| `ChatProblems.kt` | pure mapping: `AgentFinish`/transport flags/summary problem -> `ChatProblem`; `ProviderSetupProblem` -> `ChatProblem` (one function, fallback as parameter); provider failure kind -> flags |
| `ChatProbes.kt` | `internal interface ChatProbes { before(category); checked(category) }` with a no-op default replacing `beforeAction`/`checkedAction` |
| `ChatCells.kt` | gate-guarded state cells (`document`, `authority`, `revision`, `provider`, `closed`, `loading`, `pending`, state flow) and `Pending`; no lock of its own |
| `RunOwnership.kt` | `refreshLocked`, `stopLocked`, `owns`, `guarded` (probes outside/inside the gate as today) and send admission |
| `AssistantToolFactory.kt` | per-Send tool list: web tools (with the source-publish callback), profile information, SSH command; same order and conditions |
| `TurnRunner.kt` | one Send after admission: provider preparation, tool provider, summary, `module.run`, events -> document, final problem |

`AssistantConversation` (package `ru.adsrv.frpjump.assistant`, same path) stays
the facade with the unchanged public API: constructor parameters, `state`,
`edit`, `send`, `stop`, `close`, `refresh`, `reloadHistory`, `retryHistory`,
`hideHistory`, `deleteHistory`, and the `MAX_INPUT`, `MAX_TURNS`,
`MAX_HISTORY_CHARS`, `INSTRUCTIONS_VERSION`, `INSTRUCTIONS` constants (aliases
to `ChatLimits` where moved). `AssistantChatRuntime` keeps the gate, documents
the real lock order in its KDoc and exposes `probes`. `AssistantSummary` and
`AssistantChatHistory` stay in place.

Saved JSON: no `@SerialName`, no polymorphism, no sealed `Saved*` types, so
class and package names are not in the JSON; property order and defaults are
kept and a golden-JSON test pins the bytes.

## Acceptance criteria

1. Every `synchronized(gate)` site of the current code exists once, with the
   same body order; no new `synchronized`, `Mutex`, `withContext`, `launch` or
   await inside a gate block; `before` probes run outside and `checked` probes
   inside the gate at the same points. Reviewed against the site table in the
   task notes.
2. No `.kt` file under `app/src/main/java/ru/adsrv/frpjump/assistant/`
   (recursive) is longer than 250 lines; `send()` is at most 40 lines. Line
   length is not limited; moved lines are not reflowed unless touched.
3. Pure unit tests without coroutines: `ChatProjectionTest` (archive vs
   non-archive, running/pending, interrupted, last-turn split, problem fields,
   omitted/contextChanged/summarized), `ChatProblemsTest` (every `AgentFinish`,
   transport flags, every `ProviderSetupProblem`, fallback), `ChatDocumentTest`;
   a golden `SavedChat` JSON test written and passed on the baseline before the
   move.
4. `ChatProbes` replaces `beforeAction`/`checkedAction`; default no-op; unset in
   production.
5. Existing tests are the spec. Allowed edits: imports for `assistant.chat` in
   test and UI files that use moved types, and the mechanical replacement of
   hook-lambda assignments by `ChatProbes` (8 files, 15 sites, listed in the
   task notes; no assertion changes). `AssistantModelSurfaceTest` gets at most
   import edits.
6. Test counts change only by the added tests (baseline app 1697, agent-core
   36, agent-openai 37).
7. Mutation checks of the moved guarded sections, projection and error mapping
   each fail at least one test (list in the task notes).

## Validation

- Focused: all `assistant/*` and `ui/settings/Assistant*` unit tests.
- Gates: `./gradlew --no-daemon :agent-core:test :agent-openai:test
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
  :app:lintDebug`; `git diff --check`; repo-docs checker.
- Emulator (`emulator-5580`, reused, not wiped): `AssistantChatScreenTest` with
  the chat-component runner in portrait and landscape at font scale 1.0 and 2.0
  (restore 1.0); report actual counts and failures. Scoped emulator evidence
  only: no physical device, vendor IME or live provider.
- After verification: update the agent-contract implementation references, mark
  this contract complete, delete this file.
