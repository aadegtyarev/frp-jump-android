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

    /** Closed, by either side or by a failure; the terminal keeps its text. */
    data class Ended(val message: String) : SshState
}

/** What a session needs from the app, the same for every profile. */
class SshEnvironment(
    val keys: SshKeyStore,
    val passwords: SshPasswords,
    /** The comment of the app's own key, e.g. `frp-jump@<phone>`. */
    val keyComment: () -> String,
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
    val auth: SshAuth,
    private val env: SshEnvironment,
    private val scope: CoroutineScope,
    terminalClient: TerminalSessionClient,
) {
    val terminal = RemoteTerminal(terminalClient)
    private val _state = MutableStateFlow<SshState>(SshState.Connecting)
    val state: StateFlow<SshState> = _state

    val title get() = "$user@$device"

    @Volatile
    private var client: SSHClient? = null

    @Volatile
    private var shell: Session.Shell? = null

    @Volatile
    private var closedByUser = false

    fun connect() {
        _state.value = SshState.Connecting
        terminal.note(Texts.get(R.string.ssh_note_connecting, "$user@$device:$port", "127.0.0.1:$localPort") + "\r\n")
        scope.launch(Dispatchers.IO) { run() }
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

    fun close(reason: String = Texts.get(R.string.ssh_closed)) {
        closedByUser = true
        end(reason)
        val c = client ?: return
        scope.launch(Dispatchers.IO) { runCatching { c.disconnect() } }
    }

    private val connector = SshConnector(profile, hostKeys, device, port, localPort, user, auth, env)

    private suspend fun run() {
        val ssh = connector.newClient()
        client = ssh
        try {
            connector.connect(ssh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HostKeyRejected) {
            when (val check = e.check) {
                HostKeyCheck.Unknown -> {
                    _state.value = SshState.HostKeyUnknown(e.presented)
                    terminal.note(Texts.get(R.string.ssh_note_host_key_unknown, "$device:$port") + "\r\n")
                }
                is HostKeyCheck.Changed -> {
                    _state.value = SshState.HostKeyChanged(check.pinned, e.presented)
                    terminal.note("\r\n" + Texts.get(R.string.ssh_note_host_key_changed, "$device:$port") + "\r\n")
                }
                HostKeyCheck.Trusted -> fail(Texts.get(R.string.ssh_cannot_connect, e.message ?: e.javaClass.simpleName))
            }
            return
        } catch (e: Exception) {
            fail(Texts.get(R.string.ssh_cannot_connect, e.message ?: e.javaClass.simpleName))
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
            _state.value = SshState.Open
            // The screen may have resized the terminal while the PTY was being set up.
            if (terminal.columns != columns || terminal.rows != rows) resize(terminal.columns, terminal.rows)
            terminal.pump(started.inputStream, started.outputStream) { error -> onShellEnded(ssh, started, error) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserAuthException) {
            runCatching { ssh.disconnect() }
            fail(connector.authFailure())
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            fail(Texts.get(R.string.ssh_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun onShellEnded(ssh: SSHClient, started: Session.Shell, error: IOException?) {
        val status = (started as? SessionChannel)?.exitStatus
        runCatching { ssh.disconnect() }
        if (closedByUser) return
        end(
            when {
                error != null -> Texts.get(R.string.ssh_connection_lost, error.message ?: error.javaClass.simpleName)
                status != null -> Texts.get(R.string.ssh_logged_out, status)
                else -> Texts.get(R.string.ssh_connection_closed)
            },
        )
    }

    private fun fail(message: String) = end(message)

    private fun end(message: String) {
        if (_state.value is SshState.Ended) return
        _state.value = SshState.Ended(message)
        shell = null
        terminal.finish()
        terminal.note("\r\n[$message]\r\n")
    }

    private companion object {
        const val TAG = "SshSession"
        const val TERM = "xterm-256color"
    }
}
