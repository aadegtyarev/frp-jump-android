# Assistant: confirmation of changing tool calls (phase F8)

Status: Approved by the parent on 2026-11-12 (decisions R1, R6-R12; prompt sentence approved by the operator on 2026-11-12). Date: 2026-11-12. Base: `origin/main` 67df9c8 (0.3.65).
Nothing in this document is implemented. The proposed prompt change (section 12) needs operator approval before the asset is touched.

## 1. Goal and user-visible behavior

Before every call of a tool whose catalog effect `changes` (today only `ssh_command`) runs, the owner sees what will happen and answers:

- **Allow**: this call only.
- **For session**: this tool on this target (for `ssh_command`: this profile and connection) until the chat screen is left.
- **Always**: turns confirmation requests off for the whole app (the same setting as the switch in Settings).
- **Decline**: nothing is executed.

Read tools (`web_search`, `read_page`, `inspect_image`, `profile_information`) never ask. While requests are off, Chat shows a compact icon; its tap explains and links to the switch. Decisions below are the operator's (2026-11-11/12: R1, R6-R8, R11, R12); where they differ from the older spec text, they win.

## 2. Scenarios

| # | Scenario | Result |
| --- | --- | --- |
| S1 | Model proposes `ssh_command`, requests on, no grant | Card appears in the current turn; nothing runs; no SSH client exists |
| S2 | Allow | This call runs; the next one asks again |
| S3 | For session | This call runs; later calls on the same profile+connection skip the card; another connection asks; leaving Chat, assistant disable, or switching the tool off clears grants |
| S4 | Always | The app-wide switch is persisted as "requests off" first, then the call runs; the card is gone for all later calls, including in the same Send |
| S5 | Decline | Result `DECLINED` with fixed text; model continues the Send (may answer in text) |
| S6 | No answer in 5 minutes | Same as Decline, with the timeout text; card disappears |
| S7 | Stop, Back/close, assistant disable, provider change during the wait | Run cancelled, card removed, nothing executed, slot freed after exit |
| S8 | Open Tools and come back during the wait | Card still there (it lives in `ChatState`) |
| S9 | Rotation / recreation | Card kept (the retained ViewModel owns it) |
| S10 | Process death during the wait | Nothing was executed; on restore the turn is INTERRUPTED as today; the call shows without a result |
| S11 | Requests off in Settings | No cards; indicator in Chat; behavior as today (the owner has accepted the risk) |
| S12 | Invalid arguments for a changing tool | No card (parsing is pure and deterministic); the tool returns its usual "invalid arguments" failure; nothing runs |
| S13 | Target unresolved or not ready at ask time | Still asks (card shows "Unknown connection"): readiness can change between ask and run, so a skipped ask could let an unconfirmed command through |
| S14 | Two or more changing calls in one step | Asked one by one in order; a decline of one does not decline the others |
| S15 | Requests turned off while a card is pending | The pending card stays until answered (or Stop); the next call follows the new value |
| S16 | Saving "off" fails when pressing Always | This call is allowed once; an inline note says the setting was not saved; requests stay on |

## 3. Non-goals

- No per-tool/per-target persisted allow lists; `assistant_always_allowed` and "Always allowed: N" are dropped (R11).
- No override levels in the UI or logic yet (device, tunnel/target); only the persisted shape and model leave room for them.
- No confirmation for read tools, no new tools, no change to tunnel policy, host-key trust, SSH output limits or web limits.
- Not a replacement for code-level safety; the prompt rule is guidance only.
- No grants stored in chat history; no grant persists beyond the chat screen.

## 4. Decision and settings model

### 4.1 Decision (pure, in `app`)

```
enum ConfirmationDecision { ONCE, SESSION, ALWAYS, DENY }
data class GrantKey(tool: AssistantToolId, target: String?)   // ssh_command: "<profileId>/<connectionId>"
```

