# Assistant action classes and "For session" for the tunnel's open period (stage E1)

Status: draft for operator approval (2026-11-17); no implementation started. Base: `origin/main` = `cfaed07` (0.3.90).

## Goal and approval basis

Stage E1 of the assistant-connections plan (`docs/reviews/assistant-connections-plan.md`, section 9; untracked planning
document, not part of this repository). Operator decisions it rests on: D1 ("For session" lives while the tunnel is
open, replacing the chat-screen lifetime), D2 (the question is asked by action class, not by tool; the answers
stay), D4 (minimum settings), R27 (plan questions B1-B16 accepted as recommended except B10; B14b weakening approved), R63
(E1 and E3 are done; E2, the three group switches, is NOT: per-tool switches, default off, stay).

User-visible result:

1. Every catalog tool has an action class. A changing tool's confirmation card says "class · tool".
2. "For session" is recorded as (class, connection) and lives for the open period of that connection's tunnel. It
   survives leaving the chat (and closing the conversation), and ends when the tunnel closes, a connection edit or
   re-enrollment changes the fingerprint, the assistant is turned off, or the tool's switch goes off.
3. The connection card shows "On for the tunnel's lifetime · Forget" while such a grant exists.

Scope is the plan's E1 minus the `assistant_confirmations.tools` snapshot (see Open questions, Q2). Nothing is persisted.

## Non-goals

- No group switches (E2), no persistent "For this connection" grant, no `overrides` use, no stored fingerprint (E3).
- No new tools, no `RAW_TCP` class (no tool uses it yet; it arrives with `tcp_exchange`).
- No change to Allow once, Always, Decline, Stop, the 5-minute wait, event waits, card layout beyond the lines below, or
  any model-facing text (`ConfirmationTexts`, tool results, `skill.md`).
- No new persisted data, no new setting, no settings-file migration, no history format change.
- "Ask before changing commands" keeps its scope: every tool that asks today.

## Affected surfaces (legacy paths, normative until updated at completion)

- `docs/agent-contract.md`: Confirmation of changing calls (`:797-870`), Tool switches, Typing (`:1296-1312`), Chat lifetime (`:455-466`),
  Tool set captured at Send (`:705-715`), tool table rows (`:933-960`), stop/close/skill subsections (`:1197-1200`, `:1264`, `:1287`).
- `docs/contract.md:1029-1030,1046`, `docs/ssh-persist-contract.md:30` (the same "until Chat is left" promise).
- `docs/contracts/surfaces/operations.md:15-19,123-125` (effect pin; the class gets the same pin).
- `docs/contract.md` shared UI rules for the connection card (the added line). Meta-contract:
  `docs/contracts/meta-contract.md`; process: `AGENTS.md`.

## Current tool catalog mapped to classes

