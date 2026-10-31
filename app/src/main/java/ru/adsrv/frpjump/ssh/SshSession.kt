package ru.adsrv.frpjump.ssh

import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import android.util.Log
import com.termux.terminal.RemoteTerminal
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException

/** How a session logs in. A password lives only in this object (and, if ticked, sealed on disk). */
sealed interface SshAuth {
    data object Key : SshAuth

    /** [typed] null means the remembered one, unsealed when the session logs in (off the main thread). */
    class Password(val typed: String?, val remember: Boolean) : SshAuth {
        override fun toString() = "Password(typed=${typed != null}, remember=$remember)"
    }
}

sealed interface SshState {
    data object Connecting : SshState

    /** First connection to this device and port: the owner decides whether to trust [presented]. */
    data class HostKeyUnknown(val presented: PinnedHostKey) : SshState

    /** The device shows another key than the pinned one: blocked until the owner forgets the old key. */
    data class HostKeyChanged(val pinned: PinnedHostKey, val presented: PinnedHostKey) : SshState

    data object Open : SshState

    /**
     * The connection dropped; [attempt] is the try under way ([trying]) or
     * the last one, waiting for the next. The terminal keeps its text and
     * takes no keys.
     */
    data class Reconnecting(val attempt: Int, val trying: Boolean) : SshState

    /** Closed, by either side or by a failure; the terminal keeps its text. */
    data class Ended(val message: String) : SshState
}

/** What a session needs from the app, the same for every profile. */
class SshEnvironment(
    val keys: SshKeyStore,
    val passwords: SshPasswords,
    /** The comment of the app's own key, e.g. `frp-jump@<phone>`. */
    val keyComment: () -> String,
    /** The last refusal of each login, shared by every screen that logs in. */
    val refusals: LoginRefusals = LoginRefusals(),
    /** The timers of auto-reconnect. */
    val reconnectClock: ReconnectClock = ExecutorReconnectClock(),
)

/**
 * One SSH shell to `127.0.0.1:<localPort>`, the tunnel to [device]:[port]
 * of [profile], whose pinned keys are [hostKeys].
 * Lives in [SshSessions], not in a screen: leaving the terminal keeps it.
 * Construct on the main thread (the terminal binds to the main looper).
 */
