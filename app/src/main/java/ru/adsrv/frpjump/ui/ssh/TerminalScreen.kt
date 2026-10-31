package ru.adsrv.frpjump.ui.ssh

import ru.adsrv.frpjump.R
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val input = remember {
        TerminalInput(context, TerminalView(context, null).apply { isFocusable = true; isFocusableInTouchMode = true }).apply {
            terminal.setTerminalViewClient(ViewClient(this, { current.value }, modifiers, sessions))
        }
    }
    val view = input.terminal
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
        input.requestFocus()
    }

    // Landscape with the keyboard up leaves a sliver: give it all to the terminal.
    val compact = WindowInsets.isImeVisible &&
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Scaffold(
        topBar = {
            if (!compact) TopAppBar(
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    val smaller = stringResource(R.string.terminal_text_smaller)
                    val larger = stringResource(R.string.terminal_text_larger)
                    TextButton(
                        onClick = { sessions.fontSize.value = (fontSp - 1).coerceAtLeast(MIN_FONT_SP) },
                        modifier = Modifier.semantics { contentDescription = smaller },
                    ) { Text("A\u2212") }
                    TextButton(
                        onClick = { sessions.fontSize.value = (fontSp + 1).coerceAtMost(MAX_FONT_SP) },
                        modifier = Modifier.semantics { contentDescription = larger },
                    ) { Text("A+") }
                    IconButton(onClick = { sessions.close(selected.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.terminal_close_session))
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
                    list.forEachIndexed { index, s ->
                        Tab(
                            selected = s.id == selected.id,
                            onClick = { selectedId = s.id },
                            // Numbered: two shells to the same device look alike otherwise.
                            text = { Text("${index + 1}. ${s.device}:${s.port}", maxLines = 1) },
                        )
                    }
                }
            }
            AndroidView(
                factory = { input },
                // The Termux view draws its whole grid; without the clip it paints over the
                // extra keys when the keyboard leaves it only a few rows.
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .background(Color.Black),
            )
            (state as? SshState.Ended)?.let { ended ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ended.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { sessions.reconnect(selected.id)?.let { selectedId = it.id } }) { Text(stringResource(R.string.terminal_reconnect)) }
                }
            }
            ExtraKeys(
                modifiers = modifiers,
                onKey = { key -> sendExtraKey(view, key, modifiers) },
                onKeyboard = input::showKeyboard,
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

@Composable
private fun statusLine(session: SshSession, state: SshState): String = when (state) {
    SshState.Connecting -> stringResource(R.string.terminal_state_connecting)
    SshState.Open -> stringResource(R.string.terminal_status_open, "${session.device}:${session.port}", "127.0.0.1:${session.localPort}")
    is SshState.HostKeyUnknown -> stringResource(R.string.terminal_status_host_key_unknown)
    is SshState.HostKeyChanged -> stringResource(R.string.terminal_status_host_key_changed)
    is SshState.Ended -> state.message
}

@Composable
private fun TrustDialog(session: SshSession, presented: PinnedHostKey, onTrust: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.host_key_trust_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.host_key_first, "${session.device}:${session.port}"))
                KeyText(presented)
                Text(
                    stringResource(R.string.host_key_check, "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text(stringResource(R.string.cert_trust)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
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
        title = { Text(stringResource(R.string.host_key_changed_title), color = MaterialTheme.colorScheme.error) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.host_key_changed_text, "${session.device}:${session.port}"))
                Text(stringResource(R.string.host_key_trusted_before), style = MaterialTheme.typography.labelMedium)
                KeyText(pinned)
                Text(stringResource(R.string.host_key_shown_now), style = MaterialTheme.typography.labelMedium)
                KeyText(presented)
                Text(
                    stringResource(R.string.host_key_forget_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onForget) { Text(stringResource(R.string.host_key_forget)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
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
        val keyboard = stringResource(R.string.terminal_keyboard)
        TextButton(onClick = onKeyboard, modifier = Modifier.semantics { contentDescription = keyboard }) { Text("\u2328") }
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

private fun spToPx(context: Context, sp: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), context.resources.displayMetrics).toInt()

/** Glue between the Termux view and the app: keyboard, sticky modifiers, pinch zoom, resize. */
private class ViewClient(
    private val input: TerminalInput,
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

    override fun onSingleTapUp(e: MotionEvent) = input.showKeyboard()

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
        if (input.terminal.currentSession === s.terminal.session) s.resize(s.terminal.columns, s.terminal.rows)
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
