# SshTargets: one SSH access path (behavior-neutral)

Status: Approved by the parent on 2026-11-12 (phase F5; decisions 1-5
accepted). Base: `e9b2f86`.

Approved decisions: (1) "needs setup" stays inside the worker as
`connector() == null`, not a `resolveSaved` result; (2) `watchLost` callback
registration instead of a Flow; (3) `SshCommandOperation` and `captureSshStream`
move unchanged to `AssistantSshOperation.kt` (imports/visibility only);
(4) `AssistantSshCommands` takes `SshTargets`, four test construction sites change
in construction only; (5) the `TerminalScreen.kt` mapping expression is included.

## Goal

Today three questions about a saved SSH connection are answered by copies:

1. how a `SshAuthMethod` becomes a `SshAuth` (five copies: `ConnectionActions`
   twice, `FilesSession`, `TerminalScreen`, the assistant);
2. how a remembered login connects "without the dialog" (the assistant and
   `ConnectionActions.loginAtOnce` each combine `stored`, `passwords.has`,
   `refusals.get` and `connectsDirectly`);
3. when a saved SSH target is ready and when it is lost (only the assistant,
   inline in `AssistantSshCommands.execute`).

Give each question one owner. This is a refactor: no user-visible, model-visible
or stored-format change; every existing test keeps its scenario.

## Scenarios (all must behave exactly as before)

`SshTargets.resolveSaved(profile, connection)` returns a ready target or
"unavailable" (no tunnel started, nothing prompted) when:

- the profile runtime is missing, state is not loaded, or the profile id is not
  in the index exactly once;
- the connection id is absent or duplicated in the profile, or is not `PortKind.SSH`;
- no tunnel has the connection's `localPortKey`, or several do.

`isAvailable(target)` (the old `available()`, re-evaluated on current state) is
false when any of these holds: state not loaded; `activationPending`; runtime
replaced or `enrollments` changed since capture (enrollment changed);
`session.removed` (profile removed); the connection gone, duplicated or changed
from the captured value; the connection or its tunnel hidden by a pending Undo;
the tunnel pending (connecting/disconnecting); the tunnel gone or not the same
one (key, grantId, localPort); the tunnel not ready (status not
DIRECT/RELAYED/TRYING, waiting for peer/device, device missing).

`watchLost` calls `onLost` when `isAvailable` turned false after an emission of
`profiles`, `hidden`, the connection list or the tunnel state, when
`session.removed` becomes true, or when the plan entry disappears
(`targetPresent` false). Same six sources, same Unconfined launch on the
publisher thread as before.

`connector(target)` (called only inside the admitted worker, as before):
no stored login for device:port -> needs setup; stored login but
`connectsDirectly` false (key refused, password missing/unreadable/refused) ->
needs setup; else a `SshConnector` with `toAuth(remember = true)` (typed
password `null` = the remembered one, read later by the connector). Host-key
trust is still enforced only by the connector; untrusted/changed keys still map
to setup.

`directLogin(profile, device, port, user)` serves the UI's "connect without the
dialog": null when the profile is gone, nothing was chosen before, or
`connectsDirectly` is false.

## Non-goals

- No change to `SshSession`, `SshSessions`, `KeyInstall`, the terminal, the
  Files screen flow, login dialogs or their strings.
- No change to when a password is read (still only inside `SshConnector.logIn`;
  `passwords.has` is still evaluated where it is today: in the admitted worker
  for the assistant, on Main for `loginAtOnce`).
- No automatic host-key trust; no tunnel start or revival; the assistant path
  never prompts. No change to the `SshCommandOperation` state machine, tool
  texts, results or persisted data.

## Affected surface contracts

- [Agent contract, "Optional SSH command"](../../agent-contract.md)
- [Files contract](../../files-contract.md)
- [SSH persistence contract](../../ssh-persist-contract.md)
- [App behavior contract, Connections / SSH console](../../contract.md)

Expected surface edit after verification: the agent contract names `SshTargets`
as the single readiness/login owner (one sentence); no promise changes. If the
diff would require any other edit, stop and ask.

## Interfaces and constraints

New, in package `ru.adsrv.frpjump.ssh` (files `SshAuthMapping.kt`, `SshTargets.kt`):

