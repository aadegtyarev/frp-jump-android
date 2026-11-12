# Feature contract: operations layer (assistant refactor, phase F9)

Status: Approved by the operator (autopilot plan 2026-11-12; decision R9: a separate surface `docs/contracts/surfaces/operations.md`).
Implemented; verification recorded in the PR. Temporary: delete this file when the PR is merged (meta-contract, Completion).

## Goal

Introduce the model "the UI and the LLM are two adapters over one operation" and prove it on three pilots, without widening what the
assistant may do. Behavior-neutral: the model gets the same tool specs, the same result texts and the same confirmation cards; the
owner sees the same notices.

## Scope

- Package `app/.../ops/`: `RemoteOutcome`, `OpResult` (`Done` / `Refused` / `Failed(reason, outcome)`), `Operation<I, O>` (name, effect,
  target, invoke) and sealed `OpRefusal` / `OpFailure` hierarchies with domain subtypes and no wording.
- `assistant/tools/OperationTool.kt`: the adapter that makes an operation an assistant tool (`AppTool` pipeline, rendering, model wording of
  typed refusals and failures). The F8 confirmation takes the effect and the target from the operation.
- Pilot A: `SshExec` (over `SshTargets` and the existing `SshCommandOperation`). `ssh_command` becomes an `OperationTool`.
- Pilot B: `ProfileQuery` (over `ProfileInformation` and `profileInformationText`). `profile_information` becomes an `OperationTool`.
- Pilot C (UI only): `TunnelUp`, moved from `ConnectionActions.tunnelPort`; returns `OpResult<Int>`; `ConnectionActions` words the result
  through `ui/OpTexts.kt`. The assistant does not get it.

## Non-goals

- No new assistant tool, no change to the tool catalog, prompts, tool descriptions, schemas or result texts (F10 changes error texts later).
- `TunnelUp` is not exposed to the model: the assistant does not start, change or stop tunnels.
- Files and MQTT are not rewritten. `TunnelDown`, `RemoteFiles` and `MqttPublish` are later features, each with its own contract.
- Nothing is built for a second provider; nothing blocks one.

## Affected surface contracts

- [`docs/contracts/surfaces/operations.md`](../surfaces/operations.md) (new).
- [`docs/agent-contract.md`](../../agent-contract.md): Optional profile information, Optional SSH command, Confirmation of changing calls,
  boundary and non-goals (tunnel control stays unavailable).
- [`docs/contract.md`](../../contract.md): scenario 4 (tap on a connection's card) and the Connections tab: bringing a tunnel up and the
  notice when it does not come up.
- Read, unchanged: [`docs/files-contract.md`](../../files-contract.md), [`docs/mqtt-contract.md`](../../mqtt-contract.md),
  [`docs/ssh-persist-contract.md`](../../ssh-persist-contract.md), the terminal contracts.

## Deviations from the plan

- `Operation.target` returns the F8 `GrantTarget?`, not a `String?`, so the confirmation key stays structured. `ops` depends on `settings.GrantTarget`.
- `TunnelFailure` carries a detail string written by the tunnel manager or the server (`awaitDevice` returns text and its tests pin that);
  the ops layer itself words nothing.
- `RemoteOutcome` choices (the plan gave none): SSH failures are `KNOWN` only with an exit already reported, else `UNKNOWN`; a profile query
  failure and `DidNotComeUp` and `DeviceNotListening` are `UNKNOWN`. `NOT_STARTED` exists but nothing returns it.
- `OperationTool` also takes `render(input, output)` (not only the output) and an `outcome(output)` hook, because the profile text depends on
  the query and an SSH command that exited nonzero is a finished command reported to the model as failed.
- `ProfileQuery` and `ProfileInformationQuery` are internal; `SshExec`, `SshExecInput`/`Output` and `TunnelUp` are public.
- `OperationTool` refuses to be built when the operation's effect differs from the catalog's (fail closed), and a changing call without a
  command text is undescribed and refused.

### Tests rewritten because a signature was removed

- `AssistantConfirmationFlowTest`: the `AssistantSshCommands.execute(profile, connection, command)` override became an `exec` operation
  override (it also defines `target`); same scenarios.
- `AssistantSshCommandRealSshTest`, `AssistantSshCommandTest` (the `SshCommandOperation.run`/`execute` call sites): `ToolOutput` became
  `OpResult`; the target-loss scenario asserts `Failed(TargetLost(null), UNKNOWN)` instead of the text.
- `AssistantModelSurfaceTest`: imports only.

## Behavior that must not change (acceptance basis)

- Every model-facing text, byte for byte: the `ssh_command` and `profile_information` specs and descriptions, invalid-argument texts,
  all SSH refusal and failure texts, the success text, the outcomes (`SUCCESS` only for exit 0 without a signal), the profile failure text.
- Admission before parsing, no automatic retries, cancellation propagates, the worker/readers/disconnect exit before the call returns, the
  3000-character stream capture, "remote outcome unknown" after a possible start, no tunnel started for the assistant, no login question,
  no automatic host-key trust, no credentials from the model.
- Confirmation (F8): the card shows the same tool, target and command; the grant key is the same tool plus target; a changing call that
  cannot be described is refused; the effect is the catalog's.
- UI: `tunnelPort`'s order of work and timing (revive or await the disconnect, connect when down and not pending, 45 s wait, wait for the
  device as fresh when brought up here) and the notice texts: "profile forgotten", the tunnel manager's last error or "The tunnel is not
  connected.", the tunnel manager's device explanation. "Use key login" still receives an `IOException` with the same message.

## Acceptance criteria

1. `ssh_command` and `profile_information` are `OperationTool`s over `SshExec` and `ProfileQuery`.
2. `operation.effect == AssistantToolId.effect` for every operation tool (test).
3. `ConnectionActions` has no "bring up and wait" logic; `TunnelUp` has a unit test in `ops/`; `TunnelUp` is not reachable from any assistant tool.
4. Existing golden and surface tests pass unchanged except those listed in the PR as moved from a removed signature.
5. Mutations of effect, target, refusal-versus-failure and `RemoteOutcome` in the operations are killed by tests.
6. The PR describes the operations: name, effect, caller (UI / tool), result type.

## Validation

`./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`,
`git diff --check`, the repo-docs checker, and emulator runs of the chat and Connections UI classes (one configuration at least).