Source: `AssistantToolId` (19 entries) plus the service tool `request_tool` (not in the catalog; no class, never asks,
grants nothing; unchanged). "Asks" = catalog `effect.changes && confirms` and `asksAbout` (today's `changeCheck`).
"Target" = what a `GrantKey` is scoped to; "grantable" = a session grant may be recorded and honored (today: only if
`ChangeRequest.target != null` and the card offers For session, `AssistantConfirmationCard.kt` `canSession`).

Classes: the plan's `PUBLIC_READ`, `CONNECTION_READ`, `CONNECTION_CHANGE`, plus two proposed here because tools added after
the plan (2026-11-13) have no home in it: `APP_READ` and `APP_CHANGE` (Open question Q1).

| Tool | Class | Effect | Asks today | Grantable | Target of a grant | Switch (unchanged) |
| --- | --- | --- | --- | --- | --- | --- |
| `web_search` | PUBLIC_READ | READ_REMOTE | no | n/a | none | Web search |
| `read_page` | PUBLIC_READ | READ_REMOTE | no | n/a | none | Read page |
| `inspect_image` | PUBLIC_READ | READ_REMOTE | no | n/a | none | Inspect image |
| `profile_information` | CONNECTION_READ | READ_REMOTE | no | n/a | none | Profile information |
| `terminal_tabs` | CONNECTION_READ | READ_REMOTE | no | n/a | none | Terminal tabs |
| `terminal_read` | CONNECTION_READ | READ_REMOTE | no | n/a | none | Terminal tabs (shared) |
| `ssh_command` | CONNECTION_CHANGE | CHANGE_REMOTE | yes | yes | connection (profile, connection id) | SSH command |
| `terminal_input` | CONNECTION_CHANGE | CHANGE_REMOTE | yes | yes | the tab's saved connection (none if no connection matches: no For session) | Typing into tabs (needs Terminal tabs) |
| `terminal_open` | CONNECTION_CHANGE | CHANGE_REMOTE | no (`confirms=false`) | n/a | none | Assistant sessions |
| `terminal_close` | CONNECTION_CHANGE | CHANGE_REMOTE | yes | **no** (today: none) | session (tab id of the assistant's session) | Assistant sessions |
| `read_skill` | APP_READ | READ_LOCAL | no | n/a | none | none (on) |
| `skills_list` | APP_READ | READ_LOCAL | no | n/a | none | Skills |
| `skill_save` | APP_CHANGE | CHANGE_LOCAL | yes | **no** (today: none) | none (skill name is shown, not a grant scope) | Skills |
| `skill_delete` | APP_CHANGE | CHANGE_LOCAL | yes | **no** | none | Skills |
| `start_tunnel` | APP_CHANGE | CHANGE_LOCAL | no (`confirms=false`) | n/a | connection | Tunnels |
| `stop_tunnel` | APP_CHANGE | CHANGE_LOCAL | yes (only if the tunnel is on) | **no** (`agent-contract.md:1200`) | connection (shown on the card only) | Tunnels (shared) |
| `terminal_rename` | APP_CHANGE | CHANGE_LOCAL | no (`confirms=false`) | n/a | tab | Terminal tabs (shared) |
| `terminal_show` | APP_CHANGE | CHANGE_LOCAL | no (`confirms=false`, it only asks the owner) | n/a | session | Assistant sessions |
| `wait_for` | APP_CHANGE | CHANGE_LOCAL | no (`confirms=false`; the owner cancels in the chat) | n/a | none | Event waits |

Counts: PUBLIC_READ 3, CONNECTION_READ 3, CONNECTION_CHANGE 4, APP_READ 2, APP_CHANGE 7 = 19. Asking tools: 6
(`ssh_command`, `terminal_input`, `terminal_close`, `skill_save`, `skill_delete`, `stop_tunnel`); grantable: 2
(`ssh_command`, `terminal_input`). There is no `cancel` tool: a wait is cancelled by the owner's button.

Rules that make the class table safe:

- Class never decides whether a call asks by itself. The question is still `effect.changes && confirms && asksAbout` AND the
  global switch is ask AND no valid grant. `confirms=false` tools never ask, whatever their class. A class without a
  question (reads) never asks. A catalog entry cannot be built without a class; a call whose class is unknown asks (fail closed).
- `grantable` is a property of the tool, kept from today. Sharing a class never extends a grant to a non-grantable tool:
  a CONNECTION_CHANGE grant never covers `terminal_close`, and APP_CHANGE grants do not exist.
- Operations (`profile_query`, `ssh_exec`, `tunnel_up`, `tunnel_down`) get the same `actionClass`; a tool cannot be built
  when its operation's class differs from the catalog's (as the effect pin today).

## Specification

### ActionClass and GrantKey

- `enum ActionClass(wire, grantable-by-class)` in `ops/`: `PUBLIC_READ`, `CONNECTION_READ`, `CONNECTION_CHANGE`, `APP_READ`,
  `APP_CHANGE`. `AssistantToolId` gets `actionClass` and `grantable` (true only for `SSH_COMMAND`, `TERMINAL_INPUT`). Wire names are
  not persisted in E1.
- `GrantKey(actionClass, target: GrantTarget)`; the target is never null for a recorded grant (a call with no target
  has no For session, as today). `GrantTarget(profileId, connectionId)` is unchanged.
- `SessionGrants` replaces `GrantStore`; it is owned by the app (`FrpJumpApp`), not by a conversation or `ConfirmationSupport`
  instance. Value: `(period, fingerprint, tools, disableVersion)`; in memory only, never in history, logs or receipts.
  - `period`: an id of the tunnel's open period. `TunnelManager` gets an open-period id per tunnel key that is
    assigned from one app-wide monotonic counter when the tunnel goes from "not planned" to "planned" (a Connect), and is
    never reused in the process. It ends when the planned entry disappears, a Disconnect is pending, or the tunnel key is in
    `PendingUndo.hidden` (even if the owner's Undo then cancels the disconnect; B14c). Transient changes (frpc reconnect,
    via-server/direct switch, local-port rebind, `grantId` or `localPort` change on restore) do not end it. Implementation must
    prove this both ways with tests; if `grantId` alone cannot tell them apart it must not be used. If a case cannot be told
    apart, the period ends (fail closed).
  - `fingerprint`: equal while nothing the owner can edit changed: all `Connection` fields (`id, device, port, kind, customName,
    https, user, localPort`), the profile's `id`, `keyAlias` and `activationPending`, the profile's enrollment, and the
    non-secret SSH login (user and method kept in `SshLoginMemory` for device and port) and the pinned host key, when readable
    without a Keystore unlock (B15; if one is not readable the field is left out and the gap is named in the report). The profile `name`
    and `color` are NOT part of it (B14b, approved weakening). Null (gone) means the grant is dropped. No secret is read.
  - `tools`: the grantable tools of the class that were in effect (`AssistantTools[id]`) when the owner answered. A
    call is covered only if its tool is in `tools` AND still in effect now.
- Both `period` and `fingerprint` are captured BEFORE the question is shown (as today's `stamp`), so an edit or a close
  during the wait makes the grant born stale. SESSION is recorded only if the tool is still in effect and the period
  exists; otherwise it acts as Allow once (no record, no error line).
- `mustAsk(tool, key, policy, grants, ...)`: asks iff tool changes and confirms and policy is ask and not
  `grants.covers(tool, key)`. A repeated SESSION answer replaces the entry (current `tools`, same period/fingerprint).
- Lookups are lazy at each call (as today) AND an app-level cleaner keeps the list honest (below).

### When a grant ends

| Event | Effect | Mechanism |
| --- | --- | --- |
| Tunnel disconnect (owner, assistant's `stop_tunnel`, error that removes the plan), Undo-hidden, reconnect as new Connect | grants of every connection on that tunnel key end | lazy period check + cleaner on `TunnelManager.state`, `PendingUndo.hidden` |
| Connection edited (any fingerprint field), connection or profile deleted, "Forget profile", re-enrollment | that connection's (profile's) grants end | lazy fingerprint check + cleaner on profiles state |
| Rename or colour of the profile | nothing | not in the fingerprint (B14b) |
| Assistant turned off (or off/on), module disabled | all grants end | lazy `disableVersion` check + cleaner on `assistantDisables` and availability, independent of any conversation |
| A tool's switch goes off, or it falls out of effect (Typing goes off with Terminal tabs) | that tool leaves `tools` of every grant of its class; an empty `tools` drops the grant; turning the switch on later does NOT bring it back | cleaner on settings; lazy `AssistantTools[id]` check |
| Chat history cleared | all grants end (kept from today, B14a) | existing clear path |
| Leaving the chat, closing the conversation, Back, another section, the app lock | nothing (D1) | `closeLocked` no longer clears; grants are not owned by the conversation |
| Process death | all end | memory only |
| Allow once, Decline, no answer, Stop | nothing is recorded | unchanged |

The cleaner runs in the app scope and must not depend on a conversation existing (the old `toolsChanged` and `assistantDisables`
collectors live in the chat owner's scope and cover only an existing conversation). It publishes `observe(): Flow<Set<GrantTarget>>`
of live grants, so the UI never shows a stale line.

### Card

- The tool line (`chat_confirm_tool`) becomes "class · tool" for every asking card (6 tools): the class name, a middle dot, the
  tool title the card uses today. Only classes of asking tools need names in E1: CONNECTION_CHANGE, APP_CHANGE.
- The For session hint says what the answer covers: the grantable tools of the class in effect now, for this connection, while its tunnel is open.
  Cards without For session (stop, close, skills, an unknown connection) are unchanged and show no hint.
- Answer order, labels and the Always second confirmation are unchanged.

### Connection card line

On the connection's card (`ConnectionCard`), under the address, shown only while `SessionGrants.observe()` contains that
connection: a muted line "Assistant: no questions, for the tunnel's lifetime" with a trailing text button "Forget". Forget removes every session
grant of that connection at once, with no confirmation (it narrows authority) and cannot fail. The row wraps at 200% font, the button keeps a 48 dp
target, and TalkBack reads "Forget the assistant's permissions for <connection name>". It disappears the moment the grant ends. No sheet,
no list in Tools, no receipt wording (E3).

## Old -> new -> basis (security, assistant permissions, stored data)

| # | Old promise (`agent-contract.md` unless noted) | New | Basis |
| --- | --- | --- | --- |
| 1 | For session covers "the tool and the exact target" (`:17,:799-800,:830`) | covers the class on the connection: `ssh_command` and `terminal_input` share one grant, each only if in effect at the answer and now; card hint lists them | D2, R27 (plan 2.1/6.2) |
| 2 | Lifetime: until the chat screen is left and no attempt runs (`:74,:460,:832`; `contract.md:1030,1046`; `ssh-persist-contract.md:30`) | the open period of the connection's tunnel; survives leaving the chat and closing the conversation; hours or days | D1 |
| 3 | Grant dropped when the assistant is disabled (`:833`, `RunOwnership`) | retained, plus off/on and independent of a conversation | retained |
| 4 | Grant dropped when the tool is switched off (`:833`; `:1306` typing with tabs) | retained per tool: the tool leaves the grant's `tools`; re-enabling does not revive it | retained (reconciled with per-tool switches, R63) |
| 5 | Grant dropped when history is cleared (`:833`) | retained | B14a (R27) |
| 6 | Grant dropped when the target or its profile is edited or removed; stamped with the saved definition (`:833-836`) | fingerprint: every connection field, profile id/key alias/activation, enrollment; plus SSH login method and host key (new). Profile rename and colour no longer drop | R27 B14b (explicit), B15 |
| 7 | A tunnel disconnect that the owner Undoes drops nothing (grant lives with the screen) | the tunnel is closed for grants as soon as it is hidden, even if Undo cancels | R27 B14c (stricter) |
| 8 | The app lock ends the conversation, hence the grants, when no attempt runs (`:460`) | the lock does not end grants; a covered call may run while locked, an asking call waits behind the lock as today | D1; R27 B10; confirm in Q4 |
| 9 | "In memory only" (`:836`) | retained; never persisted, not in history, backup or logs; lives until the process dies | retained |
| 10 | No session grant for `stop_tunnel` (`:1200`), `terminal_close` (`:949,:1264`), `skill_save`/`skill_delete` (`:1287`) | retained; `grantable=false` per tool survives sharing a class | retained |
| 11 | Always turns the app-wide switch off after a second confirmation; it covers every asking tool (`:837-840`) | unchanged: same switch, same file `{"version":1,"global":...}`, same scope | retained (Q2 is about the future) |
| 12 | Fail closed: undescribed changing call or non-`AppTool` is refused, no support means refuse, policy and grants read at each call (`:711,:862-868`) | retained; adds: unknown class asks; a tool without class cannot be built | retained |
| 13 | Effect pinned equal between tool and operation (`operations.md:123-125`) | extended: class pinned equal too | moved |
| 14 | A grant is not recorded for a tool switched off during the wait; a SESSION racing Stop may record but the call never runs (`:853-854`) | retained; also not recorded if the period ended or the fingerprint changed during the wait | retained / stricter |
| 15 | Allow once, Decline, "No answer" after 5 minutes of real time, Stop, disable, leaving does not cancel a waiting card (`:832-852`) | unchanged | retained |
| 16 | Reads never ask; `confirms=false` tools never ask (`:805`) | unchanged | retained |
| 17 | Stored data: `assistant_confirmations` is `{"version":1,"global"}`; no new keys (`:869-870`) | unchanged; no new file, key or version | retained |

## Open questions (not decided here)

- **Q1. Classes the plan does not have.** The plan defines four classes. The 8 tools added later (skills, assistant sessions,
  tunnels, rename, waits, `read_skill`) need a class for the catalog test. Proposed: `APP_READ` (2) and `APP_CHANGE` (7), with no
  new question, no grants, no switch (`confirms`/`grantable` stay per tool). `terminal_open/close` go to CONNECTION_CHANGE (they
  act on a device). Alternative: a nullable class for these. Operator to confirm names and placement.
- **Q2. `tools` snapshot on `global=off` (plan E1 and B13).** The plan writes `tools` next to `global=off` and reads an old
  `off` as `["ssh_command"]`. Today `off` already silently covers `terminal_input`, `stop_tunnel`, `terminal_close`, `skill_save`,
  `skill_delete`. Reading it as `["ssh_command"]` would re-ask the owners who chose Always for all of those: a behavior
  change (stricter) not approved for E1. This contract leaves `assistant_confirmations` untouched. Decide: (a) defer to E3/next tool,
  (b) snapshot the current asking set as the baseline at E1 (no re-ask now, resets when a new asking tool arrives).
- **Q3. `terminal_open`/`start_tunnel` and friends are class members without a question.** The plan's test "`asks == effect.changes`
  per catalog row" cannot hold for the 5 `confirms=false` tools. This contract tests `asks == effect.changes && confirms`. Confirm.
- **Q4. App lock and grants (row 8).** Today the lock indirectly ends the session. D1 and B10 imply grants stay and a background
  wait-triggered turn may run covered changing calls while locked. Confirm that this is the intended reading.
- **Q5. Hint wording when only one grantable tool is in effect** and when Typing is turned on after the answer (the card asks again;
  the new answer then includes it). Confirm the three hint texts below.

## Texts (RU / EN), old -> new

| Where | Old | New |
| --- | --- | --- |
| `chat_confirm_tool` | tool title, e.g. "SSH command" | `%1$s · %2$s`, e.g. "Changes in connections · SSH command" / «Изменения в подключениях · Команда SSH» (use the exact current tool title) |
| class names | none | CONNECTION_CHANGE "Changes in connections" / «Изменения в подключениях»; APP_CHANGE "Changes in the app" / «Изменения в приложении» |
| `confirm_session_hint` | "Any command on this tunnel until you leave Chat and the assistant finishes." / «Любые команды в этом туннеле, пока вы не выйдете из чата и помощник не закончит.» | "Commands in this connection while its tunnel is open." / «Команды в этом подключении, пока открыт его туннель.» |
| `confirm_input_session_hint` | "Any typing into tabs of this tunnel until you leave Chat and the assistant finishes." / «Любой ввод во вкладки этого туннеля, пока вы не выйдете из чата и помощник не закончит.» | "Typing into tabs of this connection while its tunnel is open." / «Ввод во вкладки этого подключения, пока открыт его туннель.» |
| new `confirm_session_hint_both` | none | "Commands and typing into tabs of this connection while its tunnel is open." / «Команды и ввод во вкладки этого подключения, пока открыт его туннель.» |
| new connection-card line | none | "Assistant: no questions, for the tunnel's lifetime" / «Помощник: без вопросов, на время туннеля» + action "Forget" / «Забыть» |
| new TalkBack label | none | "Forget the assistant's permissions for %1$s" / «Забыть разрешения помощника для %1$s» |

"For session" / «Для сессии» and all other answer labels are unchanged. The hint shown is `_both` when both grantable tools are in
effect, else the one in effect. Model-facing texts are unchanged.

## Acceptance criteria

1. Every `AssistantToolId` has a class; the mapping above holds (test lists all 19 and the 6 asking, 2 grantable tools); a
   changed `confirms`, `effect`, `grantable` or class for any row fails a test. Each operation's class equals its tool's.
2. Asking decisions are identical to today for every tool when no grant exists, and for global off (matrix: 19 tools x global
   ask/off x grant none/valid).
3. A For session answer on `ssh_command` makes a later `ssh_command` and (if in effect) `terminal_input` on the same connection skip the
   question; neither covers another connection, `terminal_close`, `stop_tunnel` or skills; with Typing off at the answer, a later
   `terminal_input` asks.
4. The grant survives: leaving and re-entering the chat, closing the conversation, the app lock, a transient tunnel reconnect, a profile rename or
   colour change. It ends on: Disconnect then Connect, hide behind Undo (also when Undo cancels), connection edit (one test per fingerprint field),
   delete, Forget profile, re-enrollment, assistant off and off/on, the tool's switch off (and does not return when it is switched back on),
   history clear, SSH login method change.
5. The grant is not recorded when the tool is switched off, the period ends or the fingerprint changes while the card waits; the call still obeys
   the answer (Allow once semantics).
6. The cleaner works with no conversation alive; the connection card line appears and disappears with the grant, and Forget clears
   it at once.
7. `assistant_confirmations` and every other setting are byte-identical before and after (no write). Old settings files read unchanged.
8. Allow once, Always (second confirmation, `persistOff`), Decline, 5-minute no-answer, Stop and waits behave as before (existing tests unchanged).

## Validation

Unit (JVM, `:app:testDebugUnitTest`):
- `ConfirmationGateTest`: class-keyed key; `terminal_input`/`ssh_command` sharing; non-grantable tools never record or honor a grant; tool
  not in `tools`; unknown class asks; SESSION recorded with pre-wait period/fingerprint; no record for a switched-off tool or an ended
  period; ALWAYS/ONCE/DENY/NO_ANSWER unchanged.
- `SessionGrantsTest`: `covers`, replacement, per-tool removal and empty-set drop, `forgetConnection`, `observe()`, thread safety.
- `SessionGrantCleanerTest` with a fake `TunnelManager` and fake `PendingUndo`/profiles/settings flows: each row of "When a grant ends",
  including a conversation that does not exist, a transient reconnect (period kept), the app-wide counter never reusing a period for a recreated runtime.
- Catalog test: the 19-row table, `asks == effect.changes && confirms`, operation class pin; `AssistantConversation`'s close no longer clears grants.
- Fingerprint test: one assertion per field of `Connection`, per profile field, enrollment, login method; rename/colour equal.

Compose (androidTest, `emulator-5580`, RU 1.0 portrait; EN 200% landscape for the card): the card "class · tool", the three hints and no
hint on stop/close/skill cards; the connection card line with and without a grant, wrap at 200%, Forget removes the line. Screenshots for the
changed screens before release (`ltest.sh`). Scoped to the changed classes, not the full suite (assistant chat classes and the connections card).

Mutation checks (each must turn at least one named test red; run on a scratch branch, one mutation at a time, revert):
M1 `covers` ignores `period`; M2 ignores `fingerprint`; M3 ignores `tools` membership; M4 ignores the tool's current effect; M5 `grantable`
true for `terminal_close`/`stop_tunnel`/`skill_save`; M6 SESSION recorded with a post-wait period or stamp; M7 `closeLocked` clears again or
the cleaner ties to the conversation; M8 cleaner ignores `PendingUndo.hidden`; M9 disable/off-on does not clear; M10 an unknown class does not
ask / `confirms=false` tool asks; M11 history clear keeps grants; M12 rename changes the fingerprint (or a connection field is skipped).

Gates: `AGENTS.md` full gate (unit, `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`, `git diff --check`), the repo-docs checker,
Gitleaks, an independent review (security/permissions change). Release notes: say that For session now lasts with the tunnel.

## Completion

After verification: update the surface contracts listed above, mark this file complete, delete it (meta-contract, lifecycle 5).
