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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import ru.adsrv.frpjump.ssh.PinnedHostKey
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ssh.TerminalScreenListener
import android.widget.Toast
import android.app.Activity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.snippets.SnippetStore
import ru.adsrv.frpjump.ui.AppTheme

/** The SSH sessions as tabs, the selected one in a Termux terminal view. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    sessions: SshSessions,
    snippetStore: SnippetStore,
    /** Profile id to its colour's name, for the tabs' dots. */
    profileColors: Map<String, String>,
    initialId: Int,
    onBack: () -> Unit,
) {
    val list by sessions.sessions.collectAsStateWithLifecycle()
    val snippets by snippetStore.snippets.collectAsStateWithLifecycle()
    var managing by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun write(change: SnippetStore.() -> Unit) {
        scope.launch(Dispatchers.IO) { snippetStore.change() }
    }
    val snippetActions = remember {
        SnippetActions(
            add = { text -> write { add(text) } },
            update = { id, text -> write { update(id, text) } },
            remove = { id -> write { remove(id) } },
            move = { id, by -> write { move(id, by) } },
        )
    }
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
    // The bars are dark in both themes: light status bar icons while the terminal shows.
    val root = LocalView.current
    DisposableEffect(root) {
        val window = (root.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, root) }
        val before = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = false
        onDispose { if (bars != null && before != null) bars.isAppearanceLightStatusBars = before }
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
    val colors = AppTheme.colors
    val notOpen = stringResource(R.string.terminal_not_open)
    Scaffold(
        containerColor = colors.termBar,
        topBar = {
            if (!compact) TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.termBar,
                    titleContentColor = colors.onTerm,
                    navigationIconContentColor = colors.onTerm,
                    actionIconContentColor = colors.onTerm,
                ),
                title = {
                    Column {
                        Text(
                            selected.device + if (selected.port != 22) ":${selected.port}" else "",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            statusLine(selected, state),
                            fontSize = 12.sp,
                            color = colors.termMuted,
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
                    val keyboard = stringResource(R.string.terminal_keyboard)
                    BarButton("\u2328", keyboard, onClick = input::showKeyboard)
                    BarButton("A\u2212", smaller, enabled = fontSp > MIN_FONT_SP) {
                        sessions.fontSize.value = (fontSp - 1).coerceAtLeast(MIN_FONT_SP)
                    }
                    BarButton("A+", larger, enabled = fontSp < MAX_FONT_SP) {
                        sessions.fontSize.value = (fontSp + 1).coerceAtMost(MAX_FONT_SP)
                    }
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
                SessionTabs(list, selected.id, profileColors) { selectedId = it }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.termBar)
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ended.message, fontSize = 13.sp, color = colors.onTerm, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { sessions.reconnect(selected.id)?.let { selectedId = it.id } },
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                    ) { Text(stringResource(R.string.terminal_reconnect)) }
                }
            }
            HorizontalDivider(color = colors.termLine)
            SnippetsRow(
                snippets = snippets,
                onSend = { text, run ->
                    if (state != SshState.Open) {
                        Toast.makeText(context, notOpen, Toast.LENGTH_SHORT).show()
                    } else {
                        selected.terminal.session.write(if (run) text + "\r" else text)
                    }
                },
                onManage = { managing = true },
            )
            ExtraKeys(
                modifiers = modifiers,
                onKey = { key -> sendExtraKey(view, key, modifiers) },
            )
        }
    }
    if (managing) SnippetsSheet(snippets, snippetActions, onDismiss = { managing = false })

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

/** A narrow text button of the terminal's bar, so the device's name keeps its room. */
@Composable
private fun BarButton(text: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .size(width = 44.dp, height = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) colors.onTerm else colors.termMuted)
    }
}

/** The open sessions as the mockup's tabs: the profile's colour, then user and device. */
@Composable
private fun SessionTabs(list: List<SshSession>, selectedId: Int, profileColors: Map<String, String>, onSelect: (Int) -> Unit) {
    val colors = AppTheme.colors
    val labels = list.map { s -> "${s.user} \u00B7 ${s.device}" + if (s.port != 22) ":${s.port}" else "" }
    val row = rememberLazyListState()
    val at = list.indexOfFirst { it.id == selectedId }
    // A tab picked or opened off screen comes into view.
    LaunchedEffect(at) { if (at >= 0) row.animateScrollToItem(at) }
    LazyRow(
        state = row,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.termBar)
            .selectableGroup(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(list, key = { _, s -> s.id }) { index, s ->
            val on = s.id == selectedId
            // Two shells as the same user to the same device look alike otherwise.
            val label = if (labels.count { it == labels[index] } > 1) "${index + 1}. ${labels[index]}" else labels[index]
            Row(
                Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) colors.termKey else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab) { onSelect(s.id) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(colors.termProfile(profileColors[s.profile] ?: "grey"), CircleShape),
                )
                Text(
                    label,
                    fontSize = 13.sp,
                    color = if (on) colors.onTerm else colors.termMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            }
        }
    }
}

@Composable
private fun statusLine(session: SshSession, state: SshState): String = session.user + " \u00B7 " + when (state) {
    SshState.Connecting -> stringResource(R.string.terminal_state_connecting)
    SshState.Open -> stringResource(R.string.terminal_state_open)
    is SshState.HostKeyUnknown -> stringResource(R.string.terminal_status_host_key_unknown)
    is SshState.HostKeyChanged -> stringResource(R.string.terminal_status_host_key_changed)
    // The reason is in the line above the snippets, with Reconnect.
    is SshState.Ended -> stringResource(R.string.terminal_state_ended)
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