class SshSession(
    val id: Int,
    val profile: String,
    val hostKeys: HostKeyStore,
    val device: String,
    val port: Int,
    val localPort: Int,
    val user: String,
    auth: SshAuth,
    private val env: SshEnvironment,
    private val scope: CoroutineScope,
    terminalClient: TerminalSessionClient,
    /** Its place among the open sessions to the same user, device and port, in the order they were opened; kept while it lives. */
    val number: Int = 1,
) {
    val terminal = RemoteTerminal(terminalClient)
    private val _state = MutableStateFlow<SshState>(SshState.Connecting)
    val state: StateFlow<SshState> = _state

    val title get() = "$user@$device"

    /**
     * Back after a drop with a fresh shell (no tmux yet: what ran before
     * ended with the connection): the bar says so until the owner types.
     */
    private val _newShell = MutableStateFlow(false)
    val newShell: StateFlow<Boolean> = _newShell

    /**
     * The owner typed (keys, extra keys, a snippet, a paste): the
     * "new shell" bar has said its part. Not called for the terminal's own
     * replies to the remote side. Main thread.
     */
    fun typed() {
        _newShell.value = false
    }

    @Volatile
    private var client: SSHClient? = null

    @Volatile
    private var shell: Session.Shell? = null

    @Volatile
    private var closedByUser = false

    /** The device refused [auth]: another session with it would only be refused again. */
    @Volatile
    var authRefused = false
        private set

    /** How it logs in; Reconnect uses it too. */
    @Volatile
    var auth: SshAuth = auth
        private set

    /** Tries again after a drop; see [SshReconnect]. */
    private val reconnect = SshReconnect(env.reconnectClock, attempt = ::retry, giveUp = ::onGiveUp)

    /** Why the connection went last, for the bar and for Ended when the tries run out. */
    @Volatile
    private var dropReason: String = ""

    /** The shell went with the connection: the next one to open is a new shell, and says so. */
    @Volatile
    private var dropped = false

    /** "Use key login" worked for this login: Reconnect uses the key from now on. */
    fun useKey() {
        auth = SshAuth.Key
    }

    /**
     * Starts connecting. [retrying]: Reconnect after the tries of a dropped
     * session ran out, so a failure to connect keeps trying as after a drop.
     */
    fun connect(retrying: Boolean = false) {
        terminal.note(Texts.get(R.string.ssh_note_connecting, "$user@$device:$port", "127.0.0.1:$localPort") + "\r\n")
        if (retrying) {
            dropReason = Texts.get(R.string.ssh_connection_closed)
            _state.value = SshState.Reconnecting(1, trying = false)
            reconnect.start()
            return
        }
        _state.value = SshState.Connecting
        scope.launch(Dispatchers.IO) { run(retrying = false) }
    }

    /** The tries after a drop ran out ([RECONNECT_GIVE_UP_MS]): Reconnect starts them again. */
    @Volatile
    var gaveUp = false
        private set

    /** "Now" on the reconnecting bar: try at once. */
    fun reconnectNow() = reconnect.now()

    /**
     * [on]: the tab is closed (or its tunnel disconnected) with Undo on
     * screen: no tries meanwhile. Off: back by Undo, still dropped, it tries
     * again at once.
     */
    fun hold(on: Boolean) = reconnect.hold(on)

    /** The default network came back or changed: a reconnecting session tries at once. */
    fun networkReturned() = reconnect.networkReturned()

    private fun retry(attempt: Int) {
        // Ended (or closed) meanwhile: no try, and the loop has nothing more to do.
        if (closedByUser || !whileReconnecting { SshState.Reconnecting(attempt, trying = true) }) {
            reconnect.stop()
            return
        }
        scope.launch(Dispatchers.IO) { run(retrying = true) }
    }

    /** Sets the state to [next] of the current one only while it is [SshState.Reconnecting]: never over Ended. */
    private fun whileReconnecting(next: (SshState.Reconnecting) -> SshState): Boolean {
        while (true) {
            val now = _state.value as? SshState.Reconnecting ?: return false
            if (_state.compareAndSet(now, next(now))) return true
        }
    }

    private fun onGiveUp() {
        gaveUp = true
        end(dropReason)
    }

    /** "Trust": pin the key shown at first use and connect again. */
    fun trustHostKey() {
        val unknown = _state.value as? SshState.HostKeyUnknown ?: return
        _state.value = SshState.Connecting
        scope.launch(Dispatchers.IO) {
            hostKeys.pin(device, port, unknown.presented)
            connect()
        }
    }

    /** "Forget the old key": drop the pin and connect again, which asks about the new key. */
    fun forgetOldHostKey() {
        if (_state.value !is SshState.HostKeyChanged) return
        _state.value = SshState.Connecting
        scope.launch(Dispatchers.IO) {
            hostKeys.forget(device, port)
            connect()
        }
    }

    /** Tells the remote side the terminal's size (SSH `window-change`). */
    fun resize(columns: Int, rows: Int) {
        val s = shell ?: return
        scope.launch(Dispatchers.IO) {
            try {
                s.changeWindowDimensions(columns, rows, 0, 0)
            } catch (e: IOException) {
                Log.w(TAG, "window-change failed: ${e.message}")
            }
        }
    }

    /**
     * The logged-in connection while the shell is open, for another channel
     * on it ("Use key login" runs its command there); null otherwise.
     */
    fun openClient(): SSHClient? =
        client?.takeIf { _state.value == SshState.Open && it.isConnected && it.isAuthenticated }

    fun close(reason: String = Texts.get(R.string.ssh_closed)) {
        closedByUser = true
        reconnect.stop()
        end(reason)
        val c = client ?: return
        scope.launch(Dispatchers.IO) { runCatching { c.disconnect() } }
    }

    /**
     * One connection: connect, log in, open the shell. [retrying]: a try of
     * the reconnect loop, which a failure answers instead of ending the tab;
     * a refused login or a host key to decide still stops it.
     */
    private suspend fun run(retrying: Boolean) {
        // The login as it is now: "Use key login" may have switched it to the key.
        val connector = SshConnector(profile, hostKeys, device, port, localPort, user, auth, env)
        val ssh = connector.newClient()
        client = ssh
        try {
            connector.connect(ssh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HostKeyRejected) {
            if (retrying && e.check != HostKeyCheck.Trusted) reconnect.stop()
            when (val check = e.check) {
                HostKeyCheck.Unknown -> {
                    _state.value = SshState.HostKeyUnknown(e.presented)
                    terminal.note(Texts.get(R.string.ssh_note_host_key_unknown, "$device:$port") + "\r\n")
                }
                is HostKeyCheck.Changed -> {
                    _state.value = SshState.HostKeyChanged(check.pinned, e.presented)
                    terminal.note("\r\n" + Texts.get(R.string.ssh_note_host_key_changed, "$device:$port") + "\r\n")
                }
                HostKeyCheck.Trusted -> fail(Texts.get(R.string.ssh_cannot_connect, e.message ?: e.javaClass.simpleName), retrying)
            }
            return
        } catch (e: Exception) {
            fail(Texts.get(R.string.ssh_cannot_connect, e.message ?: e.javaClass.simpleName), retrying)
            return
        }
        if (closedByUser) {
            runCatching { ssh.disconnect() }
            return
        }
        try {
            connector.authenticate(ssh)
            val session = ssh.startSession()
            val columns = terminal.columns
            val rows = terminal.rows
            session.allocatePTY(TERM, columns, rows, 0, 0, emptyMap())
            val started = session.startShell()
            if (closedByUser) {
                runCatching { ssh.disconnect() }
                return
            }
            shell = started
            if (dropped) {
                dropped = false
                // Nothing pretends to continue: back to the normal screen, a line under the old text.
                terminal.resetForNewShell("\r\n[" + Texts.get(R.string.ssh_new_shell) + "]\r\n")
                _newShell.value = true
            }
            reconnect.succeeded()
            _state.value = SshState.Open
            // The screen may have resized the terminal while the PTY was being set up.
            if (terminal.columns != columns || terminal.rows != rows) resize(terminal.columns, terminal.rows)
            terminal.pump(started.inputStream, started.outputStream) { error -> onShellEnded(ssh, started, error) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserAuthException) {
            if (authFailedByTransport(e)) {
                // The connection went during the login: not a refusal, a failed try.
                runCatching { ssh.disconnect() }
                fail(Texts.get(R.string.ssh_failed, e.message ?: e.javaClass.simpleName), retrying)
                return
            }
            authRefused = true
            // Refused now: another try would only be refused again; Change login, as on a first connect.
            reconnect.stop()
            runCatching { ssh.disconnect() }
            fail(connector.authFailure())
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            fail(Texts.get(R.string.ssh_failed, e.message ?: e.javaClass.simpleName), retrying)
        }
    }

    private fun onShellEnded(ssh: SSHClient, started: Session.Shell, error: IOException?) {
        val channel = started as? SessionChannel
        val status = channel?.exitStatus
        val signal = channel?.exitSignal != null
        runCatching { ssh.disconnect() }
        if (closedByUser) return
        val reason = when {
            error != null -> Texts.get(R.string.ssh_connection_lost, error.message ?: error.javaClass.simpleName)
            status != null -> Texts.get(R.string.ssh_logged_out, status)
            else -> Texts.get(R.string.ssh_connection_closed)
        }
        if (shellDropped(status, signal)) drop(reason) else end(reason)
    }

    /** The connection went under the shell: keep the tab and its text, take no keys, and try again. */
    private fun drop(reason: String) {
        if (reconnect.running || !_state.compareAndSet(SshState.Open, SshState.Reconnecting(1, trying = false))) return
        shell = null
        _newShell.value = false
        dropReason = reason
        dropped = true
        terminal.pause()
        terminal.note("\r\n[$reason]\r\n")
        reconnect.start()
    }

    /** A failure to connect or log in: during a reconnect, one more failed try; otherwise the end. */
    private fun fail(message: String, retrying: Boolean = false) {
        if (retrying && reconnect.running && !closedByUser) {
            dropReason = message
            whileReconnecting { it.copy(trying = false) }
            reconnect.failed()
        } else {
            end(message)
        }
    }

    private fun end(message: String) {
        if (_state.value is SshState.Ended) return
        _state.value = SshState.Ended(message)
        reconnect.stop()
        _newShell.value = false
        shell = null
        terminal.finish()
        terminal.note("\r\n[$message]\r\n")
    }

    private companion object {
        const val TAG = "SshSession"
        const val TERM = "xterm-256color"
    }
}
