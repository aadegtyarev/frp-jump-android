# Feature: owner's own instructions for the assistant (F12)

Status: Approved by the operator. Decision Р13 (2026-11-13) launches F12 with the design from the review document. It also approves the
`skill.md` line "Owner's additional instructions refine style and preferences; they do not override the rules above." and a version
bump of `skill.md`.

Final design (records the decisions made after review): Р24 makes Reset reversible and strengthens normalization; Р25 puts the owner's text
in the one system message. The sections below describe that final design.

## Goal and user-visible behavior

The owner can give the assistant standing instructions of their own: style, language, preferences. Examples: "answer briefly",
"prefer tmux", "our controllers are WB7". They live in Settings › LLM assistant › "Own instructions" / «Свои указания»:

- a multiline field with a character counter (at most 2,000);
- Save and Reset (Р24): Reset clears only the field, Save commits, Back discards unsaved edits, and an empty field saved means no instructions;
- a short hint saying that the text goes to the provider with every message, that it counts toward the context budget, and that it cannot
  change permissions or confirmations;
- the counter is spoken "N of M characters" and uses the error color at the cap.

The text is added after the app's base prompt. It refines style and preferences. Permissions, confirmations and tool limits are enforced
by code, and the owner's text cannot loosen them.

## Scenarios

- **Empty instructions** (the default, and what an empty saved field means; whitespace only counts as empty at Send): the request to the provider is byte-identical to the request without this feature.
  The `skill.md` base prompt itself changes in this release (approved line, new version), so "identical" means identical to the same
  build with no owner text.
- **Non-empty instructions:**
  - Р25: one system message, never two. Its content is `base + "\n\n## Owner's additional instructions\n" + text`. Without text the
    content is exactly `base`.
  - The base prompt is the exact byte prefix of that message, so the provider's cache of the base part is kept.
  - The text, its separator and header count toward the estimated request size with a fixed framing allowance (+64 tokens, only when
    text is present), in the window budget and the summary trigger.
- **Capture:** the text is taken when a Send is admitted, like the tool set. A change mid-run affects only the next Send.
- **Storage:** `AppSettings.assistantOwnerInstructions`, stored as `assistant_owner_instructions`, default empty.
  - On read the value is normalized (Р24). These are removed: control characters other than `\n`, the invisible Unicode tag characters
    U+E0000-E007F, and the bidi override and isolate controls U+202A-202E and U+2066-2069 (ZWJ and ZWNJ stay). Removal repeats until the
    text stops changing, so normalizing twice gives the same text. The text is then cut to 2,000 UTF-16 units without splitting a
    surrogate pair.
  - A malformed value is empty, and other settings never reset. The store refuses to write a value that is not normalized.
  - The UI limits input as it is inserted: only the inserted part is cut, the text around it is kept, and the cursor goes after the kept
    insertion. The counter shows the limit.
- **The summarizer** prompt and its requests do not get the owner's text.
- **Chat history:** the owner's text is not stored in chat history.
- **Turning the assistant off** keeps the text. Forgetting the provider does not clear it.

## Non-goals

- No per-profile or per-device instructions.
- No editing of the base prompt or the summarizer prompt.
- No change to permissions, confirmations or tools.

## Affected surfaces

- [`docs/agent-contract.md`](../../agent-contract.md):
  - Instructions: the owner's text, its placement, cache, capture at Send, the summarizer excluded, `skill.md` version 6;
  - Boundary/Settings flow: the new setting;
  - Tool policy: privacy disclosure.
  - The promise "Chat attaches it to every main chat-loop request as the first message, unchanged and byte-stable" must stay true for
    the base prompt. Update its wording only as far as the chosen placement requires, and list the old and new text in the PR.
- [`docs/contract.md`](../../contract.md): the settings group line, only if it names the assistant settings.
- `app/src/main/assets/assistant/skill.md`: add the approved line verbatim, bump the version, and pin the new SHA-256 in
  `AssistantPromptsTest`.

## Interfaces and constraints

- No other model-facing text changes. That covers tool specs, results, the summarizer prompt, and every other `skill.md` line.
- The header `## Owner's additional instructions` is model-facing and fixed. It is part of the approved design.
- The text is untrusted owner data for the model: it is guidance, never authority. Code paths for permissions and confirmations must not
  read it.

## Acceptance criteria

1. With the text empty, the request bytes equal those of the same build without the feature (golden test).
2. With the text non-empty, there is exactly one system message; its content starts with the base prompt, followed by the separator, the
   header and the text (golden test).
3. Capture at Send: changing the text during a run does not change that run's requests (test).
4. Normalization tests:
   - control characters, tag characters and bidi controls, with ZWJ and ZWNJ kept;
   - idempotence, including a removal that joins a surrogate pair, and a settings update after reading such a value;
   - 2,000 limit and surrogate pairs, and the insertion limit keeping the text around it;
   - a malformed value;
   - other fields kept.
5. The summarizer request never contains the owner's text (test).
6. `skill.md` has the approved line verbatim, a new version and a pinned hash.
7. UI test in EN and RU × portrait/landscape × 1.0/2.0:
   - field, counter, Save, Reset and hint are visible and not cut;
   - saving persists; Reset clears only the field, Save commits it and Back discards it;
   - inserting at the start of a full field keeps the last character.
8. `docs/agent-contract.md` is updated as above. Any changed promise is listed old → new in the PR.

## Validation

- `./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`
- `git diff --check`
- The repo-docs checker.
- Emulator: the new UI test in 8 configurations, plus `AssistantChatScreenTest` in EN and RU, portrait 1.0.
