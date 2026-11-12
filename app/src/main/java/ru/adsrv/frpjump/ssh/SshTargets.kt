package ru.adsrv.frpjump.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 */
class SshTargets(
    private val profiles: StateFlow<ProfilesState>,
    private val hidden: StateFlow<Set<String>>,
    private val env: SshEnvironment,
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
        val login = directLogin(target.profile, target.runtime, saved.device, saved.port, saved.sshUser) ?: return null
        return SshConnector(target.profile, target.runtime.hostKeys, saved.device, saved.port, target.tunnel.localPort, login.user,
            login.method.toAuth(remember = true), env)
    }

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