`authorize(call)`:
1. Tool unknown or `!effect.changes` -> proceed (null).
2. Tool describes no change request (invalid arguments) -> proceed to the tool, which fails on its own.
3. Policy resolves `ASK` for (tool, target)? If `OFF` -> proceed.
4. Session grant for the key -> proceed.
5. Ask the owner: ONCE -> proceed; SESSION -> record grant, proceed; ALWAYS -> persist "off", proceed; DENY -> `ToolOutput(DECLINED)`; timeout -> `ToolOutput(DECLINED)` with the timeout text.

The policy is read live at each call (not captured at Send admission, unlike tool booleans): a safety setting must not stay stale for a running Send. Turning requests back on affects the next call.

### 4.2 Settings model: global value plus future overrides

```
enum ConfirmationMode { ASK, OFF }                       // default ASK
data class ConfirmationPolicy(
    val global: ConfirmationMode = ASK,
    val overrides: List<ConfirmationOverride> = emptyList())   // always empty in F8
data class ConfirmationOverride(val scope: Scope, val mode: ConfirmationMode)
sealed Scope { Device(id), Target(profileId, connectionId) /* future; either, or both */ }
fun ConfirmationPolicy.resolve(tool, target): ConfirmationMode = most specific matching override ?: global
```

Only `global` is read or written in F8. `resolve` already takes tool+target so adding an override level changes no call site.

### 4.3 Persisted shape (new `AppSettings` field, `@SerialName("assistant_confirmations")`)

```json
"assistant_confirmations": { "version": 1, "global": "ask" }
```

- Absent -> `ask`. Unknown `version`, wrong type, unknown `global` string, or any parse problem -> `ask` (fail closed), sanitized independently in `SettingsStore.decode` like the tool booleans, so unrelated settings are never reset.
- Future overrides add an optional `"overrides": [{"scope": {"kind": "...", "id": "..."}, "mode": "off|ask"}]` inside the same object; a version-1 reader ignores it (`ignoreUnknownKeys`), a future reader treats an absent array as empty. No breaking change for either level or both.
- Known limitation: an older app rewriting the file drops the object (requests back to `ask`: the safe direction).
- Existing field names and `SavedChat` version 1 unchanged.
- `SettingsStore.setAssistantConfirmations(mode)` uses `update`; it must not touch `assistantAvailability.disableVersion`.

## 5. UI (exact texts)

| Element | EN | RU |
| --- | --- | --- |
| Card title | Run this command? | Выполнить команду? |
| Tool line | SSH command | Команда SSH |
| Target line | `<profile> · <device> · <connection>` (names, never IDs) | то же |
| Unknown target | Unknown connection | Неизвестное соединение |
| Command | full text, monospace, selectable, scrollable, never truncated | то же |
| Warning (reuse existing tool hint meaning) | Full access within the SSH account. The command and its output go to your model. | Полный доступ в пределах SSH-аккаунта. Команда и её вывод передаются модели. |
| Button 1 | Allow | Разрешить |
| Button 2 | For session | Для сессии |
| Hint under For session | Any command on this connection until you leave Chat. | Любые команды на этом соединении, пока вы не выйдете из чата. |
| Button 3 | Always | Всегда |
| Always explanation (under the button) | Turns off confirmations in the whole app, for all tools and connections. Turn them back on in Settings › LLM assistant › Tools. | Отключает подтверждения во всём приложении, для всех инструментов и соединений. Включить обратно: Настройки › LLM-помощник › Инструменты. |
| Button 4 | Decline | Отклонить |
| Receipt for DECLINED | Declined | Отклонено |
| Receipt for wait timeout | No answer, declined | Нет ответа, отклонено |
| Progress while waiting | Waiting for your answer… | Ждём вашего ответа… |
| Setting label (Tools screen, top) | Ask before changing commands | Спрашивать перед изменяющими командами |
| Setting summary | Asks before a tool runs something that can change a system. Off: no questions anywhere in the app. | Спрашивает, прежде чем инструмент выполнит то, что может изменить систему. Выключено: вопросов нигде в приложении нет. |
| Indicator content description | Confirmations are off | Подтверждения выключены |
| Indicator explanation (snackbar-style short text on tap/long press) | Confirmations are off: changing commands run without asking. | Подтверждения выключены: изменяющие команды выполняются без вопроса. |
| Indicator action | Turn on | Включить |
| Pending save error | Could not save the setting. This call was allowed once. | Не удалось сохранить настройку. Этот вызов разрешён один раз. |

