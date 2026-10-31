package ru.adsrv.frpjump.ssh

import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.ports.profilePortKey

/** What the terminal screen hears about the sessions it shows. */
interface TerminalScreenListener {
    fun onScreenChanged(session: TerminalSession)

    fun onCursorStateChanged(visible: Boolean)
}

/**
 * The app's SSH sessions. They outlive the terminal screen; a session ends
 * when its tunnel goes away (Disconnect, Disconnect all, Forget), so the
 * foreground service that keeps the tunnels also keeps the sessions.
 */
class SshSessions(
    private val context: Context,
    private val env: SshEnvironment,
    /** Every tunnel up, in all profiles, as [profilePortKey]s. */
    tunnelsUp: Flow<Set<String>>,
    private val scope: CoroutineScope,
) {
    private val _sessions = MutableStateFlow<List<SshSession>>(emptyList())
    val sessions: StateFlow<List<SshSession>> = _sessions
    private var nextId = 1

    /** Terminal text size in sp, shared by all sessions. */
    val fontSize = MutableStateFlow(DEFAULT_FONT_SP)

    /** The terminal screen while it is shown; set and cleared on the main thread. */
    var listener: TerminalScreenListener? = null

    /** "Sessions keep running" was said once in this process: leaving the terminal says it the first time. */
    var leftRunningHintShown = false

    init {
        scope.launch(Dispatchers.Main) {
            tunnelsUp.distinctUntilChanged().collect { up ->
                _sessions.value.filter { profilePortKey(it.profile, it.device, it.port) !in up }
                    .forEach { close(it.id, Texts.get(R.string.ssh_tunnel_closed)) }
            }
        }
    }

    /** Opens a new session to [device]:[port] of [profile] and starts connecting. Main thread. */
    fun open(profile: String, hostKeys: HostKeyStore, device: String, port: Int, localPort: Int, user: String, auth: SshAuth): SshSession {
        val target = SshTarget(user, device, port)
        val number = nextSessionNumber(_sessions.value.map { SshTarget(it.user, it.device, it.port) to it.number }, target)
        val session = SshSession(nextId++, profile, hostKeys, device, port, localPort, user, auth, env, scope, ClientForScreen(), number)
        _sessions.update { it + session }
        session.connect()
        return session
    }

    /** A fresh session with the same login in place of an ended one. Main thread. */
    fun reconnect(id: Int): SshSession? {
        val old = _sessions.value.firstOrNull { it.id == id } ?: return null
        old.close()
        val fresh = SshSession(nextId++, old.profile, old.hostKeys, old.device, old.port, old.localPort, old.user, old.auth, env, scope, ClientForScreen(), old.number)
        _sessions.update { list -> list.map { if (it.id == id) fresh else it } }
        fresh.connect()
        return fresh
    }

    /**
     * Another session to [id]'s device and port, as [user] with [auth]
     * (by default the same login), after the others. Main thread.
     */
    fun openLike(id: Int, user: String? = null, auth: SshAuth? = null): SshSession? {
        val from = find(id) ?: return null
        return open(from.profile, from.hostKeys, from.device, from.port, from.localPort, user ?: from.user, auth ?: from.auth)
    }

    /** Whether a password is kept for [user] on [session]'s device and port; cheap, nothing is unsealed. */
    fun hasRememberedPassword(session: SshSession, user: String): Boolean =
        env.passwords.has(session.profile, session.device, session.port, user.trim())

    fun close(id: Int, reason: String = Texts.get(R.string.ssh_closed)) {
        val session = _sessions.value.firstOrNull { it.id == id } ?: return
        session.close(reason)
        _sessions.update { list -> list.filter { it.id != id } }
    }

    fun closeAll() = _sessions.value.forEach { close(it.id) }

    /** Closes the sessions of [profile] ("Forget profile"). */
    fun closeProfile(profile: String) = _sessions.value.filter { it.profile == profile }.forEach { close(it.id) }

    fun find(id: Int): SshSession? = _sessions.value.firstOrNull { it.id == id }

    /** The last "copied" toast, cancelled by the next: a host repeating OSC 52 must not queue them up. */
    private var copyToast: Toast? = null

    /** Callbacks from one session's emulator, forwarded to the screen when it shows that session. */
    private inner class ClientForScreen : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            listener?.onScreenChanged(changedSession)
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit

        override fun onSessionFinished(finishedSession: TerminalSession) = Unit

        /**
         * The owner's own copy of a selection, and also a remote program's
         * OSC 52, which writes the clipboard unasked: either way a toast
         * shows what went there.
         */
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            val copied = text.take(MAX_COPY)
            try {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(Texts.get(R.string.terminal), copied))
            } catch (e: RuntimeException) {
                Log.w(TAG, "clipboard not set: ${e.javaClass.simpleName}")
                return
            }
            // Called on the main thread (the emulator parses there); one toast, not a queue of them.
            copyToast?.cancel()
            copyToast = Toast.makeText(context, Texts.get(R.string.terminal_copied, copyPreview(copied, COPY_PREVIEW)), Toast.LENGTH_SHORT).also { it.show() }
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
            val text = (0 until clip.itemCount).joinToString("") { clip.getItemAt(it).coerceToText(context) }
            if (text.isEmpty()) return
            _sessions.value.firstOrNull { it.terminal.session === session }?.terminal?.paste(text)
        }

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            listener?.onScreenChanged(session)
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            listener?.onCursorStateChanged(state)
        }

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) {
            Log.e(tag ?: TAG, message.orEmpty())
        }

        override fun logWarn(tag: String?, message: String?) {
            Log.w(tag ?: TAG, message.orEmpty())
        }

        override fun logInfo(tag: String?, message: String?) = Unit

        override fun logDebug(tag: String?, message: String?) = Unit

        override fun logVerbose(tag: String?, message: String?) = Unit

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.w(tag ?: TAG, message.orEmpty(), e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.w(tag ?: TAG, e)
        }
    }

    companion object {
        private const val TAG = "SshSessions"

        /** Characters of copied text the toast shows. */
        private const val COPY_PREVIEW = 40

        /** The most a terminal puts on the clipboard: an OSC 52 of megabytes would not fit through Binder. */
        private const val MAX_COPY = 64 * 1024
        const val DEFAULT_FONT_SP = 14
    }
}

