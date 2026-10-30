package ru.adsrv.frpjump.ssh

import android.util.Log
import com.termux.terminal.RemoteTerminal
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.IOException
import java.security.PublicKey

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
        terminal.note("Connecting to $user@$device:$port (127.0.0.1:$localPort)…\r\n")
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

    fun close(reason: String = "Closed.") {
        closedByUser = true
        end(reason)
        val c = client ?: return
        scope.launch(Dispatchers.IO) { runCatching { c.disconnect() } }
    }

    private suspend fun run() {
        val verifier = PinningVerifier(hostKeys, device, port)
        val ssh = SSHClient(DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE })
        ssh.connectTimeout = CONNECT_TIMEOUT_MS
        ssh.addHostKeyVerifier(verifier)
        client = ssh
        try {
            ssh.connect(LOOPBACK, localPort)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            val rejected = verifier.rejected
            when (val check = rejected?.first) {
                HostKeyCheck.Unknown -> {
                    _state.value = SshState.HostKeyUnknown(rejected.second)
                    terminal.note("The host key of $device:$port is not known yet.\r\n")
                }
                is HostKeyCheck.Changed -> {
                    _state.value = SshState.HostKeyChanged(check.pinned, rejected.second)
                    terminal.note("\r\nWARNING: the host key of $device:$port has changed. Not connecting.\r\n")
                }
                else -> fail("Cannot connect: ${e.message ?: e.javaClass.simpleName}")
            }
            return
        }
        if (closedByUser) {
            runCatching { ssh.disconnect() }
            return
        }
        try {
            ssh.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
            authenticate(ssh)
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
            fail(authFailure())
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            fail("SSH failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun authenticate(ssh: SSHClient) {
        when (auth) {
            SshAuth.Key -> {
                val providers = env.keys.identities(env.keyComment()).mapNotNull { identity ->
                    try {
                        ssh.loadKeys(identity.privateKey, null, identity.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) })
                    } catch (e: IOException) {
                        Log.w(TAG, "cannot load an SSH key: ${e.message}")
                        null
                    }
                }
                ssh.authPublickey(user, providers)
            }
            is SshAuth.Password -> {
                val password = auth.typed ?: env.passwords.get(profile, device, port, user)
                    ?: throw UserAuthException("No remembered password for $user")
                ssh.authPassword(user, password)
                if (!auth.remember) {
                    env.passwords.forget(profile, device, port, user)
                } else if (auth.typed != null) {
                    env.passwords.remember(profile, device, port, user, password)
                }
            }
        }
    }

    private fun authFailure(): String = when (auth) {
        SshAuth.Key -> "The device did not accept this phone's key for \"$user\". " +
            "Add the public key from Settings to ~/.ssh/authorized_keys there, or log in with a password."
        is SshAuth.Password -> {
            // A remembered password that no longer works is not offered again.
            env.passwords.forget(profile, device, port, user)
            "Wrong user name or password for \"$user\"."
        }
    }

    private fun onShellEnded(ssh: SSHClient, started: Session.Shell, error: IOException?) {
        val status = (started as? SessionChannel)?.exitStatus
        runCatching { ssh.disconnect() }
        if (closedByUser) return
        end(
            when {
                error != null -> "Connection lost: ${error.message ?: error.javaClass.simpleName}"
                status != null -> "Logged out (exit $status)."
                else -> "Connection closed."
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

    private class PinningVerifier(
        private val store: HostKeyStore,
        private val device: String,
        private val port: Int,
    ) : HostKeyVerifier {
        /** What was refused and why, for the prompt. */
        @Volatile
        var rejected: Pair<HostKeyCheck, PinnedHostKey>? = null

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val presented = PinnedHostKey(KeyType.fromKey(key).toString(), sshFingerprint(Buffer.PlainBuffer().putPublicKey(key).compactData))
            val check = store.check(device, this.port, presented)
            if (check == HostKeyCheck.Trusted) return true
            rejected = check to presented
            return false
        }

        /** Prefer the pinned key's algorithm, so a device with several keys shows the same one. */
        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
            store.pinned(device, this.port)?.let { listOf(it.type) }.orEmpty()
    }

    private companion object {
        const val TAG = "SshSession"
        const val LOOPBACK = "127.0.0.1"
        const val TERM = "xterm-256color"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val KEEPALIVE_SECONDS = 20
    }
}
