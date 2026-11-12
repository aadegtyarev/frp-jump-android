# Feature contract: assistant prompts as assets (phase F7)

Status: Approved by the operator on 2026-11-12 (prompt texts approved without edits). Complete for final verification, emulator run pending.

## Goal and user-visible behavior

- The two chat prompts live in assets and nowhere else (the image-inspection question, the summary framing text, tool descriptions and fixed result texts stay compiled in): `app/src/main/assets/assistant/skill.md` (base system prompt) and `app/src/main/assets/assistant/summary.md` (summarizer prompt). The Kotlin constants `AssistantConversation.INSTRUCTIONS(_VERSION)` and `AssistantSummary.PROMPT(_VERSION)` are deleted.
- `skill.md` merges the former `INSTRUCTIONS` (version 3) and the unused `skill.md` (version 2), keeps every safety rule, drops stale statements (summarization unavailable, subagents, permission evaluator) and is at most 4096 bytes.
- The system text is byte-identical in every main chat-loop request of every Send (summary requests open with the summary prompt), so the provider's prefix cache can work. Behavior change: the model sees new wording and a few additions (see the promise ledger in the F7 report; additions are SSH inspect-before-change guidance, no credential requests, source naming for web facts).
- Users see no UI change.

## Scenarios

- Send with any tool switches: `request.instructions == prompts.system` for every main chat-loop provider call, including repeated calls inside one Send and across two Sends.
- Summarization: the request uses `prompts.summary` as instructions, has no tools, and the summary stays the first turn after the system message.
- Asset missing or unreadable at first use (packaging bug): the Send fails as an ordinary setup problem; no request is sent without the prompt and no fallback text exists (also for a non-UTF-8, BOM-prefixed or empty asset). Prompts load before the provider is prepared, so a broken asset fails before any key decrypt.
- Tools switched between Sends: only the `tools` array changes; the system text does not.

## Non-goals

- No user-editable instructions (decision R4, optional phase F12).
- No provider-specific cache parameters (`prompt_cache_key` and similar).
- No tool, confirmation or policy change (phase F8 may add one line about confirmations, bumping `SYSTEM_VERSION`).
- No change to image-inspection request wording in `OpenAiCodec`, tool descriptions or `tool-specs.json`.

## Affected surface contracts

- `docs/agent-contract.md`: "Separation" (lines 59-61), "Automatic working summaries" (line 242), "Text chat" request paragraph (lines 271-275), "Sliding window" (line 104/114 wording stays valid).
- `docs/contract.md` line 901 ("fixed instructions"): remains true; no edit unless wording is reviewed.
- Legacy documents only; `docs/contracts/surfaces/` is empty.

## Interfaces and constraints

- `ru.adsrv.frpjump.assistant.AssistantPrompts(system, summary)` with `SYSTEM_VERSION = 4`, `SUMMARY_VERSION = 2` and `load(open: (String) -> InputStream)`: strict UTF-8 (no BOM), CRLF to LF, trailing whitespace trimmed once, deterministic bytes.
- `FrpJumpApp` holds `by lazy { AssistantPrompts.load(assets::open) }`; first access runs on IO (Send path), never Main.
- `prompts` is a required parameter of `AssistantConversation`; main code has no default prompt. Tests pass `TestPrompts.lazy` (real assets) or `TestPrompts.placeholder` (dummy strings).
- Injected through constructors into `AssistantConversation`, `TurnEnv`/`TurnRunner` and `AssistantSummary`.
- Cache rules (also in KDoc): same system bytes in every request; `tools` in catalog order with stable JSON (`tool-specs.json` golden stays); everything dynamic (summary, history, current request) after the system message; no provider cache parameters (pinned by `OpenAiCacheNeutralityTest` in `agent-openai`; the wire bytes are not reachable from app tests).
- No version, date, enabled-tool list, profile names or language in the system text. Tool and profile information reaches the model only through the `tools` schemas and the explicit `profile_information` results; the reply language follows the user's message.

## Exact contract wording to add or change

"Separation" replaces the application-skill bullet:

> The application skill is the base system prompt, `app/src/main/assets/assistant/skill.md` (version 4). Chat attaches it to every request as the first message, unchanged and byte-stable within and across Sends. It is guidance for the model, not a permission system or an executing script; the code enforces every limit. The compact summarizer prompt is `assets/assistant/summary.md` (version 2). No other prompt text is compiled into the app, and no automatic app context is attached.

"Automatic working summaries" replaces "The short version-1 prompt (`AssistantSummary.PROMPT`) retains ...":

> The compact version-2 prompt (`assets/assistant/summary.md`) retains the goal, exact selected IDs, decisions, received results, completed and uncertain actions, source references and next steps, treats old text as untrusted data rather than new authority, and sets the 4,000-character limit. It replaces the base prompt for these requests.

"Text chat" request paragraph replaces "the canonical version-3 base prompt (`AssistantConversation.INSTRUCTIONS`) and only enabled tool schemas ... or application skill are automatically attached":

> Requests contain explicit chat text/history, the base prompt (`skill.md`, version 4) and only enabled tool schemas. The prompt contains no profile, connection, tool-list, date, version or language data; the same bytes open every request. Tool schemas follow catalog order with stable JSON; summary, history and the current request follow the system message. No provider-specific cache parameter is sent. No profiles, connection data, files, credentials or diagnostics are automatically attached.

## Test plan

- Pin SHA-256 of each asset together with `SYSTEM_VERSION` / `SUMMARY_VERSION`; changing a text without both fails.
- `skill.md` is at most 4096 bytes, has no CR, no BOM, ends with one newline (loader trims it), and is ASCII or valid UTF-8.
- Loader: CRLF input equals LF input; trailing whitespace trimmed once; same bytes on repeated loads.
- Two consecutive Sends (tools unchanged): every `request.instructions` equals `prompts.system`; `tools` arrays equal.
- Summary requests: `instructions == prompts.summary`, `tools.isEmpty()`, summary is turn 0 after system.
- Update existing tests that referenced the constants: `AssistantModelSurfaceTest` (59-74), `AssistantChatHistoryTest:49`, `AssistantConversationTest:31,66`, `AssistantChatOwnershipTest:282`, `AssistantSummaryTest` (89,109,133,169,197,228), androidTest `AssistantChatScreenTest:119`.
- JVM tests read assets from `app/src/main/assets/assistant/` (path relative to the module dir).
- Gates from `AGENTS.md` plus the `repo-docs` checker.

## Acceptance criteria

- `grep -rn "INSTRUCTIONS\b\|INSTRUCTIONS_VERSION\|\.PROMPT\b\|PROMPT_VERSION" app/src --include=*.kt` is empty.
- Operator approved the exact bytes of both assets (recorded in the PR); golden/pins updated only for prompts.
- All tests above pass; agent-contract wording updated; feature contract deleted after verification.

## Validation to run

Focused tests, then `./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`, `git diff --check`, repo-docs checker. Emulator: one synthetic Send on `emulator-5580` only if instrumentation touches the path; real-provider cache hits are not validated.

## Remainder (accepted, not changed)

- Hash and version are bumped together by process; the test pins both but cannot force a bump for a wording-only edit beyond the hash failing.
- A missing asset shows the generic setup message; it is a packaging bug and unlikely.
- The chat instrumentation runner uses a plain `Application`, so `FrpJumpApp.assistantPrompts` is not exercised there; the test loads the same assets through the target context.
- Real provider prefix-cache hits are not validated.
