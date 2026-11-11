# Assistant tool catalog and model-surface characterization tests

Status: Approved by the operator on 2026-11-11 (phases F0–F1 of the assistant
architecture review). Implemented; independent review passed with notes, which
are fixed. Emulator validation is pending.

## Goal

Make adding an assistant tool a one-place change that the compiler enforces,
without changing anything the model, the user or the stored files can observe.
Two small steps that land as two separate pull requests, F0 first and then F1:

- **F0, characterization tests.** Pin everything the model sees today (tool
  schemas and order, both prompts, invalid-argument results, SSH and web
  failure texts) with tests written and run green against unchanged
  production code, before any refactoring.
- **F1, tool catalog.** One enum, `AssistantToolId`, becomes the only place
  that holds a tool's wire name, persisted setting key and effect class. Typed
  IDs replace the string IDs in the settings write path, the Tools binding,
  the chat progress state and the UI text mapping.

Both steps are behavior-neutral. Later phases of the review (shared tool
pipeline, provider neutrality, structured cancellation, confirmation of
changing calls, operations layer) are out of scope and need their own approved
contracts.

## Scenarios

1. The five tools (`web_search`, `read_page`, `inspect_image`,
   `profile_information`, `ssh_command`) are offered to the provider in exactly
   the same order, with exactly the same names, descriptions and JSON schemas
   as before. The Tools screen lists the same five switches in the same order
   with the same texts and the same stored keys.
2. Every invalid-argument shape (extra key, missing key, number for string,
   string for the integer `offset`, `null`, blank, over-length) gets exactly the
   same `ToolOutput` text and `ToolOutcome` as before.
3. SSH tool and web failure texts are unchanged for every branch.
4. Chat progress for a call of a known tool still shows that tool's progress
   text; a call with an unknown tool name still shows the failed progress.
5. An old `settings.json` and old saved chats load as before; a new
   `settings.json` is byte-compatible with the old shape.
6. A desynchronized catalog is caught by a test: every `settingKey` exists in
   the encoded `AppSettings`.

## Non-goals

- Any change to a model-visible text, schema, tool order, prompt, result or
  error, any UI string, string resource or screen order.
- Any change to `AppSettings` field names or `@SerialName` values, the settings
  file shape, `SavedChat` format version 1, or the provider file.
- Using `effect` for any decision (declaration only), confirmation dialogs, a
  shared tool pipeline, typed arguments, provider neutrality changes, structured
  cancellation, the operations layer, `skill.md` wiring or any prompt change.
- Changes to TV, icon or clipboard code; releases, tags or version bumps.

## Affected surface contracts

- [`docs/agent-contract.md`](../../agent-contract.md): "Optional public Web
  tools" (five switches, order, autosave, shared Tools binding), tool results,
  progress and ownership guarantees. Expected to need no edit: behavior is
  unchanged.
- [`docs/contract.md`](../../contract.md): "UI rules" and the optional LLM
  assistant section (screen texts and order). Expected to need no edit.
- Read-only context: [`docs/contracts/meta-contract.md`](../meta-contract.md).

If implementation shows that a surface contract must change, stop and obtain
approval of a revised contract before proceeding with that scope.

## Interfaces and constraints

- `ops/Effect.kt`: `enum class Effect { READ_LOCAL, READ_REMOTE, CHANGE_LOCAL,
  CHANGE_REMOTE }` with `val changes: Boolean`. Declared only; no production
  code reads `effect` in this feature.
- `assistant/AssistantToolId.kt`:
  `enum class AssistantToolId(val wire: String, val settingKey: String, val effect: Effect)`
  with `WEB_SEARCH`, `READ_PAGE`, `INSPECT_IMAGE`, `PROFILE_INFORMATION`,
  `SSH_COMMAND` in that order (= Tools-screen order = order of `tools` in
  provider requests) and `fromWire(name): AssistantToolId?`. It is the only
  main-source file containing the five wire-name literals.
  Settings keys: `assistant_search`, `assistant_read_page`,
  `assistant_inspect_image`, `assistant_profile_information`,
  `assistant_ssh_command`.