```kotlin
fun SshAuthMethod.toAuth(password: String = "", remember: Boolean = false): SshAuth
// KEY -> SshAuth.Key; PASSWORD -> SshAuth.Password(password.ifEmpty { null }, remember)

class SavedSshTarget internal constructor(val profile: String, val runtime: ProfileRuntime,
    val enrollment: Int?, val connection: Connection, val tunnel: Tunnel)
sealed interface SshTargetResolution { class Ready(val target: SavedSshTarget); data object Unavailable }
class SshTargets(profiles: StateFlow<ProfilesState>, hidden: StateFlow<Set<String>>, env: SshEnvironment) {
    fun resolveSaved(profileId: String, connectionId: String): SshTargetResolution
    fun isAvailable(target: SavedSshTarget): Boolean
    fun watchLost(target: SavedSshTarget, scope: CoroutineScope, onLost: () -> Unit): List<Job>
    fun connector(target: SavedSshTarget): SshConnector?   // null = needs setup
    fun directLogin(profile: String, device: String, port: Int, user: String): SshLogin?
}
```

Adjustments to the review sketch, needed to preserve behavior:

- `SavedSshTarget` carries no `login`/`auth`: reading `stored`/`passwords.has` in
  `resolveSaved` would run it before worker admission (a tested ordering).
  "Needs setup" is therefore the null of `connector(target)`, not a resolution.
- `lost` is a callback registration, not `Flow<Unit>`: `merge`/`channelFlow`
  would add a dispatch hop between the source publisher and `loseTarget()`.
- `TunnelManager.commandTargetPresent` becomes `targetPresent`;
  `sameCommandTunnel`/`commandTunnelReady` move into `SshTargets` (private).
- One `FrpJumpApp.sshTargets` (lazy, next to `profileInformation`); both
  `assistantSshCommands` and `ConnectionActions` use it. `AssistantSshCommands`
  takes `SshTargets` (constructor change; four tests adapt).
- `SshCommandOperation` and `captureSshStream` move unchanged to
  `AssistantSshOperation.kt` so `AssistantSshCommand.kt` can reach <= 150 lines.

## Acceptance criteria

1. `grep -rn "SshAuthMethod.KEY -> SshAuth.Key" app/src/main` prints exactly one
   line (`SshAuthMapping.kt`); `grep -rn "SshAuth.Password(" app/src/main` prints
   only `SshAuthMapping.kt` and the `SshAuth` declaration/type tests (listed in
   the PR).
2. `grep -rn "connectsDirectly(" app/src/main` prints the definition and exactly
   one call (in `SshTargets`).
3. `grep -rn "sameCommandTunnel\|commandTunnelReady\|commandTargetPresent" app/src`
   prints nothing.
4. `AssistantSshCommand.kt` is <= 150 lines and contains no `profiles.value`,
   `connectsDirectly`, `passwords`, `refusals`, `sshLogins`, `hidden` or tunnel
   readiness logic.
5. `FilesSession`, `ConnectionActions`, `TerminalScreen` read the password at the
   same points; no diff in `SshConnector` or `SshSession*`.
6. Existing tests are unchanged except the four `AssistantSshCommands(...)`
   construction sites (listed in the PR, same scenarios).
7. New `SshTargetsTest` covers every `resolveSaved` and `isAvailable` branch
   above, `watchLost` per source (Undo hide, tunnel pending, tunnel gone,
   enrollment change, activationPending, removed), `connector` null for missing
   login / refused / unreadable password, `directLogin` branches and `toAuth`.
   A mutation check per moved branch fails the suite.
8. Test counts: app >= 1663 + new, agent-core 36, agent-openai 37, no failures.

## Validation

- `~/.cache/frpjump-build/gw.sh "$PWD" :agent-core:test :agent-openai:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`
- `git diff --check`; the `repo-docs` documentation checker.
- Instrumented on `emulator-5580`, default runner: `FilesSessionVisitTest`,
  `SshSessionLifecycleTest`, `TerminalDiagnosticsTest`, `MultiSessionTextModeTest`,
  `SshClipboardAuthorityTest` (SSH/Files neighbors; they must still compile and
  pass). Chat-component runner (`-PassistantChatComponentTests=true`, explicit
  class filter): `AssistantChatScreenTest` (constructs `AssistantSshCommands`).
- No instrumented test drives `ConnectionActions.loginAtOnce`/`openFiles` or
  `FilesSession.changeLogin`; those paths are proven by equivalence and by unit
  tests of `SshTargets`/`toAuth`, not by emulator evidence.
- Mutation checks, AGENTS.md per-class table, operator report in Russian.
