package ru.adsrv.frpjump.ui.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.connections.HeldPort
import ru.adsrv.frpjump.connections.LocalPortContext
import ru.adsrv.frpjump.connections.lastOnItsTunnel
import ru.adsrv.frpjump.connections.PinRefused
import ru.adsrv.frpjump.connections.localPortProblemWords
import ru.adsrv.frpjump.connections.pinProblemOnSave
import ru.adsrv.frpjump.connections.pinToRelease
import ru.adsrv.frpjump.connections.portPins
import ru.adsrv.frpjump.files.FilesTarget
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.ProfileRuntime
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.ports.profilePortKey
import ru.adsrv.frpjump.ports.profilePortPrefix
import ru.adsrv.frpjump.ports.releasePin
import ru.adsrv.frpjump.ports.reservePins
import ru.adsrv.frpjump.ssh.KeyInstallTarget
import ru.adsrv.frpjump.ssh.KeyInstaller
import ru.adsrv.frpjump.ssh.LoginRefusal
import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.SshLogin
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ssh.connectsDirectly
import ru.adsrv.frpjump.ssh.offersSavedPassword
import ru.adsrv.frpjump.tunnel.OpenThrough
import ru.adsrv.frpjump.tunnel.SessionOn
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.tunnel.openOnProfile
import ru.adsrv.frpjump.tunnel.openThrough
import ru.adsrv.frpjump.ui.ssh.KeyLoginFlow
import ru.adsrv.frpjump.ui.userMessage
import java.io.IOException

/** How long "Use key login" waits for a tunnel it brought up. */
private const val TUNNEL_WAIT_MS = 45_000L

/**
 * What the device card and the Connections tab do with a connection, for
 * any profile: connect, disconnect, save, delete, log in over SSH. Storage
 * runs on the IO dispatcher; [scope] is the screen's view model's.
 */
class ConnectionActions(private val app: FrpJumpApp, private val scope: CoroutineScope) {
    private fun runtime(profile: String) = app.profiles.runtime(profile)

    /** "Use key login": in the app's scope, so it finishes when the screen goes. */
    val keyLogin = KeyLoginFlow(KeyInstaller(app.sshEnv, app.ssh), app.appScope)

    /** Starts "Use key login" for [row]'s user, device and port. Main thread. */
    fun startKeyLogin(row: ConnectionRow) {
        val runtime = runtime(row.profile) ?: return
        val c = row.connection
        val target = KeyInstallTarget(row.profile, runtime.hostKeys, c.device, c.port, sshLogin(row).user, runtime.sshLogins, localPort = {
            tunnelPort(row.profile, c.device, c.port)
        })
        keyLogin.start(target)
    }

    /**
     * The local port of the tunnel to [device]:[port], brought up when it is
     * down; throws [IOException] with why when it does not come up.
     */
    private suspend fun tunnelPort(profile: String, device: String, port: Int): Int {
        val runtime = runtime(profile) ?: throw IOException(Texts.get(R.string.profile_gone))
        val key = localPortKey(device, port)
        fun up(s: TunnelsState) = s.tunnels.firstOrNull { it.key == key }?.localPort
        up(runtime.tunnels.state.value)?.let { return it }
        if (runtime.tunnels.state.value.pending[key] == null) runtime.tunnels.connectAsync(device, port, PortKind.SSH)
        return withTimeoutOrNull(TUNNEL_WAIT_MS) { runtime.tunnels.state.mapNotNull { up(it) }.first() }
            ?: throw IOException(runtime.tunnels.state.value.errors[key] ?: Texts.get(R.string.files_tunnel_down))
    }

    /** The terminal sessions of every tunnel, for the SSH rows' counts. */
    val sessions: Flow<List<SessionOn>> = app.ssh.through

    fun connect(row: ConnectionRow) {
        val c = row.connection
        runtime(row.profile)?.tunnels?.connectAsync(c.device, c.port, c.kind)
    }

    fun disconnect(row: ConnectionRow) {
        runtime(row.profile)?.tunnels?.disconnectAsync(row.connection.device, row.connection.port)
    }

    /**
     * The live terminal sessions and open file screens that go when
     * [row]'s tunnel disconnects, or every tunnel when [row] is null: the
     * owner is asked before they are lost. Main thread.
     */
    fun openThrough(row: ConnectionRow?): OpenThrough =
        openThrough(openSessions(), openFiles(), row?.let { TunnelRef(it.profile, it.connection.device, it.connection.port) })