- `AssistantTools` gets `operator fun get(id)` and `fun with(id, enabled)`;
  `SettingsStore.setAssistantTool(id: AssistantToolId, enabled)`; the
  normalization list of tool fields in settings decoding is built from the
  catalog. `AppSettings` fields and `@SerialName` values are untouched.
- `ChatToolProgress` replaces the five per-tool values with
  `Tool(val id: AssistantToolId)`; `SUMMARY` and `FAILED` stay. An unknown
  call name yields `FAILED`.
- `ui/settings/AssistantToolTexts.kt` is the single mapping from a tool to its
  string resources (title, hint, progress, SSH warning if any), using
  exhaustive `when` without `else`.
  `AssistantToolsBinding.change(id: AssistantToolId, ...)` drops the string
  allow-list.
- Existing tests are the specification. A test may change only where it uses a
  removed or renamed signature, keeping its scenario; each such change is
  reported.
- Repository documentation and comments stay in English.

## Acceptance criteria

1. F0 tests exist in `AssistantModelSurfaceTest` (plus the golden resource
   `app/src/test/resources/assistant/tool-specs.json`), were run green on
   unchanged production code, and are unchanged and green after F1. Tool specs
   are compared as `JsonElement`.
2. F0 covers: the five `ToolSpec`s and their order; `INSTRUCTIONS` and
   `PROMPT` (and versions); every invalid-argument shape for every tool
   (exact text and outcome); the SSH failure texts (unavailable, setup, every
   execution-failure branch, every target-loss branch); `webFailureDescription`
   for every `WebFailure`.
3. `grep -rn '"web_search"\|"read_page"\|"inspect_image"\|"profile_information"\|"ssh_command"' app/src/main --include=*.kt`
   finds literals only in `AssistantToolId.kt`.
4. A test checks every `AssistantToolId.settingKey` is a key of
   `Json { encodeDefaults = true }.encodeToJsonElement(AppSettings())`, and that the `AssistantTools`
   get/with accessors and `setAssistantTool` toggle exactly the field of that key.
5. A test checks `Tool(id)` progress for each known tool name and `FAILED` for
   an unknown one.
6. `AssistantSettingsTest`, `AssistantToolsBindingTest`,
   `AssistantChatScreenTest` (compiled; see Validation) keep their scenarios.
7. No `AppSettings` `@SerialName`, `SavedChat` or string resource is changed;
   `git diff` shows no edits under `app/src/main/res`.
8. No surface contract needs editing; otherwise stop and report.

## Pull request split

- **PR 1 (F0):** this feature contract, `AssistantModelSurfaceTest.kt` and
  `app/src/test/resources/assistant/tool-specs.json`. Production code is
  untouched; the tests are green on the base.
- **PR 2 (F1):** everything else: `ops/Effect.kt`, `AssistantToolId.kt`,
  `AssistantToolTexts.kt`, the edits to `AppSettings.kt`,
  `AssistantConversation.kt`, `AssistantChatHistory.kt`, the three tool
  classes, `AssistantChatScreen.kt`, `AssistantToolsScreen.kt` and
  `AssistantToolsBinding.kt`, the new `AssistantToolCatalogTest.kt` and
  `AssistantToolTextsTest.kt`, and the signature-only edits to existing tests.

## Validation

- Run the F0 tests before changing production code and confirm they are green.
- After F1:

  ```sh
  ./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest \
    :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
  git diff --check
  ```

  Inspect the real test counts and failures in the test result XML files.
- Run the repository documentation checker for the new contract file.
- Instrumented tests (`AssistantChatScreenTest` and others) are compiled by
  `assembleDebugAndroidTest`. They need an emulator; where none is available they
  are reported as not run, not as passed. Portrait, landscape and 200% font
  checks of the Tools and Chat screens need `emulator-5580`.
- This feature contract is temporary: after verified completion, delete it.
