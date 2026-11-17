# Assistant "For this connection": a persistent per-connection permission (stage E3)

Status: draft for operator approval (2026-11-18); no implementation started. Base: `origin/main` = `05aecbc` (0.3.90 plus E1, #293).

## Goal and approval basis

Stage E3 of the assistant-connections plan (`docs/reviews/assistant-connections-plan.md`, sections 3.1, 3.2, 3.4, 3.5 and 9; an untracked
planning document, not part of this repository). Operator decisions it rests on: D6 (a permission may be remembered for the whole connection:
"class + connection", saved, survives closing the tunnel and a restart), D7 (any edit of the connection resets it; enabling or disabling is
not an edit; deleting the connection removes it), D8 (five answers: Allow, For session, For this connection, Always, Decline), D9 (the card
layout is shown before implementation), D10 (re-enrolling a profile resets it; a rename or another harmless profile edit does not), R27
(B1-B16 accepted as recommended except B10; B14b approved) and R63 (E1 and E3 are done; E2, the group switches, is NOT: the per-tool switches,
default off, stay). E1 is merged: `ActionClass`, `GrantKey(class, connection)`, `SessionGrants` for the tunnel's open period, a fingerprint
with the SSH login and host key, the app lock ending grants, and the connection-card line with Forget.

User-visible result:

1. A confirmation card for `ssh_command` or `terminal_input` on a saved connection has a fifth answer, "For this connection": later calls of
   the tools the card named, on that connection, run without a question until the connection is edited, the profile is re-enrolled, the
   connection is deleted or the owner forgets the permission. It survives closing the tunnel and restarting the app.
2. The connection's card shows one muted line "Assistant: no questions, remembered <date>" with Forget (the E1 line, same place); Forget clears
   the session and the persistent permission of that connection together.
3. A chat receipt of a call that ran without a question says why.

## Non-goals

- No group switches (E2), no `RAW_TCP` class, no `tcp_exchange`, no new tool, no new class: only `CONNECTION_CHANGE` can be remembered.
- No sheet "Assistant permissions", no "⋮" item, no list in Tools (see Open question Q4): one place, the connection card.
- No change to Allow once, Decline, Always (its second confirmation and the app-wide `global` switch), Stop, the 5-minute wait, event waits,
  `ConfirmationTexts`, tool results or `skill.md`: the model is never told about grants.
- No change to "For session" (E1): its lifetime, fingerprint, lock rule and in-memory storage stay as they are.
- `assistant_confirmations.global` and its tool set are not touched (Open question Q2).

## Affected surfaces (legacy paths, normative until updated at completion)

- `docs/agent-contract.md`: confirmation of changing calls (`:797-870`: answers `:834`, ends `:842-857`, memory only `:861-862`, storage
  `:895-899`), grantable tools (`:968`, `:1298`), confirmation tests (`:1628`).
- `docs/contract.md`: the connection card line (`:283-289`), connection delete and profile forget (`:1015-1017`), undo rule (`:70`), assistant
  settings (`:1036-1037`), stored data and backup (`:1125-1145`).
- `docs/ssh-persist-contract.md:30` (the For session promise is quoted there), `docs/contracts/surfaces/operations.md:22` (class and
  grantable pin). Process: `docs/contracts/meta-contract.md`, `AGENTS.md`.

## Specification

### The answer and what it covers

- `ConfirmationDecision.CONNECTION` and `CardTap.CONNECTION`. The card offers it under exactly the condition that offers For session today
  (`canSession`: the target connection is known by name, `tool.grantable`, no stop, close or skill card). So only `ssh_command` and
  `terminal_input` (the grantable tools of `CONNECTION_CHANGE`) can get it; never `terminal_close`, `stop_tunnel`, `skill_save`, `skill_delete`,
  never a call with no saved connection. An answer on a card that did not offer it acts as Allow once and records nothing.
- Scope: (class, profile id, connection id, fingerprint, tools). Never a tool, a command, a tab, a tunnel or a device alone.
- Coverage: `tools` = the grantable tools of the class that the card said it covers (`coverableBy` when the card was raised) AND that are in
  effect and not switched off since the stamp at the moment of recording. A call is covered only if its tool is in `tools`, is grantable, its
  class equals the record's, and the tool is in effect now. A tool that joins the class later is not in `tools` and asks (the record stays).
- The grant is bound BEFORE the question (fingerprint, switch-off counts, assistant off count): an edit, a switch-off or an assistant off during
  the wait makes the answer act as Allow once, no record, no line. Recording also needs the tunnel's open period still to be the stamp's and the
  app not locked (parity with E1: a call whose target went away cannot run anyway).
- The record is written through the settings store. If the write fails, the call is allowed once and the existing note "Could not save the
  setting. This call was allowed once." shows (as for Always); nothing is recorded in memory instead. A CONNECTION answer racing Stop may
  record (the owner's choice stands, as Always); the call itself never runs. Recording replaces any record of the same (class, connection) and
  drops the connection's session entry, so one line speaks for the wider permission.
- Order at a call: the tool does not ask, or the global switch is off, or a session grant covers it, or a persistent grant covers it; else ask.
  The policy and both grants are read live at every call. Persistent records are NEVER read through `ConfirmationPolicy.resolve(scopes)`
  (it ignores class, fingerprint and tools): `resolve` returns `global` only, and the old scope-to-mode override waiver is removed.

### Fingerprint (one stable value per connection, hash, persisted)

`fp = "v1:" + base64url(SHA-256(UTF-8 of a JSON object with the keys below in this order, null written, no whitespace))`:
`connection` (`id, device, port, kind, custom_name, https, user, local_port`), `profile` (`id`, `key_alias`, `activation_pending`),
`enrollment` (SHA-256 of `control_url`, `device_id`, `cert_pem` joined by newlines), `ssh_login` (`user`, `method` kept for the device and port;
null when none), `host_key` (`type`, `fingerprint` pinned for the device and port; null when none), `mqtt_user` (the user kept for an MQTT
connection, null otherwise). Not in it: profile name and colour (B14b, D10), the connection's enabled state (a tunnel state, not a field),
every secret (token, private key, passwords, the MQTT password).

- The session fingerprint of E1 is untouched (it holds the in-memory enrollment counter, which a restart resets and which cannot be stored).
  A parity test runs the same one-field-at-a-time edits against both, so they cannot drift.
- `enrollment` is read from the PUBLIC fields of `enrollment.json` only: no sealed field is opened, no Keystore unlock is needed, nothing is
  wiped on a read failure (`CredentialStore.state()` wipes on permanent loss; the new reader never does). `mqtt_user` needs a user-only reader
  of `MqttLogins` (`get()` unseals the password and must not be used). No grantable tool targets an MQTT connection today, so `mqtt_user`
  is a unit-tested guard for the future (plan B15), not a reachable path now.
- The check is three-valued: Match, Mismatch or Gone (the profile or connection is absent from a LOADED profile state), and Unknown (profiles
  not loaded yet, a credential file or a login or host-key store unreadable). Match covers. Mismatch and Gone delete the record. Unknown asks and
  KEEPS the record: a transient read failure or a cold start must not erase a long-term decision. A profile index that failed to load is not
  "loaded", so it is never "every profile is gone".
- A record is deleted for Mismatch or Gone as soon as a change is seen (a cleaner on the same flows as the E1 cleaner, in the app scope, needing
  no conversation) and is also checked at every use. A failed delete write is retried at the next change; the lazy check keeps it unused.

### When a persistent grant ends, and when it does not

| Event | Effect | Mechanism |
| --- | --- | --- |
| Any `Connection` field edit: device, port, kind, custom name, HTTPS, user, pinned local port (one test per field) | ends | fingerprint Mismatch |
| SSH login method or user change, a pinned host key changed or forgotten, MQTT user change | ends | fingerprint |
| Profile key alias, activation, or a new enrollment (cert, control URL, device id) | ends | fingerprint, and explicit removal on every re-enrollment path |
| Re-enrolling a profile (enroll in place, "Forget profile" with `keepData`, the server removing the profile) | ends | `forgetProfile` on `Profiles` paths `:216`, `:274`, `:284` plus fingerprint |
| Delete of the connection | ends when the delete COMMITS (Undo expired or the app locked or closed); Undo cancelled: it lives on | `commitDelete` removes the record first; until then the record does not act (hidden for Undo) |
| "Forget profile", the last profile's wipe, the server removing this phone's profile | ends (all of that profile's records) | `FrpJumpApp.forgetProfile` lambda; dangling sweep |
| Forget on the connection card | ends at once, no confirmation, cannot fail (narrows authority) | `forget(profile, connection)` also clears the session entry |
| The assistant is turned off (off and on again included) | all records end | removed in the same settings write that turns it off |
| A tool's switch goes off, or it falls out of effect (Typing goes off with Terminal tabs) | that tool leaves every record for good; an emptied record is removed; switching it on again brings nothing back | narrowed in the same write as the switch, derived from before/after availability; at load a recorded tool that is not in effect is dropped |
| The global "Ask before changing commands" switch off or on | nothing: no record is made or ended; while it is off nothing asks and records are not consulted | policy is read live |
| Always | nothing here (`global` is a separate value) | unchanged |
| Profile rename or colour | nothing | not in the fingerprint |
| Tunnel disconnect, Undo-hide of the tunnel, reconnect, leaving the chat, closing the conversation, history clear, process death, restart | nothing (a persistent grant is the owner's long-term choice, not tied to a tunnel or a chat) | no tie to `period` or the conversation |
| Allow once, Decline, no answer, Stop | nothing recorded | unchanged |
| A record that is malformed, has no class, fingerprint or tools, names an unknown class or a class that has no question | read as absent (asks) and dropped at the next write | strict reader, below |

### The app lock

E1 ends every session grant when the app locks or would lock the moment the owner returns, and no grant is used meanwhile
(`agent-contract.md:854-856`). E3 keeps that for session grants and adds for the persistent one: while `GrantWorld.locked()` holds (the
interval has run out, read at every use), NO turn, wait- or event-triggered ones included, uses ANY grant; a persistent record is not deleted
by the lock (it is an explicit long-term choice and would otherwise die at every cold start, when the lock engages), it simply asks, and the card
waits behind the lock with the 5-minute clock running. With the app lock off nothing changes. Recording is refused while locked. This is read
as E1 reads it and conflicts with B10 of R27 (see Open question Q1).

### Storage

- Place: the existing `assistant_confirmations` object of `settings.json` (`filesDir`, app-private, `AtomicTextFile`), field `overrides`
  (reserved by R11; `ConfirmationOverride` is redefined as the record below). `version` stays 1: a record only WEAKENS a question, so an
  older build that reads it asks (E1's `normalizeConfirmations` rebuilds the object from `global` alone). No file, key or version is added.
  With no record the file is byte-identical to today's (the empty array is omitted).
- Record: `{"scope":{"kind":"target","id":"<profile uuid>","sub":"<connection uuid>"},"class":"connection_change","mode":"off","fp":"v1:...",
  "tools":["ssh_command","terminal_input"],"at":1763020800000}`. Only UUIDs, a hash, class and tool wire names and a time: no name, address,
  user, command or secret. `at` (epoch millis) is shown as a date on the card line.
- Strict reader (fail closed, each field on its own, an invalid record never resets `global` or another setting): a record is kept only if
  `scope.kind == "target"`, both ids are non-empty strings, `class` is a known class with a question and `grantable`, `mode == "off"`, `fp` is
  a string starting `v1:`, `tools` is a non-empty array of known tool wire names that are grantable and of that class (unknown names are dropped,
  empty after that: the record is ignored), `at` is a number. Duplicates by (class, profile, connection): the last wins. At most 500 records
  (the first 500 are read, the rest ignored). A broken `overrides` array reads as empty. A non-1 `version`, as today, reads as ask with no
  records. With the assistant off in the file, no record is read.
- Backup and transfer: `settings.json` is in the `file` domain, which `res/xml/data_extraction_rules.xml` excludes in both `cloud-backup` and
  `device-transfer` (with `allowBackup="false"`); `BackupExclusionGuardTest` gains a case that the settings file lives in that domain. A
  grant cannot move to another phone and is never in history, receipts, logs or exports.
- Compatibility: after a rollback to a build that does not know records, its first settings write drops them (`WireJson` ignores unknown keys):
  the grants must be given again. Release notes say so.

### Interface

- `ConnectionGrants` (over `SettingsStore` and a `GrantWorld`): `covers(tool, key)`, `record(key, tool, stamp, offered)` (suspend, IO),
  `forget(profile, connection)`, `forgetProfile(profile)`, `observe(): StateFlow<Map<GrantTarget, Long>>` (target to `at`), `sweep()`.
  The in-memory state is not a second source: it reads the committed `assistantConfirmations` (live, as the policy is).
- `GrantWorld` gains `persistentFingerprint(target)`, the three-valued result above, and `hidden(target)` (the connection is hidden for Undo).
- `SettingsStore.update` narrows the records inside its synchronized block and the same file write that changes the assistant switch or a
  tool's effect, so no other path can observe a switched-off tool still granted.
- The gate returns, besides null to run, the reason the call was not asked (none asked: `OFF`, `SESSION`, `CONNECTION`); the run loop keeps
  it in memory on that call's receipt only. It is never in the saved history, logs or the model's input.

### Card: five answers (mockup (a) of the plan, 2 + 3)

- A card with For session available (grantable tool, known connection): row 1 "Allow" and "Decline"; a caption "Allow and remember"; row 2
  "For session", "For this connection", "Always" (Always keeps the error colour and its second-confirmation dialog). Wraps in a `FlowRow`:
  at 360 dp 100 % row 2 is two lines, at 200 % one answer per line; no label is cut ("For this connection" is the widest). Every button is at
  least 48 dp high. Landscape: the answers stay in the right column, the section scrolls on the left, as today.
- Cards without For session (stop, close, skills, an unknown connection) are unchanged: Allow, Always, Decline, no caption.
- The two hints move from under the buttons into the scrolling section (after the warning), so the pinned block is only buttons and the
  existing rule is unchanged: if the pinned answers, the header and the first content line do not fit (200 %), the whole card scrolls from its top.
  Each button's TalkBack label is "<answer>. <hint>".
- Decline moves next to Allow and Always moves last (further from the primary action): the three tags `chat_confirm_*` stay, new tags
  `chat_confirm_connection` and `chat_confirm_remember`. No answer is preselected and none is the Enter key.

### The connection card line (the one place)

On the E1 line under the address: while the connection has a persistent record the text is "Assistant: no questions, remembered <date>"
(also when it has a session entry too); with only a session entry it stays "Assistant: no questions, for the tunnel's lifetime". The Forget
button (48 dp, same TalkBack label) removes both lifetimes at once, no confirmation, no sheet. The line is the same on the device card and
the Connections tab, for any profile, also when the tunnel is down and while the global switch is off (it states what is remembered). It is
gone the moment the record ends and is never shown for a record that was deleted. Why one place and no sheet: today one class and two tools
can be remembered, so the list per class would hold one row; the owner already edits and deletes connections on this card, which are exactly
the events that end a record, and a second surface (sheet, "⋮", Tools list) would only repeat it. Add the sheet when a second class becomes
rememberable (TCP).

### Receipts

A call that ran without a question shows a muted suffix on its line in "Tool calls and results" (inside the grouped row; the counter is
unchanged): "no question: for this connection", "no question: for the tunnel's lifetime" or "no question: confirmations are off". Memory only:
calls made while the conversation is open; a conversation restored from the saved history shows none (Open question Q5).

## Old -> new -> basis (security, assistant permissions, stored data)

| # | Old promise | New | Basis |
| --- | --- | --- | --- |
| 1 | Four answers: Allow, For session, Always, Decline (`agent-contract.md:834`) | five on a card that can be remembered; three (no session answer) elsewhere, unchanged | D8, R63 |
| 2 | A For session grant is in memory only, never in the history, receipts, logs or backups (`:861-862`) | unchanged for For session; the new persistent grant is the one stored thing: one record in `settings.json` (app-private, excluded from backup and transfer), never in history, receipts, logs or the model's input | D6, D8 |
| 3 | Stored data lists no grant (`contract.md:1125-1145`); `assistant_confirmations` is `{"version":1,"global"}`, "overrides may be added later" (`:895-899`) | `overrides` carries the records, version stays 1 (a record weakens a question, an old reader asks); the contract's "stricter override needs a bump" does not apply | plan 3.4, D6 |
| 4 | Malformed value reads as ask (`:895-896`) | retained and extended: any invalid, unknown, incomplete or unverifiable record asks; Unknown fingerprint asks and keeps, Mismatch and Gone delete | retained |
| 5 | Grant ends on an edit, delete or forget of the connection or profile, or a replaced enrollment (`:846-848`) | retained for the persistent grant; enrollment identity is a hash of the public cert data (not the in-memory counter); delete ends at commit, Undo cancelled keeps it; hidden for Undo it does not act | D7, D10 |
| 6 | Profile name and colour are not in the fingerprint (`:848`) | retained | D10, B14b |
| 7 | Assistant off ends grants (`:849`) | retained: records removed in the write that turns it off | retained (see Q3) |
| 8 | A tool's switch off ends it for that tool for good (`:850-852`) | retained: narrowed in the same write, never revived, also after a restart | retained (see Q3) |
| 9 | History clear ends grants (`:853`) | session: retained; persistent: not ended (it is not tied to a chat) | D6, plan 3.1 |
| 10 | Tunnel close, Undo-hide end a grant (`:842-845`) | session: retained; persistent: not ended | D6 |
| 11 | The lock ends every grant, none is used after it (`:854-856`) | session: retained; persistent: kept but unused while locked; no turn uses any grant while locked | proposal, Q1 |
| 12 | Race rules: bound before the question, answer acts as Allow once (`:857-860`) | retained for CONNECTION; adds: a failed save is Allow once plus the existing note | retained, stricter |
| 13 | Grantable tools: `ssh_command` and `terminal_input` only (`:968`, `:1298`) | retained: the record's tools are the card's offered set in effect, never wider; a later tool is not covered | retained |
| 14 | Always: second confirmation, app-wide `global` (`:862-865`) | unchanged; moved to the last position | retained |
| 15 | Rename, colour, transient reconnect, leaving the chat end nothing (`:857`) | retained; a restart and a tunnel close also end nothing (persistent only) | D6, D10 |
| 16 | Not backed up, not transferred (`contract.md:1144`) | retained, guard test extended to `settings.json`'s domain | retained |
| 17 | The card line has no sheet and no list elsewhere (`contract.md:283-289`) | retained: same line, new text for the persistent case; Forget clears both lifetimes | plan 3.5, minimal UI |
| 18 | Deleting a connection removes its settings; forgetting a profile removes its connections (`contract.md:1015-1017`) | also removes its grant records (before the rest, so a failure leaves none behind) | D7, D10 |
| 19 | Read-only calls and `confirms = false` tools never ask (`:809`) | unchanged; the class never decides by itself | retained |
| 20 | Undo cannot apply to a remote change; the owner can turn the question off app-wide (`contract.md:70`) | unchanged; the owner can now also turn it off for one connection | D6 |

## Open questions (not decided here)

- **Q1. App lock and a persistent grant (row 11).** R27/B10 says covered calls, "For this connection" included, keep running while the app is
  locked. E1 already chose the stricter reading for session grants (review fix, `agent-contract.md:854`): all grants end at the lock and no turn
  uses one. Options: (A) persistent is kept but unused while locked, nothing runs covered; (B) persistent also deleted by the lock (dies at each
  cold start: defeats D6); (C) persistent works while locked (B10 as written). Recommendation: A, the proposal above: it never weakens E1 and
  keeps D6. A background wait-triggered turn on a locked phone then asks and times out; confirm that is acceptable.
- **Q2. Snapshot of `global = off` (deferred from E1).** Today Always silently covers every asking tool (`ssh_command`, `terminal_input`,
  `terminal_close`, `skill_save`, `skill_delete`, `stop_tunnel`). Options: (a) E3 leaves `global` as it is; (b) write the asking set as `tools`
  next to `global=off` now and read an old `off` as that set (no re-ask now, a re-ask whenever an asking tool is added); (c) plan 3.4's baseline
  `["ssh_command"]` (re-asks owners who chose Always for the rest: a behaviour change nobody approved). Recommendation: (a). E3 adds no asking tool,
  so nothing widens; (b) needs its own migration matrix and approval, and the first stage that adds an asking tool must decide it. Never (c).
- **Q3. Assistant off and a tool's switch off: end (this contract) or sleep (plan B11, accepted in R27).** Ending is stricter, matches E1's rows
  and the per-tool switches of R63, and needs no "sleeping" state or UI. Sleeping keeps the owner's earlier choices after a switch is flipped
  back. Recommendation: end; confirm it replaces B11 for the persistent grant.
- **Q4. Where permissions are seen and forgotten.** Plan 3.5 proposes the card line, a sheet "Assistant permissions", a "⋮" item and a list in
  Tools. Recommendation: the card line with Forget only, for the reasons under "The connection card line"; add the sheet and the Tools list with
  the second rememberable class. Confirm the departure.
- **Q5. Persist the receipt reason.** Options: (a) memory only (this contract: a restored conversation shows none, no change of the history format);
  (b) an optional field in the saved turn (the reason stays for audit; an older build may not read such a file, as with `events` and `notes`).
  Recommendation: (a) now; (b) when the owner asks to audit old calls.
- **Q6. A notice when an edit resets a grant.** The line disappearing is the only signal today. Recommendation: none in E3 (E1 gives none for
  session grants); add one line on Save if the owner is surprised by the new question.

## Texts (RU / EN), old -> new

| Where | Old | New |
| --- | --- | --- |
| new `confirm_connection` | none | "For this connection" / «Для этого подключения» |
| new `confirm_remember` (caption) | none | "Allow and remember" / «Разрешить и запомнить» |
| new `confirm_connection_hint` | none | "Commands in this connection, remembered until the connection is edited, even if text the assistant read asks for them. Forget it on the connection's card." / «Команды в этом подключении: запоминается, пока подключение не изменят, даже если об этом попросит прочитанный текст. Забыть: на карточке подключения.» |
| new `confirm_connection_hint_input` | none | "Typing into tabs of this connection, ..." / «Ввод во вкладки этого подключения: ...» (same tail) |
| new `confirm_connection_hint_both` | none | "Commands and typing into tabs of this connection, ..." / «Команды и ввод во вкладки этого подключения: ...» (same tail) |
| new `connection_assistant_remembered` | none | "Assistant: no questions, remembered %1$s" / «Помощник: без вопросов, запомнено %1$s» (date, e.g. "Nov 13" / «13 ноя») |
| new receipt suffixes | none | "no question: for this connection" / «без вопроса: для этого подключения»; "no question: for the tunnel's lifetime" / «без вопроса: на время туннеля»; "no question: confirmations are off" / «без вопроса: подтверждения выключены» |
| `connection_assistant_grant` (session-only line) | "Assistant: no questions, for the tunnel's lifetime" | unchanged |
| `connection_assistant_forget`, `_description`, `confirm_not_saved`, `confirm_session*`, `always_dialog_*` | as is | unchanged |

Model-facing texts and `skill.md` are unchanged. Hints for which tools are offered follow E1's rule: `_both` when both are in effect, else the one.

## Acceptance criteria

1. A CONNECTION answer is offered only where For session is, and records (class, connection, fingerprint, tools = offered and in effect). Later
   calls of exactly those tools on that connection skip the question; another connection, `terminal_close`, `stop_tunnel`, skills, and a tool
   that was off at the answer ask.
2. It survives a new `SettingsStore` on the same file (restart), a tunnel disconnect and reconnect, an Undo-hide of the tunnel, leaving the chat,
   closing the conversation, a history clear, a profile rename and a colour change, the global switch off and on.
3. It ends, each with its own test: every field listed under "Fingerprint" (one test per field), delete after commit, "Forget profile" in both modes,
   the last profile's wipe, each re-enrollment path, assistant off and off/on, a tool's switch off (not revived by switching on, also across a
   restart), Terminal tabs off for typing, Forget on the card.
4. Delete hidden for Undo: not used; Undo cancelled: used again; Undo expired: removed.
5. Unknown fingerprint (profiles not loaded, unreadable credential, login or host-key store) asks and keeps the record; Mismatch and Gone remove it.
6. Malformed, incomplete, unknown-class, no-question-class, wrong-mode, wrong-kind, over-500 or duplicate records follow the strict reader; none
   resets `global` or another setting; an invalid record never allows.
7. While locked by the interval no grant is used; the record stays; after the unlock it covers again; with the lock off nothing changes.
8. A failed save is Allow once plus the existing note, with no memory record; a switch-off, edit or assistant off during the wait records nothing.
9. `settings.json` with no record is byte-identical to today's; with one it round-trips; the file lives in the excluded `file` domain.
10. Allow once, Decline, Always (second confirmation), 5-minute no-answer, Stop, event waits, E1's session grants and every model-facing text
    behave as before (existing tests unchanged, except the one named under Validation).
11. The card shows five answers at 100 % RU portrait and 200 % EN landscape with no cut label, 48 dp targets and the existing scroll rule; the line,
    Forget and the receipt suffix show as specified in RU and EN.

## Validation

Unit (JVM, `:app:testDebugUnitTest`):
- `ConnectionGrantFormatTest`: golden JSON, round trip, every strict-reader rule of criterion 6, `global` read independently, an E1-style reader
  (global only) ignores records. The existing test "unknown extra keys and not yet supported overrides are ignored" is replaced (overrides are
  now read); the other `AssistantConfirmationSettingsTest` cases stay.
- `GrantFingerprintTest`: a golden vector; one assertion per field and per non-member (name, colour, secrets, `enabled`); the parity test against
  the session fingerprint; Unknown versus Mismatch for each unreadable component.
- `ConnectionGrantsTest`: `covers`, `record`, `forget`, `forgetProfile`, `observe`, restart (second `SettingsStore`), hidden, locked, Unknown, Gone,
  class and tool pins (`terminal_close`, `stop_tunnel`, `skill_save` never), a later tool not covered, `resolve` ignores records.
- `SettingsStore` tests: atomic narrowing (switch off then on in two updates, Terminal tabs off, assistant off), a failed write keeps the
  committed value, `setAssistantConfirmations` and other updates keep records.
- `ConfirmationGateTest`: CONNECTION recorded with the pre-wait stamp and offered tools; not recorded for a non-offered card, a switch-off, an
  edit or assistant off during the wait, a lock; failed save gives Allow once; Stop race; order and reasons; the `mustAsk` matrix (tool x global x
  session x persistent); `CardAnswersTest` (CONNECTION needs no second confirmation, Always still does).
- Cleaner and wiring tests with fakes: dangling sweep, not-loaded profiles, `commitDelete` (commit, Undo cancel), `forgetProfile` (both modes,
  last), re-enrollment paths. `BackupExclusionGuardTest`: `settings.json` in the excluded domain.

Compose (androidTest, `emulator-5580`, RU 1.0 portrait; EN 200 % landscape for the card; scoped to changed classes, plus screenshots at 1.0 and
200 % before release via `ltest.sh`): `AssistantChatScreenTest` (five answers, order, caption, hints in the section, no connection answer on
stop, close, skill or unknown-connection cards, no cut label, 48 dp, the whole-card scroll at 200 %, tags); `ConnectionCardAssistantTest`
(persistent line with the date, session-only line, both, Forget clears both, wrap at 200 %, descriptions, device card and Connections tab);
receipt suffix. Restore font scale to 1.0. Scoped evidence only: no physical device or real SSH.

Mutation checks (each turns at least one named test red; scratch branch, one at a time, reverted):
M1 `covers` ignores the fingerprint; M2 ignores `tools` membership; M3 ignores the tool's effect now; M4 ignores `locked`; M5 the lock deletes
records; M6 ignores hidden-for-Undo; M7 Unknown treated as Mismatch (deletes); M8 Unknown treated as Match; M9 Gone not deleted; M10 `grantable`
widened to `terminal_close`, `stop_tunnel` or skills, or a class mismatch accepted; M11 the reader accepts a record without class, `fp`, `tools`,
`mode != off` or another `kind`; M12 `resolve` waives by an override scope; M13 recording with a post-wait stamp; M14 `tools` = all grantable
instead of the offered set; M15 narrowing on a switch-off skipped, or counted instead of atomic (revived); M16 assistant off keeps records; M17 `commitDelete`,
`forgetProfile` or a re-enrollment path skips removal; M18 the fingerprint skips a field or includes name or colour; M19 the gate records for a
non-offered CONNECTION or treats it as SESSION; M20 a failed save is treated as saved; M21 `global` normalization drops records or records reset `global`;
M22 Forget leaves the session entry; M23 a history clear, tunnel close or restart deletes a record; M24 the 500 cap or duplicate rule missing.

Gates: the `AGENTS.md` full gate (unit, `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`, `git diff --check`), the repo-docs
documentation checker, Gitleaks, an independent review (assistant permissions, stored data), the per-class table before review. Release notes:
the grants vanish after a rollback; the lock keeps the session and the stored grant unused.

## Completion

After verification: update the surface contracts listed above, mark this file complete, delete it (meta-contract, lifecycle 5).