Layout:
- Card sits in the current turn item of the chat list (inside the existing `LazyColumn`), under the progress line; the list auto-scrolls to it (the scroll effect keys include the confirmation id). Stop stays visible below.
- Buttons in a `FlowRow` (wrap at 200% font); the hints sit under their buttons' group, each tied to its button for TalkBack (`contentDescription` = label + hint). Reading order: title, target, command, warning, buttons.
- Decline is not visually dominant over Allow but not hidden; no destructive default focus. Back button while the card is shown behaves as today (leaves Chat, which cancels).
- Indicator: `IconButton` (48 dp), Material core icon `Warning` tinted `error`/muted, in the top-bar Row between the title and the Tools button, shown only when requests are off. Tap or long press (`combinedClickable`) opens the short explanation with a "Turn on" action that sets the switch to ASK (no navigation needed; if the owner wants the Settings screen the Tools entry is next to it). The title gets `maxLines = 1` with ellipsis. If the verification below shows crowding at 200% font in RU portrait, move the indicator to a compact row above the input field (open decision D4).
- The switch row is on the existing Tools screen, above the tool list, reachable from both Settings and Chat; it uses the same autosave binding (pending, inline failure) as the tool switches.
- Test tags: `chat_confirm_card`, `chat_confirm_tool`, `chat_confirm_target`, `chat_confirm_command`, `chat_confirm_allow`, `chat_confirm_session`, `chat_confirm_always`, `chat_confirm_decline`, `chat_confirmations_off`, `tool_confirmations` (switch row).

## 6. States, cancellation, lifecycle, timeouts

- `ChatState.confirmation: PendingConfirmation?` (`id`, tool, target names, command, `sessionHint` flag). Not persisted, not part of `SavedChat`.
- `Confirmations` port (`ask(request): ConfirmationDecision`, suspend). `ChatConfirmations` sets the state under `RunOwnership.guarded`, awaits a `CompletableDeferred`, with a 5-minute `withTimeoutOrNull`; `conversation.answer(id, decision)` completes it; an answer with a stale id, with no pending card, or after close/Stop is ignored (unit-tested). One pending card at a time.
- `AgentModule.run(..., authorize: suspend (ToolCall) -> ToolOutput? = { null })`, called after `onEvent(Calling)` and its `checkOwner`, before `withTimeout(toolTimeoutMs)`. Non-null output is recorded as the call's result and the tool is not executed. `checkOwner()` runs after `authorize` returns, so a Stop that raced the answer wins. The wait is therefore in neither the 30 s tool timeout nor the 60 s model timeout.
- `ToolOutcome.DECLINED` added to agent-core; the OpenAI codec already writes `outcome.name.lowercase()` so the model sees `declined`.
- Send deadline: `withTimeout(120_000)` in `TurnRunner.run` becomes an `ActiveDeadline` (pure, injectable clock): a watchdog cancels the Send with the same `TimeoutCancellationException`-equivalent mapping (`ChatProblem.TIMEOUT`) when active time exceeds 120 s; `authorize` pauses it for the wait and resumes it in `finally`. Active time counts everything else (model, tools, summary) exactly as today.
- Stop / Back / disable / revision change during the wait: the Send's job is cancelled; the awaiting coroutine is cancelled; the card is cleared. Because `RunOwnership.guarded` refuses commits from a stopped owner, the card is cleared in `RunOwnership.completed()` and in `refreshLocked()`/`close()` (the same places that reset other transient fields), not by the cancelled waiter. Slot semantics unchanged: the slot frees only after the run coroutine finished.
- Session grants: an in-memory set in the conversation owner (retained across recreation, dropped by `close()`), cleared when the assistant disable version advances, when the corresponding tool switch is turned off, and on `deleteHistory`. Never written to history.
- Always: write the setting on IO, then (success) return ONCE-like proceed; subscribers (Chat indicator, Tools row) update from `SettingsStore.settings`.
- No SSH client is created before the answer: `authorize` runs before `AppTool.execute` (and therefore before `AssistantSshCommands.execute` -> `connector.newClient()`); the target description is a pure lookup in `SshTargets` state.

