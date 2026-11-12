# Feature: typed domain errors (F10)

Status: Approved by the operator. Decision Р10 (2026-11-12) approves F10, including the new error text the model receives. The set of kinds
below was sent to the peer session on 2026-11-13 under Р14; renaming a kind is a mechanical change.

## Goal and user-visible behavior

- Domain state stops carrying localized UI text. `TunnelManager` and `DeviceDirectory` no longer import `R`, `Texts` or
  `ui.userMessage`. They store typed errors, and a UI mapper words them.
- The owner sees exactly the same texts as before, in every place that showed them.
- The `profile_information` tool tells the model the reason as a fixed kind, instead of one fixed phrase per field.

## The model-facing change (the only one)

These error fields of `profile_information` change from a fixed phrase to an object `{"kind": "<KIND>"}`. Every other field and every
other text stay byte-identical. That includes the whole-tool failure text, invalid-argument texts, the record-budget `"error"` string,
`waiting`, `error_count` and the tunnel booleans.

| JSON path | Before | After: kinds |
| --- | --- | --- |
| `source_status.load_error` | `"Profile loading failed"` | `PROFILES_LOAD_FAILED` |
| `results[].profile.device_list.error` | `"Device list refresh failed"` | server kinds (below) |
| `results[].profile.tunnel_state.sync_error` | `"Tunnel synchronization failed"` | server kinds, `NO_FREE_PORT` |
| `results[].profile.tunnel_state.mismatch` | `"Protocol mismatch"` | `PROTOCOL_UPDATE_APP`, `PROTOCOL_UPDATE_SERVER` |
| `results[].connection.error`, `results[].operation.error` | `"Connection operation failed"` | server kinds, `NOT_LISTED` |

Server kinds come from how a server or network call failed:

| Kind | Meaning |
| --- | --- |
| `SERVER_ERROR` | the server answered with an error |
| `NOT_HTTPS` | the control address is not HTTPS |
| `UNREACHABLE` | the server could not be reached |
| `CONNECTION_BROKEN` | the connection broke during the call |
| `REDIRECTED` | the server redirected |
| `UNEXPECTED_ANSWER` | the answer could not be read |
| `UNEXPECTED` | anything else |

The other kinds:

| Kind | Meaning |
| --- | --- |
| `NOT_LISTED` | the tunnel was granted, but it was not listed in time |
| `NO_FREE_PORT` | no free local port in the range |

A kind carries no payload: no server detail, host, URL, exception message, class name, HTTP body or frpc text. A null field stays null.

## Scenarios

- Each failure branch of today's `Throwable.userMessage()` maps to one typed error. The typed error keeps the payload the UI needs
  (server detail, host, IOException message, redirect host, port range, protocol versions). The model only ever gets its kind.
- Per field:
  - a tunnel connect that was granted but not listed in time is `NOT_LISTED`;
  - a protocol mismatch is `PROTOCOL_UPDATE_APP` when the server's version is newer, else `PROTOCOL_UPDATE_SERVER`;
  - a profile load failure is `PROFILES_LOAD_FAILED`.
- `awaitDevice` returns a typed "device port not open" fact. `TunnelFailure.DeviceNotListening` and `DidNotComeUp` carry typed values,
  not strings. `OpTexts` words them exactly as before, including the "Use key login" `IOException` message.
- `TunnelManager`'s state projection stops writing the localized `detail` for a pinned port that is taken or a missing device. The UI words
  those from the typed flags, as before. The frpc parser's own detail strings are out of scope (see non-goals). `portTaken()` keeps
  matching raw frpc text.
- Saved chat histories from 0.3.70 and earlier still hold the old phrases:
  - they are re-sent to the model as saved;
  - the facts view in the chat accepts both shapes (a string and `{"kind"}`) and shows its line in both cases.
- Language change: texts are resolved when shown, so the UI no longer mixes languages from the time an error was stored.

## Non-goals

- No change to user-visible texts. No change to the `waiting` classification or to error lifetimes.
- `FrpcLogParser` texts, `Updater`, Files, SSH and KeyInstall error strings stay as they are; later work.
- No new model-facing fields or texts beyond the table above. No HTTP status or payload for the model.
- No change to `ssh_command` texts.

## Affected surfaces

- [`docs/agent-contract.md`](../../agent-contract.md), "Optional profile information": "fixed descriptions by source field" becomes
  "a fixed kind by source field", and the kinds are listed.
- [`docs/contract.md`](../../contract.md): the profile-information summary ("Fixed error descriptions").
- [`docs/contracts/surfaces/operations.md`](../surfaces/operations.md): the `TunnelFailure` payloads become typed. Remove "a message the
  tunnel manager or the server wrote" where it no longer holds.

## Interfaces and constraints

- A domain package, for example `errors/DomainError.kt`, holds:
  - a sealed type of value classes or data classes with stable `equals`, never Throwables;
  - a `kind` for the model;
  - `Throwable.toDomainError()`, which takes over the branch logic of `userMessage()`.
- `ui/DomainErrorTexts.kt` words a `DomainError`. `ui/Errors.kt` `userMessage()` stays for its other callers, but is implemented through
  `toDomainError()` plus the mapper, so the texts cannot drift.
- Field types:
  - `TunnelsState.errors: Map<String, DomainError>`;
  - `syncError: DomainError?`;
  - `mismatch: ProtocolMismatch?` (typed);
  - `DeviceList.error: DomainError?`;
  - `ProfilesState.failure` typed, or mapped to `PROFILES_LOAD_FAILED` at the projection.
- `TunnelManager` log lines that used `userMessage()` use a payload-free form, for example the kind or the class name.
- Update the reflection-based `TunnelManagerTest` helpers (`failOperation`, `coreMap("errors")`) to the new types.
- Preserve all security and cancellation guarantees. No raw payload may reach the model: assert this in tests with synthetic private
  strings.

## Acceptance criteria

1. `grep -n "import ru.adsrv.frpjump.ui\|import ru.adsrv.frpjump.R$\|Texts\." app/src/main/java/ru/adsrv/frpjump/tunnel/TunnelManager.kt app/src/main/java/ru/adsrv/frpjump/devices/DeviceDirectory.kt`
   is empty.
2. For every exception branch, a unit test pins `DomainErrorTexts` against the exact previous `userMessage()` output, in EN and RU.
3. Existing UI-text tests pass unchanged, except tests that asserted raw text inside domain state. Those are rewritten to assert the typed
   value plus its worded text, and listed in the PR.
4. Model-surface golden tests change only in the five error fields. A test with synthetic private server detail, host and exception
   message shows that none of them appear in any `profile_information` output.
5. The chat facts view shows the operation-error line for both the old string and the new object (unit test).
6. Mutations are killed by tests:
   - swapping two kinds;
   - leaking the payload into the kind;
   - the mismatch direction.
7. The docs in "Affected surfaces" are updated.
8. The kinds are a model-visible format (Р16, 2026-11-13):
   - `docs/agent-contract.md` lists every kind, what it means and which fields it can appear in;
   - a unit test pins the exact set of kind strings per field against a fixed list, so that adding, removing or renaming a kind fails
     the test until both the list and the contract are changed on purpose.

## Validation

- `./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`
- `git diff --check`
- The repo-docs checker.
- Emulator, chat runner: `AssistantChatScreenTest` (EN and RU, 4 configurations each).
- Default runner: Connections, Devices and Files classes that show these errors (at least one configuration).
