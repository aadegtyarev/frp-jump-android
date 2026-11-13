# Operations layer

## Boundary, consumers and ownership

An operation is one thing the app can do, written once and independent of who asks. The owner's screens and the assistant's tools are
two adapters over the same operation: neither speaks for the operation, and the operation speaks to neither a person nor a model. This
surface covers the `ops/` package (the operation interface, results and typed reasons), the adapters that use it, and the invariants an
adapter must keep. It does not describe what each product surface does; see the related contracts.

Consumers: the assistant tools `ssh_command` and `profile_information` (through `OperationTool`) and the Connections actions (through
`ui/OpTexts.kt`; `ConnectionActions` is their thin adapter and keeps the navigation glue: opening SSH, MQTT and Files, login at once). Maintainers add an operation by implementing `Operation` and one adapter per caller.

## Interface

- `Operation<I, O>`: `name`, `effect`, `target(input)` (the F8 `GrantTarget` of a changing operation, null when none; `ops` therefore depends
  on `settings.GrantTarget`) and
  `suspend invoke(input): OpResult<O>`.
- `Effect` (`ops/Effect.kt`): `READ_LOCAL`, `READ_REMOTE`, `CHANGE_LOCAL`, `CHANGE_REMOTE`. An operation that changes something is
  `CHANGE_*`. For an assistant tool the effect equals `AssistantToolId.effect`; a test pins this for every operation tool.
- `OpResult<O>`:
  - `Done(value)`: the operation ran to its end. A command that exited nonzero is `Done`: it ran.
  - `Refused(reason: OpRefusal)`: nothing was started.
  - `Failed(reason: OpFailure, outcome: RemoteOutcome)`: started, did not finish. `RemoteOutcome` is `UNKNOWN` (the remote effect may have
    happened) or `KNOWN` (the remote side reported how it ended). `NOT_STARTED` is reserved for a failure before any remote call that is
    not a refusal; no operation returns it today.
- `OpRefusal` and `OpFailure` are sealed, with domain subtypes and no wording. A payload is a fact: an exit status and signal, or a typed
  domain error from `errors/DomainError.kt` (the tunnel manager's last error, the device port that is not open). The operation layer owns no
  user- or model-facing text.
- A typed error keeps, for the owner's screens only, what they need to word it: a server's `detail`, a host, and the system's exception
  messages (`Other.text`, the detail of an unreachable or broken connection). The screens word it when they show it
  (`ui/DomainErrorTexts.kt`). None of that reaches the model: it gets the error's kind alone.
- Cancellation propagates as `CancellationException`; it is never an `OpResult`. A caller that stops an operation gets no late result.
- An adapter words every typed result itself and shows or sends nothing the types do not carry; it adds no exception message or secret
  of its own. The owner's screens show the payload a typed error keeps for them; the model gets only the error's kind, never that
  payload.

## Operations today

| Operation | Effect | Caller | Result | Refusals | Failures |
| --- | --- | --- | --- | --- | --- |
| `ssh_exec` (`SshExec`) | `CHANGE_REMOTE` | tool `ssh_command` | `OpResult<SshExecOutput>` (streams, status, signal) | `SshRefusal`: `TargetUnavailable`, `NeedsSetup`, `TargetLostBeforeExec`, `ConnectFailed` | `SshFailure`: `TargetLost(exit?)`, `CommandFailed(exit?)`; `KNOWN` only when an exit was already reported, else `UNKNOWN`. `SshFailure.Unexpected` is never returned by the operation: it is the adapter's word for an exception that escaped it |
| `profile_query` (`ProfileQuery`) | `READ_REMOTE` | tool `profile_information` | `OpResult<ProfileInformationState>` | none | `ProfileQueryFailure.Unavailable` (`UNKNOWN`) |
| `tunnel_up` (`TunnelUp`) | `CHANGE_LOCAL` | UI only (Connections, device card, "Use key login") | `OpResult<Int>` (the local port) | `TunnelRefusal.ProfileGone` | `TunnelFailure`: `DidNotComeUp(error: DomainError?)` (`UNKNOWN`), `DeviceNotListening(reason: DevicePortNotOpen)` (`UNKNOWN`: the device either refused or did not answer in time, and the result does not say which) |
| `tunnel_connect` (`TunnelConnect`) | `CHANGE_LOCAL` | UI only (Connections, device card) | `OpResult<Unit>`: the connect was asked, not waited for | `TunnelRefusal.ProfileGone` | none |
| `tunnel_down` (`TunnelDown`) | `CHANGE_LOCAL` (the commit tells the server later) | UI only (Connections, Devices, device card) | `OpResult<Unit>` | `TunnelRefusal.ProfileGone` (device and profile targets) | none |
| `direct_retry` (`DirectRetry`) | `CHANGE_LOCAL` | UI only (device card, Connections) | `OpResult<Unit>` | none | none |
| `direct_keep` (`DirectKeep`) | `CHANGE_LOCAL` | UI only (device card, Connections) | `OpResult<Unit>` | `DirectRefusal.NoNetwork` | none |

### `ssh_exec`

Runs one noninteractive command on one saved SSH connection of any profile whose tunnel is already ready and visible. Never starts a
tunnel, never asks a login question, never trusts a host key, takes no credentials from the caller. The target is
`GrantTarget(profileId, connectionId)`. A refusal means no command was sent: the target is unavailable now or was lost before the command, the
stored login or host trust needs the ordinary SSH UI, or the connection could not be made. After the command may have started, loss of the
target or any error is a failure, with the exit the channel already reported when there is one. The worker, the readers and every
disconnect have exited before the call returns; stopping the caller, a timeout, revocation and target loss all disconnect the client.
Each stream keeps at most 3000 UTF-16 characters and the rest is drained.

### `profile_query`

Refreshes the device lists once and reads the app-known devices and saved connections of every profile. Reads only; no cache, inventory
owner or connection control. The caller chooses how the refresh is admitted. A failed refresh or read is a failure with an unknown outcome:
no successful refresh is implied.

### `tunnel_up`

Returns the local port of the tunnel to a device port, bringing it up when it is down. In order: a tunnel whose disconnect waits for Undo is
revived and stays up, otherwise a disconnect being done is awaited; when the tunnel is down and no connect is in flight it is asked to
connect for the connection's kind; it is waited for up to 45 s; then the device is waited for (as a fresh tunnel when it was brought up
here). It does not come up: `DidNotComeUp` with the tunnel manager's last typed error for it, when there is one. The device does not open the
port: `DeviceNotListening` with `DevicePortNotOpen(port)`, the port that is not open. The assistant does not get this operation:
tunnels are not managed by the model. If that policy is ever lifted, a tunnel tool needs its own design: `GrantTarget` names a saved connection (profile and connection ID) and cannot express a device port, so the confirmation target and grant key would have to be decided first.

