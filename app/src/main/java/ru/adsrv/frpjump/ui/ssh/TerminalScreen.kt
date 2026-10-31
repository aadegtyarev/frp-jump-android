package ru.adsrv.frpjump.ui.ssh

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.snippets.SnippetStore
import ru.adsrv.frpjump.snippets.stepsAmongShown
import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.KeyInstallTarget
import ru.adsrv.frpjump.ssh.SshLogin
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ssh.TerminalScreenListener
import ru.adsrv.frpjump.ssh.saysTunnelClosed
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.CollapseButton
import ru.adsrv.frpjump.ui.FolderGlyph
import ru.adsrv.frpjump.ui.FullscreenGlyph
import ru.adsrv.frpjump.ui.FullscreenOf
import ru.adsrv.frpjump.ui.SystemBarsHidden
import ru.adsrv.frpjump.ui.notice
import ru.adsrv.frpjump.ui.rememberFullscreen
import ru.adsrv.frpjump.ui.userMessage
import ru.adsrv.frpjump.undo.PendingUndo

/** The SSH sessions as tabs, the selected one in a Termux terminal view. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    sessions: SshSessions,
    snippetStore: SnippetStore,
    /** Where a deleted snippet waits for its Undo to run out. */
    pendingUndo: PendingUndo,
    /** Profile id to its colour's name, for the tabs' dots. */
    profileColors: Map<String, String>,
    initialId: Int,
    onBack: () -> Unit,
    /** "Files" for the selected session's device, port and login. */
    onOpenFiles: (SshSession) -> Unit,
    /** Keeps the login typed in the dialog of a long press on "+" for that session's device and port. */
    rememberLogin: (SshSession, SshLogin) -> Unit,
    /** "Use key login", shared with the app's scope. */
    keyLogin: KeyLoginFlow,
    /** Where a session's "Use key login" installs the key; null when its login is not by password. */
    keyLoginTarget: (SshSession) -> KeyInstallTarget?,
) {
    val list by sessions.sessions.collectAsStateWithLifecycle()
    val stored by snippetStore.snippets.collectAsStateWithLifecycle()
    val hidden by pendingUndo.hidden.collectAsStateWithLifecycle()
    // One deleted while its Undo is on screen is not offered.
    val snippets = remember(stored, hidden) { stored.filter { PendingUndo.snippetKey(it.id) !in hidden } }
    var managing by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    /** Writes off the main thread; a failed write keeps the list as it was and says so. */
    fun write(change: SnippetStore.() -> Unit) {
        scope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching { snippetStore.change() }.exceptionOrNull() }
            if (failure != null) {
                context.notice(context.getString(R.string.snippet_save_failed, failure.userMessage()), NoticeKind.WARN, long = true)
            }
        }
    }
    val snippetActions = remember {
        SnippetActions(
            add = { text -> write { add(text) } },
            update = { id, text -> write { update(id, text) } },
            // No question: it goes at once, with Undo on the notice; removed from the file when that goes.
            remove = { id ->
                val app = context.applicationContext
                pendingUndo.enqueue(PendingUndo.snippetKey(id), commit = {
                    val failure = withContext(Dispatchers.IO) { runCatching { snippetStore.remove(id) }.exceptionOrNull() }
                    if (failure != null) {
                        app.notice(app.getString(R.string.snippet_save_failed, failure.userMessage()), NoticeKind.WARN, long = true)
                    }
                })
            },
            // ↑ ↓ step past a snippet deleted with Undo on screen, as it is not shown.
            move = { id, by ->
                val hiddenNow = pendingUndo.hidden.value
                val steps = stepsAmongShown(snippetStore.snippets.value, { PendingUndo.snippetKey(it.id) !in hiddenNow }, id, by)
                if (steps != 0) write { move(id, steps) }
            },
        )
    }
    val fontSp by sessions.fontSize.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf(initialId) }
    /** The session whose device and port the login dialog of a long press on "+" is for. */
    var loginLikeId by rememberSaveable { mutableStateOf<Int?>(null) }
    /** The dialog is "Change login" of an ended session: the new one takes its tab. */
    var loginReplaces by rememberSaveable { mutableStateOf(false) }
    fun changeLogin(id: Int) {
        loginReplaces = true
        loginLikeId = id
    }
    val selected = list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()
    // The session shown went because its tunnel was closed (Disconnect, the notification): say so, not vanish silently.
    var shownId by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selected?.id) {
        val before = shownId
        shownId = selected?.id
        if (saysTunnelClosed(before, shownId, { sessions.find(it) != null }, sessions::closedByTunnel)) {
            context.notice(context.getString(R.string.ssh_tunnel_closed), NoticeKind.INFO)
        }
    }
    LaunchedEffect(list.isEmpty()) { if (list.isEmpty()) onBack() }
    // Leaving keeps the sessions: the first time, say where they are (not when Files covers the screen).
    fun leave() {
        val live = sessions.sessions.value.count { it.state.value !is SshState.Ended }
        if (live > 0 && !sessions.leftRunningHintShown) {
            sessions.leftRunningHintShown = true
            context.notice(context.resources.getQuantityString(R.plurals.terminal_left_running, live, live), NoticeKind.INFO, long = true)
        }
        onBack()
    }
    if (selected == null) return
    val gone = stringResource(R.string.terminal_session_gone)
    // A new tab is selected; when the session to copy closed meanwhile, a toast says so.
    fun openedOrGone(opened: SshSession?) {
        if (opened != null) selectedId = opened.id else context.notice(gone, NoticeKind.WARN)
    }
    // Closing the tab shown shows its neighbour, not the first tab.
    fun closeTab(id: Int) {
        selectionAfterClose(list.map { it.id }, selected.id, id)?.let { selectedId = it }
        sessions.close(id)
    }
    fun reconnectTab(id: Int) {
        sessions.reconnect(id)?.let { selectedId = it.id }
    }
    /** A live session asked to close from its tab: confirmed first, its commands stop. */
    var closing by remember { mutableStateOf<Int?>(null) }
    val tabActions = SessionTabActions(
        select = { selectedId = it },
        close = { id ->
            val live = list.firstOrNull { it.id == id }?.state?.value.let { it != null && it !is SshState.Ended }
            if (live) closing = id else closeTab(id)
        },
        reconnect = ::reconnectTab,
        changeLogin = ::changeLogin,
        // The same login at once; a refused one, or a remembered password since forgotten, needs the dialog.
        add = {
            if (reusesLogin(selected.auth, selected.authRefused) { sessions.hasRememberedPassword(selected, selected.user) }) {
                openedOrGone(sessions.openLike(selected.id))
            } else {
                loginReplaces = false
                loginLikeId = selected.id
            }
        },
        addAs = {
            loginReplaces = false
            loginLikeId = selected.id
        },
        keyLogin = { id -> list.firstOrNull { it.id == id }?.let(keyLoginTarget)?.let(keyLogin::start) },
        offersKeyLogin = { id -> list.firstOrNull { it.id == id }?.let(keyLoginTarget) != null },
    )
    val state by selected.state.collectAsStateWithLifecycle()
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
    // Full screen: no bar, no system bars, the session tabs only for several; the snippets and extra keys stay, typing needs them.
    val (fullscreen, setFullscreen) = rememberFullscreen(FullscreenOf.TERMINAL)
    BackHandler(enabled = !fullscreen) { leave() }
    BackHandler(enabled = fullscreen) { setFullscreen(false) }
    SystemBarsHidden(fullscreen)
    Scaffold(
        containerColor = colors.termBar,
        // Hidden bars take no room; a display cutout still does.
        contentWindowInsets = if (fullscreen) WindowInsets.displayCutout else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!compact && !fullscreen) TopAppBar(
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
                    IconButton(onClick = ::leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    val smaller = stringResource(R.string.terminal_text_smaller)
                    val larger = stringResource(R.string.terminal_text_larger)
                    val keyboard = stringResource(R.string.terminal_keyboard)
                    BarButton("\u2328", keyboard, onClick = input::showKeyboard)
                    IconButton(onClick = { onOpenFiles(selected) }) {
                        Icon(FolderGlyph, contentDescription = stringResource(R.string.files), modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { setFullscreen(true) }) {
                        Icon(FullscreenGlyph, contentDescription = stringResource(R.string.fullscreen), modifier = Modifier.size(20.dp))
                    }
                    BarButton("A\u2212", smaller, enabled = fontSp > MIN_FONT_SP) {
                        sessions.fontSize.value = (fontSp - 1).coerceAtLeast(MIN_FONT_SP)
                    }
                    BarButton("A+", larger, enabled = fontSp < MAX_FONT_SP) {
                        sessions.fontSize.value = (fontSp + 1).coerceAtMost(MAX_FONT_SP)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                // Always under the bar. Full screen and landscape with the keyboard up keep it
                // only for several sessions: there it is the only way to see and switch them.
                if (!(fullscreen || compact) || list.size > 1) {
                    SessionTabs(list, selected.id, profileColors, tabActions)
                }
                // Full screen has no bar: say what the session is doing until it is open.
                if (fullscreen && state != SshState.Open && state !is SshState.Ended) {
                    Text(
                        selected.device + " · " + statusLine(selected, state),
                        fontSize = 12.sp,
                        color = colors.termMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.termBar)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
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
                    // The reason on its own line, the buttons under it: side by side with two
                    // buttons a long reason got squeezed into a column of word fragments.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.termBar)
                            .padding(start = 12.dp, end = 4.dp, top = 8.dp),
                    ) {
                        Text(
                            ended.message,
                            fontSize = 13.sp,
                            color = colors.onTerm,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Row(modifier = Modifier.align(Alignment.End)) {
                            TextButton(
                                onClick = { reconnectTab(selected.id) },
                                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                            ) { Text(stringResource(R.string.terminal_reconnect)) }
                            // "Wrong user or password": Reconnect would only repeat it.
                            TextButton(
                                onClick = { changeLogin(selected.id) },
                                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                            ) { Text(stringResource(R.string.terminal_change_login)) }
                        }
                    }
                }
                HorizontalDivider(color = colors.termLine)
                SnippetsRow(
                    snippets = snippets,
                    onSend = { text, run ->
                        if (state != SshState.Open) {
                            context.notice(notOpen, NoticeKind.WARN)
                        } else {
                            // The Enter key sends CR; so do line breaks inside a command.
                            val line = text.replace("\r\n", "\r").replace('\n', '\r')
                            selected.terminal.session.write(if (run) line + "\r" else line)
                            // A snippet is typed as is, without a held Ctrl / Alt; it uses up the hold.
                            modifiers.ctrl = false
                            modifiers.alt = false
                        }
                    },
                    onManage = { managing = true },
                )
                ExtraKeys(
                    modifiers = modifiers,
                    onKey = { key -> sendExtraKey(view, key, modifiers) },
                )
            }
            if (fullscreen) CollapseButton { setFullscreen(false) }
        }
    }
    if (managing) SnippetsSheet(snippets, snippetActions, onDismiss = { managing = false })
    // The session the dialog is for closed under it (its tunnel went): say so rather than vanish.
    val loginFrom = loginLikeId?.let { id -> list.firstOrNull { it.id == id } }
    LaunchedEffect(loginLikeId, loginFrom == null) {
        if (loginLikeId != null && loginFrom == null) {
            loginLikeId = null
            context.notice(gone, NoticeKind.WARN)
        }
    }
    loginFrom?.let { from ->
        SshLoginDialog(
            target = "${from.device}:${from.port}",
            initial = loginLike(from.user, from.auth),
            hasRememberedPassword = { sessions.hasRememberedPassword(from, it) },
            onConnect = { user, method, password, remember ->
                loginLikeId = null
                val login = SshLogin(user.trim().ifEmpty { SshLogin.DEFAULT_USER }, method)
                rememberLogin(from, login)
                val auth = when (method) {
                    SshAuthMethod.KEY -> SshAuth.Key
                    SshAuthMethod.PASSWORD -> SshAuth.Password(password.ifEmpty { null }, remember)
                }
                if (loginReplaces) {
                    openedOrGone(sessions.reconnect(from.id, login.user, auth))
                } else {
                    openedOrGone(sessions.openLike(from.id, login.user, auth))
                }
            },
            onDismiss = { loginLikeId = null },
        )
    }

    closing?.let { id ->
        val session = list.firstOrNull { it.id == id }
        if (session == null) {
            closing = null
        } else {
            AlertDialog(
                onDismissRequest = { closing = null },
                title = { Text(stringResource(R.string.terminal_close_title, session.title)) },
                text = { Text(stringResource(R.string.terminal_close_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        closing = null
                        closeTab(id)
                    }) { Text(stringResource(R.string.terminal_close_session), color = colors.error) }
                },
                dismissButton = { TextButton(onClick = { closing = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    KeyLoginDialogs(keyLogin)

    when (val s = state) {
        is SshState.HostKeyUnknown -> HostKeyTrustDialog(
            "${selected.device}:${selected.port}", s.presented,
            onTrust = selected::trustHostKey,
            onCancel = { closeTab(selected.id) },
        )
        is SshState.HostKeyChanged -> HostKeyChangedDialog(
            "${selected.device}:${selected.port}", s.pinned, s.presented,
            onForget = selected::forgetOldHostKey,
            onCancel = { closeTab(selected.id) },
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

@Composable
private fun statusLine(session: SshSession, state: SshState): String = session.user + " \u00B7 " + when (state) {
    SshState.Connecting -> stringResource(R.string.terminal_state_connecting)
    SshState.Open -> stringResource(R.string.terminal_state_open)
    is SshState.HostKeyUnknown -> stringResource(R.string.terminal_status_host_key_unknown)
    is SshState.HostKeyChanged -> stringResource(R.string.terminal_status_host_key_changed)
    // The reason is in the line above the snippets, with Reconnect.
    is SshState.Ended -> stringResource(R.string.terminal_state_ended)
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
