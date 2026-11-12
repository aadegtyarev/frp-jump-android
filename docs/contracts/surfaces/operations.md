# Operations layer

## Boundary, consumers and ownership

An operation is one thing the app can do, written once and independent of who asks. The owner's screens and the assistant's tools are
two adapters over the same operation: neither speaks for the operation, and the operation speaks to neither a person nor a model. This
surface covers the `ops/` package (the operation interface, results and typed reasons), the adapters that use it, and the invariants an
adapter must keep. It does not describe what each product surface does; see the related contracts.

Consumers: the assistant tools `ssh_command` and `profile_information` (through `OperationTool`) and the Connections actions (through
`ui/OpTexts.kt`). Maintainers add an operation by implementing `Operation` and one adapter per caller.

## Interface

- `Operation<I, O>`: `name`, `effect`, `target(input)` (the grant/confirmation target of a changing operation, null when none) and
  `suspend invoke(input): OpResult<O>`.
- `Effect` (`ops/Effect.kt`): `READ_LOCAL`, `READ_REMOTE`, `CHANGE_LOCAL`, `CHANGE_REMOTE`. An operation that changes something is
  `CHANGE_*`. For an assistant tool the effect equals `AssistantToolId.effect`; a test pins this for every operation tool.
- `OpResult<O>`:
  - `Done(value)`: the operation ran to its end. A command that exited nonzero is `Done`: it ran.
  - `Refused(reason: OpRefusal)`: nothing was started.
  - `Failed(reason: OpFailure, outcome: RemoteOutcome)`: started, did not finish. `RemoteOutcome` is `NOT_STARTED`, `UNKNOWN` (the remote
    effect may have happened) or `KNOWN` (the remote side reported how it ended).
- `OpRefusal` and `OpFailure` are sealed, with domain subtypes and no wording. A payload is a fact (an exit status and signal) or a message
  the tunnel manager or the server wrote; the operation layer owns no user- or model-facing text.
- Cancellation propagates as `CancellationException`; it is never an `OpResult`. A caller that stops an operation gets no late result.
- An adapter words every typed result itself and shows or sends nothing the types do not carry (no exception messages, no secrets).

## Operations today

| Operation | Effect | Caller | Result | Refusals | Failures |
| --- | --- | --- | --- | --- | --- |
| `ssh_exec` (`SshExec`) | `CHANGE_REMOTE` | tool `ssh_command` | `OpResult<SshExecOutput>` (streams, status, signal) | `SshRefusal`: `TargetUnavailable`, `NeedsSetup`, `TargetLostBeforeExec`, `ConnectFailed` | `SshFailure`: `TargetLost(exit?)`, `CommandFailed(exit?)`, `Unexpected`; `KNOWN` only when an exit was already reported, else `UNKNOWN` |
| `profile_query` (`ProfileQuery`) | `READ_REMOTE` | tool `profile_information` | `OpResult<ProfileInformationState>` | none | `ProfileQueryFailure.Unavailable` (`UNKNOWN`) |
| `tunnel_up` (`TunnelUp`) | `CHANGE_LOCAL` | UI only (Connections, device card, "Use key login") | `OpResult<Int>` (the local port) | `TunnelRefusal.ProfileGone` | `TunnelFailure`: `DidNotComeUp(detail?)` (`UNKNOWN`), `DeviceNotListening(detail)` (`KNOWN`) |

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
here). It does not come up: `DidNotComeUp` with the tunnel manager's last error for it, when there is one. The device does not open the
port: `DeviceNotListening` with the manager's explanation. The assistant does not get this operation: tunnels are not managed by the model.
If that policy is ever lifted, `tunnel_up` is one catalog row plus one `OperationTool`, with the F8 confirmation applying by its effect.

## Adapters

- **Assistant (`OperationTool`).** Keeps the `AppTool` pipeline: admission first, then strict parsing, then the operation; any exception
  other than cancellation becomes one fixed text and never its message. The tool's effect is the operation's effect; the confirmation
  asks by that effect and by the operation's target, and a changing call that cannot be described is refused. The adapter owns the model
  wording of the output and of each refusal and failure. Every result other than a clean exit is reported to the model as failed, with
  the same texts as before the layer existed ([Optional SSH command](../../agent-contract.md#optional-ssh-command),
  [Optional profile information](../../agent-contract.md#optional-profile-information)).
- **UI (`ui/OpTexts.kt`).** Words refusals and failures for the owner in the app's language; a payload written by the tunnel manager or the
  server is shown as it is. Connections actions show it as a notice; "Use key login" throws an `IOException` with it, as before.

## Security and privacy

- The operation layer adds no authority: the assistant still has only the tools in the catalog; none of them starts, changes or stops a tunnel.
- A command and its output are never part of a result's string form (`SshExecInput` is not a data class).
- Untrusted data (command output, device names, server messages) is data in every adapter, never an instruction.

## Non-goals

- No operation for Files, MQTT publishing or tunnel disconnect yet; each is a later feature with its own contract.
- Wording of errors for the model is not owned here; changing it is a separate feature.
- No provider-specific behavior.

## Related contracts

- [Optional connection assistant](../../agent-contract.md) (tools, confirmation, minimal execution).
- [Shared UI rules and Connections](../../contract.md).
- [Files](../../files-contract.md), [MQTT](../../mqtt-contract.md): unchanged by this surface.