### `tunnel_connect`

Asks a tunnel to connect for the connection's kind and returns without waiting for it (`tunnel_up`, above, is the one that waits).
In order: a tunnel whose disconnect waits for Undo is revived and only stays up; a profile that is gone is refused; a disconnect being done
is awaited and the tunnel is connected after it, not into it; otherwise the connect is asked before the operation first suspends. The
screens start it undispatched, so the ask is made when the tap returns. The target is a typed tunnel reference (profile, device, port,
kind), not a `GrantTarget`. The assistant does not get it.

### `tunnel_down`

Disconnects with Undo, over `undo/TunnelDisconnects.kt`: one tunnel (a row), every tunnel of a device, of a profile, or of every profile.
The effect is local and at once: before the operation first suspends the tunnels show down (hidden in `PendingUndo`), and the screens
start it undispatched so the row goes dark when the tap returns. The server is told later, when the notice goes (or on `commitAll`:
screens stopping, lock, before Forget), one commit after another; Undo restores and tells nothing; that later commit is not part of the
result. A device or a profile that does not exist is refused. For "all": with something shown up, every tunnel goes dark under one notice
and the profiles still being restored after a restart (nothing of them on screen to wait for) have their servers told at once, not
waited for; with nothing shown up, every server is told and the result comes after each was asked (the "Disconnect all" spinner follows
that). The notification's "Disconnect all" does not use Undo and does not use this operation. The assistant does not get it.

### `direct_retry` and `direct_keep`

The owner's two answers to "Direct didn't work" over `direct/DirectChoices.kt`. Retry is always done (a missing profile changes nothing,
as before). Keep is refused with `DirectRefusal.NoNetwork` when the phone is on no network the choice can be tied to: nothing changed.
The screens word it (`keep_no_network`). Fire-and-forget at the UI, as before. The assistant does not get them.

## Adapters

- **Assistant (`OperationTool`).** Keeps the `AppTool` pipeline: admission first, then strict parsing, then the operation; any exception
  other than cancellation becomes one fixed text and never its message. The tool's effect is the operation's effect, and a tool cannot be built when it
  differs from the catalog's (the confirmation reads both, so a mismatch would skip the question); a call counts as changing when either says
  so. The confirmation asks by that effect and by the operation's target, and a changing call with nothing to show the owner (no command text)
  cannot be described and is refused. The adapter owns the model
  wording of the output and of each refusal and failure. Every result other than a clean exit is reported to the model as failed, with
  the same texts as before the layer existed ([Optional SSH command](../../agent-contract.md#optional-ssh-command),
  [Optional profile information](../../agent-contract.md#optional-profile-information)).
- **UI (`ui/OpTexts.kt`).** Words refusals and failures for the owner in the app's language; a payload written by the tunnel manager or the
  server is shown as it is. Connections actions show it as a notice; "Use key login" throws an `IOException` with it, as before.

## Security and privacy

- The operation layer adds no authority: the assistant still has only the tools in the catalog; none of them starts, changes or stops a tunnel (`tunnel_connect`, `tunnel_down`, `direct_*` are UI only).
- A command and its output are never part of a string form: `SshExecInput` and `SshExecOutput` are plain classes whose `toString` leaves them out (tested).
- Untrusted data (command output, device names, server messages) is data in every adapter, never an instruction.

## Non-goals

- No operation for Files or MQTT publishing yet; each is a later feature with its own contract.
- Wording of errors for the model is not owned here; changing it is a separate feature.
- No provider-specific behavior.

## Related contracts

- [Optional connection assistant](../../agent-contract.md) (tools, confirmation, minimal execution).
- [Shared UI rules and Connections](../../contract.md).
- [Files](../../files-contract.md), [MQTT](../../mqtt-contract.md): unchanged by this surface.
