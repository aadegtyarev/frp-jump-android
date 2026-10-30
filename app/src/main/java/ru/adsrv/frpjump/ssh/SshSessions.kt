package ru.adsrv.frpjump.ssh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.tunnel.TunnelsState

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
    tunnels: StateFlow<TunnelsState>,
    private val scope: CoroutineScope,
) {
    private val _sessions = MutableStateFlow<List<SshSession>>(emptyList())
    val sessions: StateFlow<List<SshSession>> = _sessions
    private var nextId = 1

    /** Terminal text size in sp, shared by all sessions. */
    val fontSize = MutableStateFlow(DEFAULT_FONT_SP)

    /** The terminal screen while it is shown; set and cleared on the main thread. */
    var listener: TerminalScreenListener? = null

    init {
        scope.launch(Dispatchers.Main) {
            tunnels.map { state -> state.tunnels.map { it.key }.toSet() }.distinctUntilChanged().collect { up ->
                _sessions.value.filter { localPortKey(it.device, it.port) !in up }.forEach { close(it.id, "The tunnel was closed.") }
            }
        }
    }

    /** Opens a new session and starts connecting. Main thread. */
    fun open(device: String, port: Int, localPort: Int, user: String, auth: SshAuth): SshSession {
        val session = SshSession(nextId++, device, port, localPort, user, auth, env, scope, ClientForScreen())
        _sessions.update { it + session }
        session.connect()
        return session
    }

    /** A fresh session with the same login in place of an ended one. Main thread. */
    fun reconnect(id: Int): SshSession? {
        val old = _sessions.value.firstOrNull { it.id == id } ?: return null
        old.close()
        val fresh = SshSession(nextId++, old.device, old.port, old.localPort, old.user, old.auth, env, scope, ClientForScreen())
        _sessions.update { list -> list.map { if (it.id == id) fresh else it } }
        fresh.connect()
        return fresh
    }

    fun close(id: Int, reason: String = "Closed.") {
        val session = _sessions.value.firstOrNull { it.id == id } ?: return
        session.close(reason)
        _sessions.update { list -> list.filter { it.id != id } }
    }

    fun closeAll() = _sessions.value.forEach { close(it.id) }

    fun find(id: Int): SshSession? = _sessions.value.firstOrNull { it.id == id }

    /** Callbacks from one session's emulator, forwarded to the screen when it shows that session. */
    private inner class ClientForScreen : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            listener?.onScreenChanged(changedSession)
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit

        override fun onSessionFinished(finishedSession: TerminalSession) = Unit

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
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
        const val DEFAULT_FONT_SP = 14
    }
}
