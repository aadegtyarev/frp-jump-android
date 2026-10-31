package ru.adsrv.frpjump.ui.connections

import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.connections.lastOnItsTunnel
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.SshLogin
import ru.adsrv.frpjump.ui.userMessage

/**
 * What the device card and the Connections tab do with a connection, for
 * any profile: connect, disconnect, save, delete, log in over SSH. Storage
 * runs on the IO dispatcher; [scope] is the screen's view model's.
 */
class ConnectionActions(private val app: FrpJumpApp, private val scope: CoroutineScope) {
    private fun runtime(profile: String) = app.profiles.runtime(profile)

    fun connect(row: ConnectionRow) {
        val c = row.connection
        runtime(row.profile)?.tunnels?.connectAsync(c.device, c.port, c.kind)
    }

    fun disconnect(row: ConnectionRow) {
        runtime(row.profile)?.tunnels?.disconnectAsync(row.connection.device, row.connection.port)
    }

    /** "Retry direct" for [row]'s device: all its tunnels through the server try again, kept no more on this network. */
    fun retryDirect(row: ConnectionRow) {
        app.appScope.launch { app.directChoices.retryDirect(row.profile, row.connection.device) }
    }

    /** "Keep through the server" for [row]'s device on this network; says why when there is no network to tie it to. */
    fun keepThroughServer(row: ConnectionRow) {
        app.appScope.launch {
            if (!app.directChoices.keepThroughServer(row.profile, row.connection.device)) {
                Toast.makeText(app, R.string.keep_no_network, Toast.LENGTH_LONG).show()
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
     * Saves [connection] in [profile] (new, or [previous] changed in place)
     * and then, with [connectNow], connects it. A change that moves the last
     * connection off a port disconnects that port's tunnel. [onDone] gets
     * null, or what went wrong.
     */
    fun save(profile: String, connection: Connection, previous: Connection?, connectNow: Boolean, onDone: (String?) -> Unit) {
        val runtime = runtime(profile) ?: return onDone(app.getString(R.string.profile_gone))
        scope.launch {
            val before = runtime.connections.connections.value
            val failure = try {
                withContext(Dispatchers.IO) { runtime.connections.save(connection) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.userMessage()
            }
            if (failure == null && connectNow) runtime.tunnels.connectAsync(connection.device, connection.port, connection.kind)
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
            val failure = try {
                withContext(Dispatchers.IO) { runtime.connections.remove(row.connection.id) }
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

    /** Whether deleting [row] also disconnects its tunnel. */
    fun deleteDisconnects(row: ConnectionRow): Boolean =
        row.active && lastOnItsTunnel(row.connection, runtime(row.profile)?.connections?.connections?.value.orEmpty())

    /** The login dialog's start: the connection's user, the method used last on this port. */
    fun sshLogin(row: ConnectionRow): SshLogin {
        val c = row.connection
        val last = runtime(row.profile)?.sshLogins?.get(c.device, c.port) ?: SshLogin()
        return last.copy(user = c.sshUser)
    }

    /** Only whether one is kept: the password itself is unsealed by the session, off the main thread. */
    fun hasRememberedPassword(row: ConnectionRow, user: String): Boolean =
        app.sshPasswords.has(row.profile, row.connection.device, row.connection.port, user.trim())

    /**
     * Remembers the method for this port and opens a session to [row]'s
     * tunnel; returns its id, or null when the profile is gone. An empty
     * password means "the remembered one". Main thread.
     */
    fun openSsh(row: ConnectionRow, user: String, method: SshAuthMethod, password: String, remember: Boolean): Int? {
        val runtime = runtime(row.profile) ?: return null
        val localPort = row.tunnel?.localPort ?: return null
        val c = row.connection
        val login = SshLogin(user.trim().ifEmpty { SshLogin.DEFAULT_USER }, method)
        scope.launch(Dispatchers.IO) { runtime.sshLogins.remember(c.device, c.port, login) }
        val auth = when (method) {
            SshAuthMethod.KEY -> SshAuth.Key
            SshAuthMethod.PASSWORD -> SshAuth.Password(password.ifEmpty { null }, remember)
        }
        return app.ssh.open(row.profile, runtime.hostKeys, c.device, c.port, localPort, login.user, auth).id
    }
}
