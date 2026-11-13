# Feature: MQTT writes in the operations layer (ops migration PR 2)

Status: approved by the operator via the coordinator (Р26 item 3, Р28: plan accepted, PR 1–6, released in groups 1+2, 3+4, 5, 6). This
is a pure move: no behavior change and nothing added for the assistant.

## Goal

Move the two MQTT write scenarios out of `ui/mqtt/MqttViewModel.kt` into `ops/`, as `Operation`s with `OpResult` and typed outcomes:

- `mqtt_publish` (`MqttViewModel.kt` ~680-697, over `MqttSession.publish` ~290). The result is already typed as `PublishAnswer`
  (`mqtt/Publishing.kt`): Accepted, Refused(code, reason), Unconfirmed, or Failed(NOT_CONNECTED | LOST | TIMEOUT |
  CONTROL_NOT_WRITABLE | OTHER). Map it into `OpResult` without losing a case. The owner wording stays in the UI
  (`PublishSheet.kt`); `OpTexts` may take it if that fits.
- `wb_write` (`MqttViewModel.kt` ~434-453, over `MqttSession.publishWb` ~295 and `WbWriteBook` in
  `mqtt/plugins/wirenboard/WbWrites.kt`).

## Behavior that must not change

- Publish:
  - it is answered once, on the session actor;
  - the 10 s timeout;
  - NOT_CONNECTED unless open;
  - generic publish does not enforce WB readonly;
  - a send that already left the phone cannot be taken back, so caller cancellation must not imply it.
- `PublishMemory` is written under `synchronized(memory)` in `viewModelScope` (IO) after a send that left the phone. It stays
  ViewModel-scoped: it is lost if the ViewModel is cleared first, exactly as today. Do not move it to the app scope.
- WB write:
  - the guards are unchanged: the exact owner (`ownsWbWrites`) and `wbWritable` are checked in the same actor action as the send;
  - `writeBlocked` stops a double tap;
  - a late answer owns its exact write;
  - `writeWaiting` speeds the refresh up to 250 ms.
  - The book stays PER VIEWMODEL. Do not move it to the session; that would make answers survive leaving the screen.
- Contracts: `docs/mqtt-contract.md` (publish ~492-530, WB writes ~867-912). The UI texts are unchanged.
- The assistant gets none of these operations (`docs/contracts/surfaces/operations.md`).

## Interfaces and constraints

- Effect `CHANGE_REMOTE`.
- Target: a typed MQTT target (profile, connection id, plus the control path for WB). `GrantTarget` is not used: these are UI-only.
- No wording in `ops/`.
- Update `docs/contracts/surfaces/operations.md` (the table, and a section per operation).

## Acceptance criteria

1. `MqttViewModel` has no publish or WB-write send logic left; it calls the operations.
2. Unit tests per operation:
   - every `PublishAnswer` case maps to the right `OpResult`;
   - the WB guards (owner, writable, a blocked double tap, a late answer for its own write);
   - the memory write stays ViewModel-scoped.

   The existing tests stay green unchanged: `PublishingTest`, `PublishMemoryTest`, `PublishStartTest`, `PublishJsonTest`,
   `MqttBrokerTest`, `WbPublishingTest`, `WbWritesTest`, and androidTest `WbReadonlyTest`.
3. Mutations are killed:
   - a mapping case swapped;
   - a guard removed;
   - the memory moved to the app scope (pinned by a source guard if a runtime test is impractical).
4. `operations.md` is updated, and its UI-only note is kept.

## Validation

- The full gate, `git diff --check`, the docs checker.
- Emulator: the MQTT androidTests (`WbReadonlyTest` and any publish UI test) at least in portrait 1.0 en.