    /** [openThrough] for every tunnel of [profile]. Main thread. */
    fun openOnProfile(profile: String): OpenThrough = openOnProfile(openSessions(), openFiles(), profile)

    private fun openSessions() =
        app.ssh.sessions.value.map { SessionOn(TunnelRef(it.profile, it.device, it.port), it.state.value !is SshState.Ended) }

    private fun openFiles() = app.fileTargets.all().map { TunnelRef(it.profile, it.device, it.port) }

    /** "Retry direct" for [row]'s device: all its tunnels through the server try again, kept no more on this network. */
    fun retryDirect(row: ConnectionRow) {
        app.appScope.launch { app.directChoices.retryDirect(row.profile, row.connection.device) }
    }

    /** "Keep through the server" for [row]'s device on this network; says why when there is no network to tie it to. */
    fun keepThroughServer(row: ConnectionRow) {
        app.appScope.launch {
            if (!app.directChoices.keepThroughServer(row.profile, row.connection.device)) {
                app.notices.show(Texts.get(R.string.keep_no_network), NoticeKind.WARN, long = true)
            }
        }
    }

    /** Every tunnel of every profile; [onDone] once every server was asked (main thread). */
    fun disconnectAll(onDone: () -> Unit = {}) {
        app.appScope.launch {
            app.profiles.disconnectAll()
            onDone()
        }
    }

    /**
     * Every tunnel of [profile] (the power button on the Devices tab's bar);
     * a notice says how many went, or that the server was not told and they
     * may come back. [onDone] once the server was asked (main thread).
     */
    fun disconnectProfile(profile: String, onDone: () -> Unit = {}) {
        val tunnels = runtime(profile)?.tunnels ?: run {
            app.notices.show(Texts.get(R.string.profile_gone), NoticeKind.WARN)
            return onDone()
        }
        app.appScope.launch {
            // As the bar's chip counts them; none up yet (e.g. still being restored): no "0".
            val count = app.profiles.tunnelsByProfileNow()[profile] ?: 0
            if (tunnels.disconnectAll()) {
                val text = if (count > 0) Texts.get(R.string.tunnels_disconnected, count) else Texts.get(R.string.tunnels_disconnected_unknown)
                app.notices.show(text, NoticeKind.DONE)
            } else {
                app.notices.show(Texts.get(R.string.disconnect_all_failed), NoticeKind.WARN, long = true)
            }
            onDone()
        }
    }