/** Who a session logs in as and where. */
data class SshTarget(val user: String, val device: String, val port: Int)

/**
 * A new session's number among the [open] ones (target, number) to the
 * same [target]: one past the highest, so closing one renumbers nothing;
 * 1 when none is open.
 */
fun nextSessionNumber(open: List<Pair<SshTarget, Int>>, target: SshTarget): Int =
    (open.filter { it.first == target }.maxOfOrNull { it.second } ?: 0) + 1

/**
 * What a toast shows of [text] put on the clipboard: its first non-blank
 * line without control or bidi-override characters (a remote host chooses
 * the text), cut to [max] code points, "…" when anything was left out.
 */
fun copyPreview(text: String, max: Int): String {
    val clean = text.filterNot { it.isISOControl() && it != '\n' && it != '\r' || it in BIDI_CONTROLS }
    val lines = clean.lines().map { it.trim() }
    val first = lines.firstOrNull { it.isNotEmpty() } ?: return "…"
    val cut = if (first.codePointCount(0, first.length) > max) first.substring(0, first.offsetByCodePoints(0, max)) else first
    return if (cut.length < first.length || lines.count { it.isNotEmpty() } > 1) "$cut…" else cut
}

private val BIDI_CONTROLS = ('\u202A'..'\u202E').toSet() + ('\u2066'..'\u2069').toSet() + setOf('\u200E', '\u200F', '\u061C')