## 7. Persistence

- `DECLINED` results are saved like other results (outcome string in `SavedResult`); the receipt screen shows the localized "Declined". Older histories read unchanged.
- A pending confirmation is not saved. The `Replied` event persists the proposed call before `authorize` runs (today's order), so after process death the call appears without a result and the existing "unfinished historical proposal; missing results are UNKNOWN" note applies. This is conservative (nothing ran) and accepted; see risk R2.
- Settings object as in 4.3.

## 8. Accessibility

TalkBack labels on all four buttons with the hint merged; the card has a live-region announcement of the title when it appears; the command is selectable text; all targets >= 48 dp; the indicator has a content description and an `onClickLabel`; no information by color alone; works with 200% font via `FlowRow` and the scrolling list.

## 9. Affected surface contracts and exact promises to change

Read before implementation (existing paths, normative): [`docs/agent-contract.md`](../../agent-contract.md), [`docs/contract.md`](../../contract.md) (UI rules, "Optional LLM assistant", Non-goals), [`docs/ssh-persist-contract.md`](../../ssh-persist-contract.md) (assistant paragraph, check only). No new surface contract is needed.

### 9.1 Promise ledger (`docs/agent-contract.md` unless noted)

| # | Current promise (line) | Disposition | New text intent |
| --- | --- | --- | --- |
| L1 | "No permissions evaluator or confirmation modes are shipped ... until confirmation modes exist; availability does not waive that conditional safety requirement." (Verification and non-goals, ~697-702) | Changed | Confirmation exists for changing tools; the full-access warning stays; a future changing tool must declare `effect.changes` and implement a change description, otherwise the catalog test fails. No evaluator or modes beyond the single app-wide switch |
| L2 | "Tool outcomes are success, failed, timeout or unknown-tool" (Minimal execution, ~99) | Changed | add `declined` |
| L3 | Limits table: "Tool timeout 30 seconds" | Retained + note | The owner's confirmation wait is in neither the tool nor the model timeout |
| L4 | "Every tool checks the captured owner before validating its arguments" (~84-85) | Retained, extended | Authorization of a changing call comes first and checks the owner again after the answer; the tool's own admission still runs |
| L5 | "Each step is one provider request followed by the proposed tools, in order" (~77) | Retained, extended | A changing call may first wait for the owner; calls remain in order |
| L6 | SSH: "Missing, non-SSH, ... fail without starting/reviving a tunnel" (~381) | Retained | Unchanged; the check follows an allowed confirmation |
| L7 | SSH: "Each invocation captures ... one operation-owned ordinary SSHJ client" (~384) | Retained, extended | The client is created only after the call is allowed |
| L8 | SSH: "Timeouts (30 s tool / 120 s Send by default) are cancellation thresholds" (~425) | Changed | The 120 s Send limit counts active time; the confirmation wait is excluded and has its own 5-minute limit |
| L9 | SSH: "Tool-only changes affect the next Send, not admitted work" (~422) | Retained, with an exception | Tool switches still apply next Send; the confirmation setting and session grants are read at each call |
| L10 | SSH: "Commands/output enter the same bounded encrypted Chat history" (~428) | Retained, extended | A declined call is saved as `declined`; a pending question is not saved |
| L11 | Tools: "five independent persisted switches" (~434, 440, 451, 720) | Changed | Five tool switches plus one confirmation switch on the same screen and binding; snapshot holds six booleans |
| L12 | Tools: "Each Send captures the committed tool booleans ... Changes apply only to the next Send" (~470-474) | Retained, with an exception | Same exception as L9 |
| L13 | Tools: "The whole Send deadline is 120 seconds" (~482); Summaries "120-second whole-Send limit" (~265) | Changed | 120 seconds of active time; paused during confirmation |
| L14 | Settings flow: "A malformed new flag falls back to off" (~11) | Retained for the module; new contrast | A malformed confirmation value falls back to ask (on), without resetting unrelated settings |
| L15 | Text chat: "Interrupted attempts retain received results and an explicit unknown-outcome note, never executable pending calls" (~158) | Retained, extended | A pending confirmation is never restored or executed |
| L16 | Text chat: "Stop cancels that operation" (~162) | Retained, extended | Stop during a confirmation cancels it; nothing runs |
| L17 | Prompt: "`skill.md` version 4" (~61, ~282) | Changed | version 5 (section 12) |
| L18 | Verification: shared Tools binding "include all five switches" (~720) | Changed | six switches |
| L19 | Boundary (intro): "The enabled SSH-command exception ..." | Retained, extended | mention that changing calls are confirmed by default |
| L20 | New | Added | Section "Confirmation of changing calls": the decision model, grants, 5-minute wait, switch, indicator, persisted shape, fail-closed read |
| L21 | `docs/contract.md` UI rules: "The one confirm left is 'Forget profile'" (~66) | Changed | Second confirm: the assistant's changing-call question; reason: remote effects cannot be undone, so Undo-instead-of-confirm cannot apply |
| L22 | `docs/contract.md` "Optional LLM assistant" (~780-806) and Non-goals (~954) | Retained, extended | Mention the confirmation question and switch; "no tunnel control" unchanged |
| L23 | `docs/ssh-persist-contract.md` assistant paragraph (~25-36) | Retained; add one clause | The assistant command starts only after the owner's confirmation unless requests are off |
| L24 | Safety invariants of review section 8 (atomic exchanges, no auto retries, unknown outcome, no tunnel control, bounded SSH capture, public policy, disable revokes, slot freed after real exit, untrusted output) | Retained | Checked against the diff; none weakened |

## 10. Interfaces and constraints

- agent-core stays provider/Android neutral; `authorize` is a plain suspend lambda, default `{ null }` (all existing tests unchanged).
- `AppTool` gains an optional `protected open fun describeChange(args: A): ChangeRequest?` and a public wrapper that parses `JsonObject` via `StrictArgs`; returns null on invalid arguments. `ChangeRequest` is not a data class (its `toString` must not print the command).
- Catalog test: every `AssistantToolId` with `effect.changes` must produce a non-null `ChangeRequest` for valid arguments (prevents a changing tool bypassing confirmation).
- `AssistantSshCommands.describe(profile, connection)`: names from the profile index and `Connection.customName`/`defaultConnectionName`; no client, no network, no tunnel access.
- Fixed model texts: decline: `The owner declined this call. Nothing was executed. Do not repeat it or do the same thing another way unless the owner asks.`; wait timeout: `No answer from the owner within 5 minutes. Nothing was executed.` (decline text differs from the older spec by the added clause, matching the prompt rule; see D6.)
- Receipts/projection: `DECLINED` is not a failure: excluded from `toolFailures`/`toolFailed` (`ChatSaved.kt:56`), does not set `ChatToolProgress.FAILED` (`TurnRunner.kt:117`), and is localized in the receipt UI.
- Security: the command text appears only in the owner's card and in history as today; never in logs.

## 11. Test plan

Pure/JVM (`agent-core`, `app`):
- `AgentModuleTest`: authorize null -> executes; non-null -> recorded, tool not called; wait longer than 30 s tool timeout and 60 s model timeout (virtual time) does not time out; authorize cancelled by Stop -> no tool run; owner revoked during wait -> cancellation; order Calling -> authorize -> execute; unknown tool -> no authorize call or proceeds as today.
- `ActiveDeadline`: runs, pauses, resumes, exact expiry at 120 s active, pause during wait does not consume, double pause/resume safe, cancellation of the watchdog on completion.
- `ConfirmationPolicy`/`GrantStore`: resolve (global only; with synthetic overrides to prove the future shape), ONCE asks again, SESSION same key skips / other key asks / cleared on close, disable, tool-off; key never includes the command.
- Settings: default ASK; absent, malformed, unknown version, wrong type -> ASK without resetting other fields; round trip; unknown extra keys ignored; existing field names unchanged (golden JSON); update does not bump `disableVersion`.
- Ownership/race: Stop vs answer, close vs answer, stale id, answer after timeout, answer twice, disable during wait, provider revision change during wait, refresh during wait; the card is cleared on every path; slot free after exit; no commit after owner loss.
- Tool/SSH: fake connector factory count is 0 until the answer and 0 after DENY/timeout/Stop; 1 after ONCE; invalid arguments -> no card, factory 0; target Unavailable still asks; two changing calls in one step ask in order.
- Always: persisted off first; save failure -> allowed once, setting stays ASK, error shown; subsequent calls in the same Send skip.
- DECLINED persistence: saved and restored; excluded from failure lists; exchange atomicity preserved; fixed texts exact.
- Prompt: `AssistantPromptsTest` pinned hash and `SYSTEM_VERSION = 5`; size <= 4096; volatile-content regex still passes.
- Catalog test (section 10).

Virtual time: 5-minute timeout via `runTest` (no real wait); Send 120 s with and without a wait.

Instrumentation (`emulator-5580`, API35; synthetic fake provider/connector; restore font scale 1.0): `AssistantChatScreenTest` additions in portrait, landscape and 200% font (EN and RU): card shown with names and a very long multi-line command (scrollable, selectable), four buttons visible/reachable (wrap), the hints, each button's effect, Stop during wait, Tools round trip keeps the card, recreation keeps it, indicator present only when off, tap and long press show the explanation and "Turn on", indicator does not displace Tools/menu/title, Tools-screen switch autosave and inline failure, TalkBack labels/tags present.

Gates: the full command from `AGENTS.md`, `git diff --check`, `repo-docs` checker; read actual test counts. Evidence is scoped emulator evidence, not physical-device or live-service validation.

## 12. Proposed prompt change (needs operator approval; asset NOT edited)

`app/src/main/assets/assistant/skill.md`, v4 -> v5. Insert one bullet in the "Safety" section, directly after the "An error, timeout or interrupted history ..." bullet. The sentence below is the parent's corrected version (the earlier "The owner approves each changing call" is false once Always turns requests off); the operator approved it word for word on 2026-11-12. The asset is NOT edited in PR 1; PR 2 copies the approved file byte for byte (2591 bytes, SHA-256 ae79a0777ceb609a2b7e748523e5540df17b3f8b6d31eb8f6510c326122b4627; the sentence in that file is authoritative if it differs from the diff below), sets SYSTEM_VERSION 5, pins the hash and updates the agent-contract version references:

```diff
 - An error, timeout or interrupted history does not prove that a remote action had no effect. Never blindly repeat an uncertain action: report it and gather evidence first.
+- A changing call may need the owner's approval before it runs. If the owner declines one, nothing was executed: do not achieve the same thing another way. Report it and ask what the owner wants.
 - Several connections may reach one device. Never change one device twice through different connections.
```

Size and SHA-256 are recomputed when the asset is edited in PR 2 (about +195 bytes, far below the 4096 limit). `AssistantPrompts.SYSTEM_VERSION` 4 -> 5, hash and version pinned in `AssistantPromptsTest`, `agent-contract.md` "version 4" updated. Rationale: the model must know that a changing call may be refused and that a refusal is final for that goal; it adds no volatile content (no tool list, no setting state), so the prefix cache stays byte-stable within a version. It states no mechanism that code does not enforce; safety stays in code. Russian: «Изменяющий вызов может потребовать подтверждения владельца до выполнения. Если владелец отклонил вызов, ничего не выполнено: не добивайся того же другим способом. Сообщи об этом и спроси, что владелец хочет.»

## 13. Acceptance criteria

1. With requests on, `ssh_command` never creates an SSH client before an Allow/For session/Always answer (fake factory counter 0), and never after Decline/timeout/Stop.
2. Decisions: ONCE asks again; SESSION same profile+connection skips, other asks, closing Chat/disable/tool-off asks again; ALWAYS persists "off" (survives restart; the Tools switch shows off; indicator shows); Decline and 5-minute timeout give `DECLINED` with exact texts and nothing runs.
3. Stop, close and disable during the wait cancel, clear the card, run nothing, free the slot.
4. The wait counts in neither the 30 s tool, the 60 s model nor the 120 s Send time (virtual-time tests); active time still times out at 120 s.
5. Settings: default requests on; malformed value -> on; unrelated settings intact; persisted object is versioned; model and `resolve` accept overrides without changing existing JSON or call sites.
6. A declined call is saved and shown as "Declined", never as a failure; a pending question is never saved; process death -> INTERRUPTED.
7. Card shows tool, target names, full command (monospace, selectable, scrollable), warning, four buttons wrapping at 200% font, hints, TalkBack labels, test tags; indicator appears only when requests are off and does not crowd other elements in portrait, landscape and 200% font (EN and RU).
8. Read tools never ask; invalid-argument calls show no card.
9. Prompt v5 approved, hash pinned, size <= 4096; promise ledger L1-L24 applied in the docs; review-document section 8 invariants preserved.
10. Grep checks: no `assistant_always_allowed`; no direct call of `AppTool.execute` path that bypasses `authorize` for changing tools (catalog test); no `withTimeout(120_000)` left in `TurnRunner`.

## 14. Validation to run

Focused: `:agent-core:test`, the new/changed `app` unit tests, `AssistantChatScreenTest` on `emulator-5580`. Full gate and `git diff --check`, `repo-docs` checker, Gitleaks on new commits before pushing. Report counts and failures, not just exit codes. Separate statements for inspected, implemented, tested, merged, published, downloaded-artifact verified.

## 15. Phase B proposal

PR 1 (no user-visible change): agent-core `authorize` hook, `DECLINED`, `ActiveDeadline` (+ `TurnRunner` using it), `ConfirmationPolicy`, `GrantStore`, settings field and sanitizing decode, fixed texts, `AppTool.describeChange` + catalog test, tests. Until PR 2, `TurnRunner` passes no `authorize`.
PR 2 (user-visible): `Confirmations`/`ChatConfirmations`, `ChatState.confirmation`, wiring in `TurnRunner`/`RunOwnership`/`AssistantConversation`, SSH describe, receipts, card UI, Tools switch, indicator, strings EN/RU, prompt v5, docs (ledger), instrumentation tests.
Estimate: PR 1 about 350 production lines and 450 test lines; PR 2 about 700 production lines (about half UI/strings) and 900 test lines; each is reviewable; do not merge PR 1 alone with a release claiming the feature.

## 16. Decisions (closed by the parent, 2026-11-12)

- D1 The switch is on the Tools screen. The "turn back on" text uses the app's exact names: Settings (`settings`), LLM assistant (`assistant_title`; RU "LLM-помощник"), Tools (`assistant_tools`; RU "Инструменты"). No other path is invented.
- D2 Indicator in the top bar between the title and Tools; if it crowds at 200% font in Russian portrait, move it to the input row. Decided from screenshots in phase B (PR 2).
- D3 Ask anyway when the target is unresolved, labelled "Unknown connection" / "Неизвестное соединение".
- D4 The decline text includes "or do the same thing another way".
- D5 If saving "off" fails, allow the call once with an inline error; requests stay on.
- D6 Grants reset on screen life end, assistant disable, tool switch off and history clear.
- D7 The policy is read live at each call.
- Button labels follow R1 exactly: Allow / Разрешить, For session / Для сессии, Always / Всегда, Decline / Отклонить.
- The `docs/contract.md` "one confirm left" change is approved: a remote change cannot be undone.

PR 1 scope (no visible change): authorize hook, DECLINED, ActiveDeadline, ConfirmationPolicy, GrantStore, settings object, fixed texts, `AppTool.describeChange` and catalog test. Nothing asks; no Confirmations port is wired; behavior equals 0.3.65.
