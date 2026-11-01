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
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.util.concurrent.TimeUnit

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
    /** Names of the tabs' tmux sessions. */
    val tmuxNames: TmuxNames,
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
    /** The tmux session of the tab it replaces (Reconnect), to attach to again; null for a new tab. */
    tmuxName: String? = null,
    /** The tab it replaces had tmux fail to start: plain shells only. */
    noTmux: Boolean = false,
    /** Back on the tab's tmux session after a drop or Reconnect: a short notice says so. */
    private val onReattached: () -> Unit = {},
    /**
     * The first tab of a connection with none in the app: after login it
     * lists the device's sessions of this install and hands them here
     * (see [SshSessions]); what this returns, if anything, is the tab's own.
     */
    pickUp: ((SshSession, List<String>) -> String?)? = null,
    /** Picked up after a kill: the first attach says nothing ("Restored N sessions" does). */
    quietAttach: Boolean = false,
) {
    val terminal = RemoteTerminal(terminalClient)

    /** The tab's tmux session; see [TabTmux]. */
    val tmux = TabTmux(env.tmuxNames, tmuxName, noTmux)

    /** When the current tmux channel started ([System.nanoTime]), for [tmuxFailedToStart]. */
    @Volatile
    private var channelStartedAt = 0L

    private val copyMode = CopyModeGuard()

    /** Still to look for sessions to pick up; done once, on the first login that gets that far. */
    @Volatile
    private var pickUp = pickUp

    /** The next re-attach is the pick-up's: no "Session restored" of its own. */
    @Volatile
    private var quietAttach = quietAttach

    init {
        terminal.beforeWrite = { bytes, count -> if (copyMode.beforeWrite(bytes, count)) leaveCopyMode() }
    }
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
        copyMode.ownerTyped()
    }

    /**
     * The owner's paste of [text], as decided by [planPaste]: typed first, so
     * it does not land in tmux's copy mode (see CopyModeGuard); bracketed if
     * the remote side asked for it. Main thread.
     */
    fun paste(text: String) {
        typed()
        terminal.paste(text)
    }

    @Volatile
    private var client: SSHClient? = null

    @Volatile
    private var shell: SessionChannel? = null

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

    /**
     * Closes the tab's connection. [endTmux]: the owner closed the tab for
     * good (its Undo is gone), so its tmux session ends too, when the
     * connection is up to say so; otherwise (a tunnel closed, a dropped
     * connection) the session stays on the device.
     */
    fun close(reason: String = Texts.get(R.string.ssh_closed), endTmux: Boolean = false) {
        val wasOpen = _state.value == SshState.Open
        closedByUser = true
        reconnect.stop()
        end(reason)
        val c = client ?: return
        val kill = tmuxToKill(endTmux, wasOpen && c.isConnected && c.isAuthenticated, tmux.inUse, tmux.assigned)
        scope.launch(Dispatchers.IO) {
            if (kill != null) killTmux(c, kill)
            runCatching { c.disconnect() }
        }
    }

    /** Best effort: a failure is only logged, the tab is gone either way and the session can be ended by hand. */
    private fun killTmux(ssh: SSHClient, name: String) {
        try {
            ssh.startSession().use { s ->
                val command = s.exec(tmuxKillCommand(name))
                command.join(TMUX_EXEC_TIMEOUT_S, TimeUnit.SECONDS)
                if (command.exitStatus != 0) Log.w(TAG, "tmux kill-session ended with ${command.exitStatus}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "tmux kill-session failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Whether the device has tmux and the tab's session [name] still runs;
     * see [tmuxProbeCommand]. A probe that fails on a connection still up
     * (exec refused, no answer in time) counts as no tmux: a plain shell, as
     * before. A connection gone fails the try as any other failure.
     */
    private fun probeTmux(ssh: SSHClient, name: String): TmuxProbe =
        try {
            ssh.startSession().use { s ->
                val command = s.exec(tmuxProbeCommand(name))
                command.join(TMUX_EXEC_TIMEOUT_S, TimeUnit.SECONDS)
                // join() may come back with the channel still open: no answer is no answer.
                if (command.isOpen) throw IOException("tmux probe did not finish")
                parseTmuxProbe(readUpTo(command.inputStream, PROBE_MAX_BYTES))
            }
        } catch (e: IOException) {
            if (!ssh.isConnected) throw e
            Log.w(TAG, "tmux probe failed: ${e.javaClass.simpleName}")
            TmuxProbe.ABSENT
        }

    /**
     * This install's tmux sessions on the device, by number ([ownTmuxSessions]).
     * As with the probe, a listing that fails on a connection still up
     * counts as none: the tab opens as without a pick-up.
     */
    private fun listOwnTmux(ssh: SSHClient): List<String> =
        try {
            ownTmuxSessions(execOutput(ssh, TMUX_LIST_COMMAND), env.tmuxNames.install)
        } catch (e: IOException) {
            if (!ssh.isConnected) throw e
            Log.w(TAG, "tmux list-sessions failed: ${e.javaClass.simpleName}")
            emptyList()
        }

    /**
     * For a tap on the command line under tmux: whether the pane shows the
     * shell's command line (see [parseTapToShell]), after one exec round
     * trip. [answer] runs on a background thread; no tmux, no open
     * connection, a failed exec or an answer later than [TAP_ANSWER_MAX_MS]
     * (the screen has moved on) is false.
     */
    fun askTapToShell(answer: (Boolean) -> Unit) {
        val name = tmux.assigned
        val c = openClient()
        if (!tmux.inUse || name == null || c == null) {
            answer(false)
            return
        }
        val asked = System.nanoTime()
        scope.launch(Dispatchers.IO) {
            val shell = try {
                parseTapToShell(execOutput(c, tmuxTapStateCommand(name)))
            } catch (e: Exception) {
                Log.w(TAG, "tmux pane state failed: ${e.javaClass.simpleName}")
                false
            }
            answer(shell && (System.nanoTime() - asked) / 1_000_000 <= TAP_ANSWER_MAX_MS)
        }
    }

    /** See [CopyModeGuard]; on the terminal's writer thread, which it holds for one round trip at most. */
    private fun leaveCopyMode() {
        val name = tmux.assigned ?: return
        val c = openClient() ?: return
        try {
            c.startSession().use { s -> s.exec(tmuxLeaveModeCommand(name)).join(TMUX_EXEC_TIMEOUT_S, TimeUnit.SECONDS) }
        } catch (e: Exception) {
            Log.w(TAG, "leaving tmux copy mode failed: ${e.javaClass.simpleName}")
        }
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
            pickUp?.let { pick ->
                if (!tmux.disabled && tmux.assigned == null) {
                    val found = listOwnTmux(ssh)
                    if (pick(this, found) != null) quietAttach = true
                }
                pickUp = null
            }
            // One short exec first: with tmux the tab's channel attaches to (or creates) its session.
            val name = tmux.name()
            val probe = if (tmux.disabled) TmuxProbe.ABSENT else probeTmux(ssh, name)
            val channel = ssh.startSession() as SessionChannel
            val columns = terminal.columns
            val rows = terminal.rows
            channel.allocatePTY(TERM, columns, rows, 0, 0, emptyMap())
            if (probe == TmuxProbe.ABSENT) channel.startShell() else channel.exec(tmuxAttachCommand(name))
            if (closedByUser) {
                runCatching { ssh.disconnect() }
                return
            }
            tmux.inUse = probe != TmuxProbe.ABSENT
            channelStartedAt = System.nanoTime()
            copyMode.active = tmux.inUse
            shell = channel
            when (resumed(dropped, probe)) {
                // Nothing pretends to continue: back to the normal screen, a line under the old text.
                Resumed.NEW_SHELL -> {
                    terminal.resetForNewShell("\r\n[" + Texts.get(R.string.ssh_new_shell) + "]\r\n")
                    _newShell.value = true
                }
                // tmux redraws the screen as it is now: no text of the app's in it.
                Resumed.REATTACHED -> if (!quietAttach) onReattached()
                Resumed.FIRST -> Unit
            }
            quietAttach = false
            dropped = false
            reconnect.succeeded()
            _state.value = SshState.Open
            // The screen may have resized the terminal while the PTY was being set up.
            if (terminal.columns != columns || terminal.rows != rows) resize(terminal.columns, terminal.rows)
            terminal.pump(channel.inputStream, channel.outputStream) { error -> onShellEnded(ssh, channel, error) }
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

    private fun onShellEnded(ssh: SSHClient, channel: SessionChannel, error: IOException?) {
        val status = channel.exitStatus
        val signal = channel.exitSignal != null
        runCatching { ssh.disconnect() }
        if (closedByUser) return
        val ranMs = (System.nanoTime() - channelStartedAt) / 1_000_000
        if (tmuxFailedToStart(tmux.inUse && !tmux.disabled, status, ranMs) && status != null) {
            fallBackToPlainShell(status)
            return
        }
        val reason = when {
            error != null -> Texts.get(R.string.ssh_connection_lost, error.message ?: error.javaClass.simpleName)
            status != null -> Texts.get(R.string.ssh_logged_out, status)
            else -> Texts.get(R.string.ssh_connection_closed)
        }
        if (shellDropped(status, signal)) drop(reason) else end(reason)
    }

    /** tmux could not start here: say so, and open a plain shell in the tab at once, for good. */
    private fun fallBackToPlainShell(status: Int) {
        if (!_state.compareAndSet(SshState.Open, SshState.Connecting)) return
        tmux.disabled = true
        tmux.inUse = false
        copyMode.active = false
        shell = null
        terminal.pause()
        terminal.resetForNewShell("\r\n[" + Texts.get(R.string.ssh_tmux_failed, status) + "]\r\n")
        scope.launch(Dispatchers.IO) { run(retrying = false) }
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

        /** The most a tmux probe, kill or copy-mode exec may take. */
        const val TMUX_EXEC_TIMEOUT_S = 10L

        /** The longest a tmux answer to a tap may take and still move the cursor. */
        const val TAP_ANSWER_MAX_MS = 2_000L

        /** The probe prints two short lines; anything past this is not read. */
        const val PROBE_MAX_BYTES = 4096
    }
}
