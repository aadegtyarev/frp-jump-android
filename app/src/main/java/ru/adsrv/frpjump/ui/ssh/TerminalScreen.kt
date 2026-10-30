package ru.adsrv.frpjump.ui.ssh

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import ru.adsrv.frpjump.ssh.PinnedHostKey
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ssh.TerminalScreenListener

/** Sticky Ctrl / Alt from the extra-keys row: apply to the next key, then release. */
@Stable
private class Modifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    fun takeCtrl() = ctrl.also { ctrl = false }

    fun takeAlt() = alt.also { alt = false }
}

/** The SSH sessions as tabs, the selected one in a Termux terminal view. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(sessions: SshSessions, initialId: Int, onBack: () -> Unit) {
    val list by sessions.sessions.collectAsStateWithLifecycle()
    val fontSp by sessions.fontSize.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf(initialId) }
    val selected = list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()
    LaunchedEffect(list.isEmpty()) { if (list.isEmpty()) onBack() }
    if (selected == null) return
    val state by selected.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val modifiers = remember { Modifiers() }
    val current = rememberUpdatedState(selected)
    val view = remember {
        TerminalView(context, null).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setTerminalViewClient(ViewClient(this, { current.value }, modifiers, sessions))
        }
    }
    DisposableEffect(view) {
        sessions.listener = object : TerminalScreenListener {
            override fun onScreenChanged(session: TerminalSession) {
                if (view.currentSession === session) view.onScreenUpdated()
            }

            override fun onCursorStateChanged(visible: Boolean) = Unit
        }
        onDispose { sessions.listener = null }
    }
    // A new font size re-measures the grid; onEmulatorSet then sends window-change.
    LaunchedEffect(fontSp) { view.setTextSize(spToPx(context, fontSp)) }
    LaunchedEffect(selected.id) {
        view.attachSession(selected.terminal.session)
        view.onScreenUpdated()
        view.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(selected.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            statusLine(selected, state),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { sessions.fontSize.value = (fontSp - 1).coerceAtLeast(MIN_FONT_SP) }) { Text("A\u2212") }
                    TextButton(onClick = { sessions.fontSize.value = (fontSp + 1).coerceAtMost(MAX_FONT_SP) }) { Text("A+") }
                    IconButton(onClick = { sessions.close(selected.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close session")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            if (list.size > 1) {
                ScrollableTabRow(selectedTabIndex = list.indexOf(selected).coerceAtLeast(0), edgePadding = 8.dp) {
                    list.forEach { s ->
                        Tab(
                            selected = s.id == selected.id,
                            onClick = { selectedId = s.id },
                            text = { Text("${s.title}:${s.port}", maxLines = 1) },
                        )
                    }
                }
            }
            AndroidView(
                factory = { view },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
            )
            (state as? SshState.Ended)?.let { ended ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ended.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { sessions.reconnect(selected.id)?.let { selectedId = it.id } }) { Text("Reconnect") }
                }
            }
            ExtraKeys(
                modifiers = modifiers,
                onKey = { key -> sendExtraKey(view, key, modifiers) },
                onKeyboard = { showKeyboard(context, view) },
            )
        }
    }

    when (val s = state) {
        is SshState.HostKeyUnknown -> TrustDialog(
            selected, s.presented,
            onTrust = selected::trustHostKey,
            onCancel = { sessions.close(selected.id) },
        )
        is SshState.HostKeyChanged -> ChangedKeyDialog(
            selected, s.pinned, s.presented,
            onForget = selected::forgetOldHostKey,
            onCancel = { sessions.close(selected.id) },
        )
        else -> Unit
    }
}

private fun statusLine(session: SshSession, state: SshState): String = when (state) {
    SshState.Connecting -> "Connecting\u2026"
    SshState.Open -> "${session.device}:${session.port} via 127.0.0.1:${session.localPort}"
    is SshState.HostKeyUnknown -> "Waiting for the host key decision"
    is SshState.HostKeyChanged -> "Blocked: host key changed"
    is SshState.Ended -> state.message
}

@Composable
private fun TrustDialog(session: SshSession, presented: PinnedHostKey, onTrust: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Trust this device?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("First SSH connection to ${session.device}:${session.port}. Its host key:")
                KeyText(presented)
                Text(
                    "On the device, `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` prints the same line " +
                        "if this is really it. The key is remembered for this device and port.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("Trust") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ChangedKeyDialog(
    session: SshSession,
    pinned: PinnedHostKey,
    presented: PinnedHostKey,
    onForget: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Host key changed", color = MaterialTheme.colorScheme.error) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${session.device}:${session.port} shows another host key than the one trusted before. " +
                        "That happens after a reinstall or a firmware reset, but it can also mean someone is in between. " +
                        "Nothing was sent.",
                )
                Text("Trusted before:", style = MaterialTheme.typography.labelMedium)
                KeyText(pinned)
                Text("Shown now:", style = MaterialTheme.typography.labelMedium)
                KeyText(presented)
                Text(
                    "Forget the old key only if you know why it changed; you will be asked to trust the new one.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onForget) { Text("Forget the old key") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun KeyText(key: PinnedHostKey) {
    Text("${key.type}\n${key.fingerprint}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}

private enum class ExtraKey(val label: String) {
    ESC("Esc"), TAB("Tab"), CTRL("Ctrl"), ALT("Alt"),
    LEFT("\u2190"), UP("\u2191"), DOWN("\u2193"), RIGHT("\u2192"),
    PIPE("|"), DASH("-"), SLASH("/"), TILDE("~"),
}

@Composable
private fun ExtraKeys(modifiers: Modifiers, onKey: (ExtraKey) -> Unit, onKeyboard: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onKeyboard) { Text("\u2328") }
        ExtraKey.entries.forEach { key ->
            when (key) {
                ExtraKey.CTRL -> FilterChip(selected = modifiers.ctrl, onClick = { modifiers.ctrl = !modifiers.ctrl }, label = { Text(key.label) })
                ExtraKey.ALT -> FilterChip(selected = modifiers.alt, onClick = { modifiers.alt = !modifiers.alt }, label = { Text(key.label) })
                else -> OutlinedButton(
                    onClick = { onKey(key) },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text(key.label) }
            }
        }
    }
}

private fun sendExtraKey(view: TerminalView, key: ExtraKey, modifiers: Modifiers) {
    if (view.currentSession == null) return
    val keyCode = when (key) {
        ExtraKey.ESC -> KeyEvent.KEYCODE_ESCAPE
        ExtraKey.TAB -> KeyEvent.KEYCODE_TAB
        ExtraKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        ExtraKey.UP -> KeyEvent.KEYCODE_DPAD_UP
        ExtraKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        ExtraKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        else -> null
    }
    if (keyCode != null) {
        var mod = 0
        if (modifiers.takeCtrl()) mod = mod or KeyHandler.KEYMOD_CTRL
        if (modifiers.takeAlt()) mod = mod or KeyHandler.KEYMOD_ALT
        view.handleKeyCode(keyCode, mod)
    } else {
        // inputCodePoint reads (and releases) the sticky Ctrl / Alt itself.
        view.inputCodePoint(key.label.codePointAt(0), false, false)
    }
}

private fun showKeyboard(context: Context, view: TerminalView) {
    view.requestFocus()
    context.getSystemService(InputMethodManager::class.java).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

private fun spToPx(context: Context, sp: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), context.resources.displayMetrics).toInt()

/** Glue between the Termux view and the app: keyboard, sticky modifiers, pinch zoom, resize. */
private class ViewClient(
    private val view: TerminalView,
    private val session: () -> SshSession,
    private val modifiers: Modifiers,
    private val sessions: SshSessions,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        // Pinch: step the shared font size once the gesture has scaled ~20 %.
        if (scale < 0.8f || scale > 1.2f) {
            val step = if (scale > 1f) 1 else -1
            sessions.fontSize.value = (sessions.fontSize.value + step).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) = showKeyboard(view.context, view)

    override fun shouldBackButtonBeMappedToEscape() = false

    override fun shouldEnforceCharBasedInput() = true

    override fun shouldUseCtrlSpaceWorkaround() = false

    override fun isTerminalViewSelected() = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false

    override fun onLongPress(event: MotionEvent) = false

    override fun readControlKey() = modifiers.takeCtrl()

    override fun readAltKey() = modifiers.takeAlt()

    override fun readShiftKey() = false

    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false

    /** The grid was (re)measured: tell the remote side. */
    override fun onEmulatorSet() {
        val s = session()
        if (view.currentSession === s.terminal.session) s.resize(s.terminal.columns, s.terminal.rows)
    }

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

    private companion object {
        const val TAG = "TerminalView"
    }
}

private const val MIN_FONT_SP = 8
private const val MAX_FONT_SP = 28
