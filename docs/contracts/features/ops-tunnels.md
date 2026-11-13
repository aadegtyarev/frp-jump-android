# Feature: tunnel operations in the operations layer (ops migration PR 1)

Status: Approved by the operator via the peer session (Р26 item 3, 2026-11-13). Pure move, no behavior change, nothing added for the
assistant.

## Goal

Move the tunnel scenario operations out of `ui/connections/ConnectionActions.kt` into `ops/`, as `Operation`s with `OpResult` and typed
refusals and failures, next to `tunnel_up`:

- `tunnel_connect`: Connect. It revives a tunnel that is waiting for Undo; otherwise it waits for a disconnect in progress, then
  connects for the connection's kind. This is `TunnelUp` without the 45 s wait.
- `tunnel_down`: disconnect one row, a device, a profile, or everything, over `undo/TunnelDisconnects.kt`.
  - The disconnect is Undo-based: the tunnel goes dark at once, and the server is told when the notice goes.
  - Restoring profiles are told now.
  - With nothing up, every server is told now (restoring profiles included) and `onDone` runs after.
- `direct_retry` and `direct_keep`: Retry direct and Keep through the server, over `direct/DirectChoices.kt`.

The UI keeps all navigation glue (open SSH, MQTT, Files, login at once) and calls the operations through a thin adapter. Owner wording
goes to `ui/OpTexts.kt`: `profile_gone`, `keep_no_network`.

## Behavior that must not change (acceptance basis)

- **Undo, not confirm, about 6 s:**
  - nothing reaches the server until the notice goes;
  - Undo restores;
  - closing the app still completes the action (`docs/contract.md`);
  - terminal and Files sessions close only when Undo is gone;
  - `commitAll` runs when the screens stop, on lock and before Forget;
  - commits run one after another.
- **The tunnel or row goes dark at once.** `Operation.invoke` is `suspend`, so the adapter must start it without a suspension before the
  synchronous part. Use `CoroutineStart.UNDISPATCHED` on `Main.immediate`, or keep the synchronous enqueue outside the suspend part and
  document it.
- **A tunnel used again** while it waits for Undo is revived. While it is going down, the next connect waits and connects afresh.
- **Disconnect all:**
  - the `onDone` spinner timing;
  - the notification's Disconnect all is unchanged (it may reuse the operation only if its behavior stays identical; otherwise leave it);
  - missing profiles show "profile gone" exactly as today.
- **Direct retry and keep:** the `Boolean` meaning, and the `keep_no_network` notice.
- **The assistant** gets none of these operations (`docs/contracts/surfaces/operations.md`: tunnels are not managed by the model).

## Interfaces and constraints

- Effects:
  - `tunnel_connect`: `CHANGE_LOCAL`;
  - `tunnel_down`: `CHANGE_LOCAL`; the commit tells the server later, and that is documented;
  - `direct_*`: `CHANGE_LOCAL`.
- Target: a typed tunnel reference (profile, device, port). `GrantTarget` is not used, because these are UI-only.
- Refusals: the profile is gone. Failures only where today's code reports one. Today's fire-and-forget calls stay fire-and-forget at the
  UI.
- No wording in `ops/`.
- Update `docs/contracts/surfaces/operations.md` (the table, and a section per operation) and `docs/contract.md` where it names the
  mechanism. Promises must not change.

## Acceptance criteria

1. `ConnectionActions` has no tunnel connect, disconnect or direct logic left; it calls the operations.
2. Unit tests for each operation:
   - effect and target;
   - refusals;
   - revive and await order;
   - immediate dark;
   - Undo restore;
   - commit order;
   - Disconnect all with restoring and with nothing up.
   Existing tests stay green unchanged, except ones moved from a removed signature; list those in the PR.
3. Mutations killed:
   - the revive and await order;
   - the immediate effect (a suspension before the enqueue);
   - the refusal vs. Done choice.
4. `operations.md` lists the new operations, and the security note (UI only) is kept.

## Validation

- The full gate; `git diff --check`; the docs checker.
- Emulator: the Connections and device-card UI tests, and the undo-related androidTests if any. At least portrait 1.0 en.
