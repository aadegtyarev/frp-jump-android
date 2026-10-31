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
import ru.adsrv.frpjump.connections.asMqtt
import ru.adsrv.frpjump.connections.HeldPort
import ru.adsrv.frpjump.connections.LocalPortContext
import ru.adsrv.frpjump.connections.disconnectsAfterDelete
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
import ru.adsrv.frpjump.tunnel.SessionOn
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.ui.ssh.KeyLoginFlow
import ru.adsrv.frpjump.undo.PendingUndo
import ru.adsrv.frpjump.undo.TunnelDisconnects
import ru.adsrv.frpjump.restore.restoringProfiles
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
            tunnelPort(row.profile, c.device, c.port, c.kind)
        })
        keyLogin.start(target)
    }

    /**
     * The local port of the tunnel to [device]:[port], brought up for a
     * connection of [kind] (which names it to the server) when it is down;
     * throws [IOException] with why when it does not come up.
     */
    private suspend fun tunnelPort(profile: String, device: String, port: Int, kind: PortKind): Int {
        val runtime = runtime(profile) ?: throw IOException(Texts.get(R.string.profile_gone))
        val key = localPortKey(device, port)
        fun up(s: TunnelsState) = s.tunnels.firstOrNull { it.key == key }?.localPort
        // Used again while its disconnect waits for Undo: it stays up. Being disconnected: connect afresh after.
        if (!revive(profile, device, port)) app.disconnects.awaitDisconnected(TunnelRef(profile, device, port))
        up(runtime.tunnels.state.value)?.let { return it }
        if (runtime.tunnels.state.value.pending[key] == null) runtime.tunnels.connectAsync(device, port, kind)
        return withTimeoutOrNull(TUNNEL_WAIT_MS) { runtime.tunnels.state.mapNotNull { up(it) }.first() }
            ?: throw IOException(runtime.tunnels.state.value.errors[key] ?: Texts.get(R.string.files_tunnel_down))
    }

    /** The sessions of every tunnel, for the SSH rows' counts of terminals. */
    val sessions: Flow<List<SessionOn>> = app.sessions.through

    /** Connect; a tunnel whose disconnect waits for Undo is still up, and only stays so. */
    fun connect(row: ConnectionRow) {
        val c = row.connection
        if (revive(row.profile, c.device, c.port)) return
        val tunnels = runtime(row.profile)?.tunnels ?: return
        val ref = TunnelRef(row.profile, c.device, c.port)
        // Its disconnect is being done: connect once it is down, not into it (the tap is not lost).
        if (app.disconnects.disconnecting(ref)) {
            app.appScope.launch {
                app.disconnects.awaitDisconnected(ref)
                tunnels.connectAsync(c.device, c.port, c.kind)
            }
        } else {
            tunnels.connectAsync(c.device, c.port, c.kind)
        }
    }

    /** A tunnel whose disconnect waits for Undo, used again from its row: it stays up ([TunnelDisconnects.revive]). Main thread. */
    private fun revive(profile: String, device: String, port: Int): Boolean = app.disconnects.revive(TunnelRef(profile, device, port))

    /** Disconnect, with Undo ([TunnelDisconnects]): [row]'s tunnel shows down at once. Main thread. */
    fun disconnect(row: ConnectionRow) = app.disconnects.disconnect(listOf(TunnelRef(row.profile, row.connection.device, row.connection.port)))

    /** The tunnels of [profiles] up as the owner sees them: not those whose disconnect waits already. Main thread. */
    private fun shownTunnels(profiles: Collection<ProfileRuntime>): List<TunnelRef> =
        app.disconnects.shown(profiles.flatMap { r -> r.tunnels.state.value.tunnels.map { TunnelRef(r.id, it.device, it.port) } })

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

    /**
     * "Disconnect all": every tunnel of every profile up now, with Undo
     * ([TunnelDisconnects]); [onDone] at once. Profiles whose tunnels are
     * all still being restored after a restart have nothing on screen to
     * wait for: their servers are told now. With none up anywhere, every
     * server is told now, and [onDone] once it was asked (main thread).
     */
    fun disconnectAll(onDone: () -> Unit = {}) {
        val runtimes = app.profiles.state.value.runtimes
        val shown = shownTunnels(runtimes.values)
        if (shown.isNotEmpty()) {
            app.disconnects.disconnect(shown)
            val restoring = restoringProfiles(app.restoreRecord.profiles.value, runtimes.keys, app.profiles.tunnelsByProfileNow())
            restoring.mapNotNull(runtimes::get).forEach { r -> app.appScope.launch { r.tunnels.disconnectAll() } }
            return onDone()
        }
        app.appScope.launch {
            app.profiles.disconnectAll()
            onDone()
        }
    }

    /** Every tunnel of [profile] up now (the power button on the Devices tab's bar), with Undo. Main thread. */
    fun disconnectProfile(profile: String) {
        val runtime = runtime(profile) ?: return app.notices.show(Texts.get(R.string.profile_gone), NoticeKind.WARN)
        app.disconnects.disconnect(shownTunnels(listOf(runtime)))
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
     * Deletes [row]'s connection, with Undo: it leaves the screens at once
     * and is removed when the notice goes ([ru.adsrv.frpjump.undo.PendingUndo]).
     * Main thread.
     */
    fun delete(row: ConnectionRow) {
        if (!row.saved) return
        app.pendingUndo.enqueue(PendingUndo.connectionKey(row.profile, row.connection.id), commit = { commitDelete(row) })
    }

    /**
     * Removes [row]'s connection; its tunnel goes too when no other
     * connection of the profile uses it. In the app's scope: the screen may
     * be gone by now. A failure says why; the row is then shown again.
     */
    private suspend fun commitDelete(row: ConnectionRow) {
        val runtime = runtime(row.profile) ?: return
        // Its explorer session, broker login and MQTT pins go with it: first, so a
        // failure leaves the connection itself in place, as the message says.
        app.mqtt.closeConnection(row.profile, row.connection.id)
        val failure = try {
            withContext(Dispatchers.IO) {
                app.mqtt.changeStored {
                    app.mqttLogins.forget(row.profile, row.connection.id)
                    app.mqttPins.forget(row.profile, row.connection.id)
                }
                runtime.connections.remove(row.connection.id)
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
        if (failure != null) {
            app.notices.show(Texts.get(R.string.delete_failed, failure), NoticeKind.WARN, long = true)
            return
        }
        // As the list and the tunnel are now: of several deletes on one tunnel, the last one disconnects it.
        val key = localPortKey(row.connection.device, row.connection.port)
        val up = runtime.tunnels.state.value.tunnels.any { it.key == key }
        // Its Undo is gone already: at once, not with another one.
        if (disconnectsAfterDelete(row.connection, runtime.connections.connections.value, up)) runtime.tunnels.disconnectAsync(row.connection.device, row.connection.port)
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
                tunnelPort(row.profile, row.connection.device, row.connection.port, row.connection.kind)
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

    /**
     * The explorer session of [row]'s connection: the one open ("MQTT"
     * returns to it), else a new one through its tunnel, which must be up;
     * null when it is not, or the profile is gone. Main thread.
     */
    fun openMqtt(row: ConnectionRow): Int? {
        val c = row.connection
        revive(row.profile, c.device, c.port)
        // Going down with its tunnel: [openMqttWhenUp] waits for it and opens a new one.
        if (app.disconnects.disconnecting(TunnelRef(row.profile, c.device, c.port))) return null
        app.mqtt.find(row.profile, c.id)?.let { return it.id }
        val localPort = row.tunnel?.localPort ?: return null
        if (runtime(row.profile) == null) return null
        return app.mqtt.open(row.profile, c.id, c.device, c.port, localPort).id
    }

    /**
     * As [openMqtt], also with the tunnel down: it is brought up first, then
     * [onOpened] gets the session; a tunnel that does not come up says why.
     * Main thread.
     */
    fun openMqttWhenUp(row: ConnectionRow, onOpened: (Int) -> Unit) {
        openMqtt(row)?.let { return onOpened(it) }
        scope.launch {
            val localPort = try {
                tunnelPort(row.profile, row.connection.device, row.connection.port, row.connection.kind)
            } catch (e: IOException) {
                app.notices.show(e.message ?: Texts.get(R.string.files_tunnel_down), NoticeKind.WARN)
                return@launch
            }
            val c = row.connection
            onOpened(app.mqtt.find(row.profile, c.id)?.id ?: app.mqtt.open(row.profile, c.id, c.device, c.port, localPort).id)
        }
    }

    /**
     * "Open as MQTT" of a TCP connection: saved as the MQTT kind under the
     * same id (allowed with the tunnel up: the wire protocol stays `tcp`),
     * then its explorer opens as [openMqttWhenUp] does. A save that fails
     * says why and opens nothing. Main thread.
     */
    fun openAsMqtt(row: ConnectionRow, onOpened: (Int) -> Unit) {
        val mqtt = row.connection.asMqtt()
        save(row.profile, mqtt, previous = row.connection, connectNow = false) { failure ->
            if (failure != null) {
                app.notices.show(failure, NoticeKind.WARN)
            } else {
                openMqttWhenUp(row.copy(connection = mqtt), onOpened)
            }
        }
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
        revive(row.profile, c.device, c.port)
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
