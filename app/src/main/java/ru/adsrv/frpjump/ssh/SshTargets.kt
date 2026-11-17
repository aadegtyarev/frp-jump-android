package ru.adsrv.frpjump.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.profiles.ProfileRuntime
import ru.adsrv.frpjump.profiles.ProfilesState
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.undo.PendingUndo

/** A saved SSH connection with its ready tunnel, as captured by [SshTargets.resolveSaved]. */
class SavedSshTarget internal constructor(
    val profile: String,
    val runtime: ProfileRuntime,
    internal val enrollment: Int?,
    val connection: Connection,
    val tunnel: Tunnel,
)

sealed interface SshTargetResolution {
    class Ready(val target: SavedSshTarget) : SshTargetResolution

    /** Nothing was attempted; no tunnel was started. */
    data object Unavailable : SshTargetResolution
}

/**
 * The one answer to "may this saved SSH connection be used now, and how does it log in without a dialog".
 * Never starts tunnels, never prompts, never trusts a host key.
 * The one owner that decides the assistant's target readiness ([resolveSaved], [isAvailable]), target loss ([watchLost]) and the
 * no-dialog login rule ([directLogin], [connector]); the Connections tab's "connect without the dialog" calls [directLogin]. The
 * Terminal, Connections and Files login dialogs derive credentials from the chosen method through one function,
 * [SshAuthMethod.toAuth] (`SshAuthMapping.kt`).
 */