    /**
     * Saves [connection] in [profile] (new, or [previous] changed in place)
     * and then, with [connectNow], connects it. A change that moves the last
     * connection off a port disconnects that port's tunnel. [onDone] gets
     * null, or what went wrong.
     */
    fun save(profile: String, connection: Connection, previous: Connection?, connectNow: Boolean, onDone: (String?) -> Unit) {
        val runtime = runtime(profile) ?: return onDone(Texts.get(R.string.profile_gone))
        scope.launch {
            val before = runtime.connections.connections.value
            val failure = try {
                withContext(Dispatchers.IO) {
                    // Under the shared map's lock, so two saves (two profiles) cannot both take one port:
                    // the pin is checked again against everything as it is now, and only then saved.
                    runtime.localPorts.update { ports ->
                        val now = localPortContext(profile)
                        pinProblemOnSave(profile, connection, now.pins, now.held)?.let { throw PinRefused(it) }
                        runtime.connections.save(connection)
                        // A pin given up (back to Auto, or another port) is free for others again...
                        pinToRelease(previous, runtime.connections.connections.value)?.let { (key, pin) ->
                            releasePin(ports, profilePortPrefix(profile) + key, pin)
                        }
                        // ...and a new one is reserved at once in the map all profiles share, so no other tunnel gets it, even while this one is off.
                        connection.localPort?.let { pin -> reservePins(ports, mapOf(profilePortKey(profile, connection.device, connection.port) to pin)) }
                    }
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: PinRefused) {
                localPortProblemWords(e.problem).let { (res, args) -> Texts.get(res, *args.toTypedArray()) }
            } catch (e: Exception) {
                e.userMessage()
            }
            if (failure == null && connectNow) runtime.tunnels.connectAsync(connection.device, connection.port, connection.kind)
            // No question about sessions on the old tunnel: the sheet locks kind and port while
            // that tunnel is up or changing (portLocked), and the device cannot be changed, so a
            // move only ever leaves a tunnel that is down.
            val moved = previous != null && (previous.device != connection.device || previous.port != connection.port)
            if (failure == null && moved && previous != null && lastOnItsTunnel(previous, before)) {
                val key = localPortKey(previous.device, previous.port)
                if (runtime.tunnels.state.value.tunnels.any { it.key == key }) runtime.tunnels.disconnectAsync(previous.device, previous.port)
            }
            onDone(failure)
        }
    }

    /**
     * Deletes [row]'s connection; its tunnel goes too when no other
     * connection of the profile uses it. [onDone] gets null, or what went wrong.
     */
    fun delete(row: ConnectionRow, onDone: (String?) -> Unit) {
        val runtime = runtime(row.profile) ?: return onDone(null)
        scope.launch {
            val all = runtime.connections.connections.value
            // Its explorer session, broker login and MQTT pins go with it.
            app.mqtt.closeConnection(row.profile, row.connection.id)
            val failure = try {
                withContext(Dispatchers.IO) {
                    runtime.connections.remove(row.connection.id)
                    app.mqtt.changeStored {
                        app.mqttLogins.forget(row.profile, row.connection.id)
                        app.mqttPins.forget(row.profile, row.connection.id)
                    }
                    // Its pin, unless another connection to that device and port still has it, is free again.
                    pinToRelease(row.connection, runtime.connections.connections.value)?.let { (key, pin) ->
                        runtime.localPorts.update { ports -> releasePin(ports, profilePortPrefix(row.profile) + key, pin) }
                    }
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.userMessage()
            }
            if (failure == null && row.active && lastOnItsTunnel(row.connection, all)) disconnect(row)
            onDone(failure)
        }
    }

    /**
     * What a pinned local port of [profile] is checked against: the pins of
     * every profile's connections and the local ports of the tunnels up now.
     * Main thread.
     */
    fun localPortContext(profile: String): LocalPortContext = localPortContext(profile, app.profiles.state.value.runtimes.values)

    /** [localPortContext] as it changes: the sheet's check follows tunnels coming up and pins saved elsewhere. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun localPortContexts(profile: String): Flow<LocalPortContext> = app.profiles.state.flatMapLatest { state ->
        val runtimes = state.runtimes.values
        if (runtimes.isEmpty()) {
            flowOf(LocalPortContext(profile))
        } else {
            combine(runtimes.flatMap { listOf(it.connections.connections, it.tunnels.state) }) { localPortContext(profile, runtimes) }
        }
    }

    private fun localPortContext(profile: String, runtimes: Collection<ProfileRuntime>): LocalPortContext {
        return LocalPortContext(
            profile = profile,
            pins = runtimes.flatMap { r -> portPins(r.id, r.connections.connections.value) },
            held = runtimes.flatMap { r -> r.tunnels.state.value.tunnels.map { HeldPort(r.id, it.device, it.port, it.localPort) } },
        )
    }

    /** Whether deleting [row] also disconnects its tunnel. */
    fun deleteDisconnects(row: ConnectionRow): Boolean =
        row.active && lastOnItsTunnel(row.connection, runtime(row.profile)?.connections?.connections?.value.orEmpty())

    /** The login dialog's start: the connection's user, the method used last on this port. */
    fun sshLogin(row: ConnectionRow): SshLogin {
        val c = row.connection
        val last = runtime(row.profile)?.sshLogins?.get(c.device, c.port) ?: SshLogin()
        return last.copy(user = c.sshUser)
    }

    /**
     * Only whether one is kept and worth offering: the password itself is
     * unsealed by the session, off the main thread. Not one just refused
     * (or unreadable) for that user, see [offersSavedPassword].
     */
    fun hasRememberedPassword(row: ConnectionRow, user: String): Boolean {
        return offersSavedPassword(refusalOf(row, user), user.trim(), user) {
            app.sshPasswords.has(row.profile, row.connection.device, row.connection.port, user.trim())
        }
    }

    /** Why the last login as [user] to [row]'s device and port was refused, if it was (terminal or files). */
    private fun refusalOf(row: ConnectionRow, user: String): LoginRefusal? =
        app.sshEnv.refusals.get(row.profile, row.connection.device, row.connection.port, user.trim())

    /**
     * The remembered login of [row] when it can be used without the dialog
     * (see [connectsDirectly]); null when the dialog must ask. Main thread.
     */
    fun loginAtOnce(row: ConnectionRow): SshLogin? {
        val c = row.connection
        // Only what was chosen here before: the first time, the dialog asks.
        val login = runtime(row.profile)?.sshLogins?.stored(c.device, c.port)?.copy(user = c.sshUser) ?: return null
        val saved = app.sshPasswords.has(row.profile, c.device, c.port, login.user)
        return login.takeIf { connectsDirectly(it, saved, refusalOf(row, login.user), login.user) }
    }

    /**
     * The session already open to [row]'s device and port in its profile:
     * the live one last among the tabs, else the last ended one (its
     * output and Reconnect are still there); null when there is none. Main thread.
     */
    fun openSession(row: ConnectionRow): Int? {
        val c = row.connection
        val mine = app.ssh.sessions.value.filter { it.profile == row.profile && it.device == c.device && it.port == c.port }
        return (mine.lastOrNull { it.state.value !is SshState.Ended } ?: mine.lastOrNull())?.id
    }

    /**
     * Remembers the method for this port and opens a session to [row]'s
     * tunnel; returns its id, or null when the profile is gone. An empty
     * password means "the remembered one". Main thread.
     */
    fun openSsh(row: ConnectionRow, user: String, method: SshAuthMethod, password: String, remember: Boolean): Int? =
        row.tunnel?.localPort?.let { openSshAt(row, it, user, method, password, remember) }

    /**
     * As [openSsh], also with the tunnel down ("Change login" from "⋮"): it
     * is brought up first, then [onOpened] gets the session; a tunnel that
     * does not come up says why. Main thread.
     */
    fun openSshWhenUp(row: ConnectionRow, user: String, method: SshAuthMethod, password: String, remember: Boolean, onOpened: (Int) -> Unit) {
        row.tunnel?.localPort?.let { port -> openSshAt(row, port, user, method, password, remember)?.let(onOpened); return }
        scope.launch {
            val port = try {
                tunnelPort(row.profile, row.connection.device, row.connection.port)
            } catch (e: IOException) {
                app.notices.show(e.message ?: Texts.get(R.string.files_tunnel_down), NoticeKind.WARN)
                return@launch
            }
            openSshAt(row, port, user, method, password, remember)?.let(onOpened)
        }
    }

    private fun openSshAt(row: ConnectionRow, localPort: Int, user: String, method: SshAuthMethod, password: String, remember: Boolean): Int? {
        val runtime = runtime(row.profile) ?: return null
        val c = row.connection
        val login = SshLogin(user.trim().ifEmpty { SshLogin.DEFAULT_USER }, method)
        scope.launch(Dispatchers.IO) { runtime.sshLogins.remember(c.device, c.port, login) }
        val auth = when (method) {
            SshAuthMethod.KEY -> SshAuth.Key
            SshAuthMethod.PASSWORD -> SshAuth.Password(password.ifEmpty { null }, remember)
        }
        return app.ssh.open(row.profile, runtime.hostKeys, c.device, c.port, localPort, login.user, auth).id
    }

    /** The file session kept on [row]'s tunnel (its screen left while a terminal is open there), for the route; null when none. Main thread. */
    fun keptFiles(row: ConnectionRow): Int? = app.files.kept(TunnelRef(row.profile, row.connection.device, row.connection.port))

    /**
     * Remembers the method for this port and makes a file screen's login
     * for [row]; returns its id for the route, or null when the profile is
     * gone. The tunnel need not be up: the screen waits for it. Main thread.
     */
    fun openFiles(row: ConnectionRow, user: String, method: SshAuthMethod, password: String, remember: Boolean): Int? {
        val runtime = runtime(row.profile) ?: return null
        val c = row.connection
        val login = SshLogin(user.trim().ifEmpty { SshLogin.DEFAULT_USER }, method)
        scope.launch(Dispatchers.IO) { runtime.sshLogins.remember(c.device, c.port, login) }
        val auth = when (method) {
            SshAuthMethod.KEY -> SshAuth.Key
            SshAuthMethod.PASSWORD -> SshAuth.Password(password.ifEmpty { null }, remember)
        }
        // A new password typed: the refusal of the old one says nothing about it.
        if (method == SshAuthMethod.PASSWORD && password.isNotEmpty()) app.sshEnv.refusals.clear(row.profile, c.device, c.port, login.user)
        return app.fileTargets.put(FilesTarget(row.profile, c.device, c.port, login.user, auth))
    }
}