class SshTargets(
    private val profiles: StateFlow<ProfilesState>,
    private val hidden: StateFlow<Set<String>>,
    private val env: SshEnvironment,
    /** How many times an Undo key was hidden ([PendingUndo.hideCount]); part of a period, so a hide that Undo then cancels still ends it. */
    private val hides: (String) -> Long = { 0L },
) {
    /** A saved SSH connection of [profileId] whose tunnel is already ready and visible; else [SshTargetResolution.Unavailable]. */
    fun resolveSaved(profileId: String, connectionId: String): SshTargetResolution {
        val state = profiles.value
        val runtime = state.runtimes[profileId] ?: return SshTargetResolution.Unavailable
        if (!state.loaded || state.index.profiles.count { it.id == profileId } != 1) return SshTargetResolution.Unavailable
        val saved = runtime.connections.connections.value.filter { it.id == connectionId }.singleOrNull()
            ?.takeIf { it.kind == PortKind.SSH } ?: return SshTargetResolution.Unavailable
        val tunnel = runtime.tunnels.state.value.tunnels.filter { it.key == localPortKey(saved.device, saved.port) }
            .singleOrNull() ?: return SshTargetResolution.Unavailable
        val target = SavedSshTarget(profileId, runtime, state.enrollments[profileId], saved, tunnel)
        return if (isAvailable(target)) SshTargetResolution.Ready(target) else SshTargetResolution.Unavailable
    }

    /** (profile name, connection) of a saved connection of any kind or state; null when either is unknown. Pure lookup. */
    fun labels(profileId: String, connectionId: String): Pair<String, Connection>? {
        val state = profiles.value
        val profile = state.index.profiles.singleOrNull { it.id == profileId } ?: return null
        val connection = state.runtimes[profileId]?.connections?.connections?.value?.singleOrNull { it.id == connectionId } ?: return null
        return profile.name to connection
    }

    /**
     * Equal while nothing the owner can edit about the saved connection changed: all its fields, its profile's id, key alias and activation, the
     * profile's enrollment, and the non-secret SSH login (user and method) and pinned host key of its device and port. The profile's name and
     * colour are NOT in it. Null when the connection or its profile is gone. Reads no secret and needs no Keystore unlock: the login memory and
     * the pinned keys are plain stores.
     */
    fun fingerprint(profileId: String, connectionId: String): Any? {
        val state = profiles.value
        val profile = state.index.profiles.singleOrNull { it.id == profileId } ?: return null
        val runtime = state.runtimes[profileId] ?: return null
        val connection = runtime.connections.connections.value.singleOrNull { it.id == connectionId } ?: return null
        return listOf(connection, profile.id, profile.keyAlias, profile.activationPending, state.enrollments[profileId],
            runtime.sshLogins.stored(connection.device, connection.port), runtime.hostKeys.pinned(connection.device, connection.port))
    }

    /**
     * The token of the open period of the tunnel of a saved connection ([ru.adsrv.frpjump.tunnel.TunnelManager.openPeriod]); null when its tunnel is
     * not open: not planned, a Disconnect pending, hidden for Undo (also when Undo then cancels it), or the connection or profile unknown or hidden.
     */
    fun period(profileId: String, connectionId: String): Any? {
        val state = profiles.value
        if (state.index.profiles.none { it.id == profileId }) return null
        val runtime = state.runtimes[profileId] ?: return null
        val connection = runtime.connections.connections.value.singleOrNull { it.id == connectionId } ?: return null
        val gone = hidden.value
        if (PendingUndo.connectionKey(profileId, connectionId) in gone || PendingUndo.tunnelKey(profileId, connection.device, connection.port) in gone) return null
        val open = runtime.tunnels.openPeriod(connection.device, connection.port) ?: return null
        // The hide counts are part of it: hidden for Undo and brought back is not the same period any more (the key may be seen again by nobody in between).
        return OpenPeriod(open, hides(PendingUndo.tunnelKey(profileId, connection.device, connection.port)), hides(PendingUndo.connectionKey(profileId, connectionId)))
    }

    /** An opaque period of a saved connection's tunnel: equal only while it is the same open tunnel and was never hidden since. */
    private data class OpenPeriod(val tunnel: Long, val tunnelHides: Long, val connectionHides: Long)

    /** Emits whenever something [fingerprint] or [period] reads may have changed (any profile, connection, login, host key, tunnel period or hidden key). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun changes(): Flow<Unit> = merge(
        profiles.map { }, hidden.map { },
        profiles.map { it.runtimes.values.toList() }.distinctUntilChanged().flatMapLatest { runtimes ->
            merge(*runtimes.map { runtime ->
                merge(runtime.connections.connections.map { }, runtime.tunnels.periodChanges.map { }, runtime.sshLogins.changes.map { }, runtime.hostKeys.changes.map { })
            }.toTypedArray())
        },
    )

    /** Whether [target] is still the same ready, visible target on the current state. */
    fun isAvailable(target: SavedSshTarget): Boolean {
        val profile = target.profile
        val runtime = target.runtime
        val saved = target.connection
        val tunnel = target.tunnel
        val connection = saved.id
        val now = profiles.value
        val t = runtime.tunnels.state.value
        return now.loaded && now.index.profiles.singleOrNull { it.id == profile }?.activationPending == false &&
            now.runtimes[profile] === runtime && now.enrollments[profile] == target.enrollment &&
            !runtime.session.removed.value && runtime.connections.connections.value.count { it.id == connection } == 1 &&
            runtime.connections.connections.value.singleOrNull { it.id == connection } == saved &&
            PendingUndo.connectionKey(profile, connection) !in hidden.value &&
            PendingUndo.tunnelKey(profile, saved.device, saved.port) !in hidden.value &&
            t.pending[tunnel.key] == null && t.tunnels.any { sameTunnel(it, tunnel) && tunnelReady(it) }
    }

    /**
     * Calls [onLost] when [target] stops being available. Observers only signal; they run unconfined on the
     * publisher's thread and must not suspend or clean up. The caller cancels the returned jobs.
     */
    fun watchLost(target: SavedSshTarget, scope: CoroutineScope, onLost: () -> Unit): List<Job> {
        val runtime = target.runtime
        return listOf(
            scope.launch(Dispatchers.Unconfined) { profiles.collect { if (!isAvailable(target)) onLost() } },
            scope.launch(Dispatchers.Unconfined) { hidden.collect { if (!isAvailable(target)) onLost() } },
            scope.launch(Dispatchers.Unconfined) { runtime.connections.connections.collect { if (!isAvailable(target)) onLost() } },
            scope.launch(Dispatchers.Unconfined) { runtime.tunnels.state.collect { if (!isAvailable(target)) onLost() } },
            scope.launch(Dispatchers.Unconfined) { runtime.session.removed.collect { if (it) onLost() } },
            scope.launch(Dispatchers.Unconfined) { runtime.tunnels.targetPresent(target.tunnel).collect { if (!it) onLost() } },
        )
    }

    /**
     * The connector for the target's stored login when it connects without the dialog; null when the login or
     * host trust needs setup in the ordinary SSH UI. Reads only whether a password is kept, never the password.
     */
    fun connector(target: SavedSshTarget): SshConnector? {
        val saved = target.connection
        val (user, auth) = directSession(target) ?: return null
        return SshConnector(target.profile, target.runtime.hostKeys, saved.device, saved.port, target.tunnel.localPort, user, auth, env)
    }

    /** The user and authentication of the target's stored login when it connects without the dialog; null when it needs setup in the ordinary SSH UI. */
    fun directSession(target: SavedSshTarget): Pair<String, SshAuth>? {
        val saved = target.connection
        val login = directLogin(target.profile, target.runtime, saved.device, saved.port, saved.sshUser) ?: return null
        return login.user to login.method.toAuth(remember = true)
    }

    /**
     * Whether [profile] is known and loaded and has no saved SSH connection to [device]:[port] any more (removed): not merely a tunnel that is off, and
     * never while the profiles are still loading.
     */
    fun connectionGone(profile: String, device: String, port: Int): Boolean {
        val state = profiles.value
        if (!state.loaded || state.index.profiles.none { it.id == profile }) return false
        val runtime = state.runtimes[profile] ?: return false
        return runtime.connections.connections.value.none { it.kind == PortKind.SSH && it.device == device && it.port == port }
    }

    /** The id of the saved SSH connection of [profile] to [device]:[port], or null (a remembered session of the assistant finds its tunnel again by it). */
    fun savedConnectionAt(profile: String, device: String, port: Int): String? =
        profiles.value.runtimes[profile]?.connections?.connections?.value?.firstOrNull { it.kind == PortKind.SSH && it.device == device && it.port == port }?.id

    /** The remembered login of [device]:[port] as [user] when it can be used without the dialog, else null. */
    fun directLogin(profile: String, device: String, port: Int, user: String): SshLogin? =
        profiles.value.runtimes[profile]?.let { directLogin(profile, it, device, port, user) }

    private fun directLogin(profile: String, runtime: ProfileRuntime, device: String, port: Int, user: String): SshLogin? {
        // Only what was chosen here before: the first time, the dialog asks.
        val login = runtime.sshLogins.stored(device, port)?.copy(user = user) ?: return null
        val saved = env.passwords.has(profile, device, port, login.user)
        return login.takeIf { connectsDirectly(it, saved, env.refusals.get(profile, device, port, login.user), login.user) }
    }

    private fun sameTunnel(a: Tunnel, b: Tunnel) = a.key == b.key && a.grantId == b.grantId && a.localPort == b.localPort
    private fun tunnelReady(t: Tunnel) = t.state.status in setOf(TunnelStatus.DIRECT, TunnelStatus.RELAYED, TunnelStatus.TRYING) &&
        !t.state.waitingForPeer && !t.state.waitingForDevice && !t.state.deviceMissing
}
