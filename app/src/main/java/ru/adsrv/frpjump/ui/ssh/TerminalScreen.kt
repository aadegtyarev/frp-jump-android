package ru.adsrv.frpjump.ui.ssh

import ru.adsrv.frpjump.ui.AppDropdownMenu
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.image.ImageLimits
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.snippets.SnippetStore
import ru.adsrv.frpjump.snippets.stepsAmongShown
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.toAuth
import ru.adsrv.frpjump.ssh.KeyInstallTarget
import ru.adsrv.frpjump.ssh.SshLogin
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.sessions.OpenSessions
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ssh.TerminalScreenListener
import ru.adsrv.frpjump.ssh.PastePlan
import ru.adsrv.frpjump.ssh.DraftSubmission
import ru.adsrv.frpjump.ssh.TerminalDraft
import ru.adsrv.frpjump.ssh.planPaste
import ru.adsrv.frpjump.ssh.pasteBracketed
import ru.adsrv.frpjump.ssh.PasteAttempt
import ru.adsrv.frpjump.ssh.attemptPaste
import ru.adsrv.frpjump.ssh.pasteExecutionSelected
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
import ru.adsrv.frpjump.sessions.SessionCloser
import ru.adsrv.frpjump.sessions.sessionHidden
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.TunnelRef

private data class PendingTerminalPaste(
    val session: SshSession, val ask: PastePlan.Ask,
    val connection: SshSession.PasteTarget,
    val submission: DraftSubmission? = null, val execute: Boolean = false,
    val selection: Any? = null,
)

/** The SSH sessions as tabs, the selected one in a Termux terminal view. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    sessions: SshSessions,
    /** The terminal and MQTT sessions together, for "N sessions keep running" on leaving. */
    allSessions: OpenSessions,
    snippetStore: SnippetStore,
    /** Where a deleted snippet waits for its Undo to run out. */
    pendingUndo: PendingUndo,
    /** Closes a tab's session when its Undo is gone. */
    closeSessions: SessionCloser,
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
    /** Tests only: stands in for "the tab is Open" and for the system picker, which an emulator without an SSH server cannot reach. */
    imageHooks: ImageEntryHooks? = null,
) {
    val all by sessions.sessions.collectAsStateWithLifecycle()
    val stored by snippetStore.snippets.collectAsStateWithLifecycle()
    val hidden by pendingUndo.hidden.collectAsStateWithLifecycle()
    // A tab closed, or its tunnel disconnected, while the Undo is on screen is not shown; it still runs.
    val list = remember(all, hidden) {
        all.filterNot { sessionHidden(hidden, SessionKind.TERMINAL, it.id, TunnelRef(it.profile, it.device, it.port)) }
    }
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
    var barMenu by remember { mutableStateOf(false) }
    /** "Close all sessions of this phone" is asking the device. */
    var closingAll by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf(initialId) }
    fun selectSession(id: Int?) {
        sessions.selectClipboardSource(id)
        if (id != null) selectedId = id
    }
    /** The session whose device and port the login dialog of a long press on "+" is for. */
    var loginLikeId by rememberSaveable { mutableStateOf<Int?>(null) }
    /** The dialog is "Change login" of an ended session: the new one takes its tab. */
    var loginReplaces by rememberSaveable { mutableStateOf(false) }
    fun changeLogin(id: Int) {
        loginReplaces = true
        loginLikeId = id
    }
    val selected = list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()
    // Initial/restored/fallback selection, not tied to listener lifetime or app visibility.
    SideEffect { sessions.selectClipboardSource(selected?.id) }
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
        allSessions.leftRunning()?.let { live ->
            context.notice(context.resources.getQuantityString(R.plurals.sessions_left_running, live, live), NoticeKind.INFO, long = true)
        }
        onBack()
    }
    if (selected == null) return
    val gone = stringResource(R.string.terminal_session_gone)
    // A new tab is selected; when the session to copy closed meanwhile, a toast says so.
    fun openedOrGone(opened: SshSession?) {
        if (opened != null) selectSession(opened.id) else context.notice(gone, NoticeKind.WARN)
    }
    // Closing the tab shown shows its neighbour, not the first tab.
    fun dropTab(id: Int) {
        selectSession(selectionAfterClose(list.map { it.id }, selected.id, id))
        sessions.close(id)
    }
    // No question: the tab goes at once, with Undo on the notice; its shell ends when that goes.
    // Undo brings it back, the same session, selected again.
    fun closeTab(id: Int) {
        selectSession(selectionAfterClose(list.map { it.id }, selected.id, id))
        closeSessions.terminal(id, undo = { selectSession(id) })
    }
    fun reconnectTab(id: Int) {
        sessions.reconnect(id)?.let { selectSession(it.id) }
    }
    val tabActions = SessionTabActions(
        select = { selectSession(it) },
        close = ::closeTab,
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
    val newShell by selected.newShell.collectAsStateWithLifecycle()
    val draftStates by sessions.drafts.state.collectAsStateWithLifecycle()
    val draft = draftStates[selected.id] ?: TerminalDraft()
    var editorValue by remember(selected.id) {
        mutableStateOf(TextFieldValue(draft.text, TextRange(draft.selectionStart, draft.selectionEnd)))
    }
    LaunchedEffect(selected.id, draft.revision) {
        // A keyboard callback can be newer than the StateFlow snapshot that scheduled this effect.
        val latest = sessions.drafts.state.value[selected.id] ?: return@LaunchedEffect
        if (editorValue.text != latest.text) editorValue = TextFieldValue(latest.text, TextRange(latest.selectionStart, latest.selectionEnd))
    }
    val editorSurface = remember(selected.id, draft.textMode, draft.expanded, LocalConfiguration.current.orientation) { Any() }
    val currentEditorSurface = rememberUpdatedState(editorSurface)
    val editorFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val modifiers = remember { Modifiers() }
    val current = rememberUpdatedState(selected)
    val selectionToken = remember(selected.id) { Any() }
    val currentSelection = rememberUpdatedState(selectionToken)
    val input = remember {
        TerminalInput(context, TerminalView(context, null).apply { isFocusable = true; isFocusableInTouchMode = true }).apply {
            terminal.setTerminalViewClient(ViewClient(this, { current.value }, modifiers, sessions))
            onTap = TerminalTap(terminal, { current.value }, ::showKeyboard)::onTap
        }
    }
    val inputSettings by (context.applicationContext as FrpJumpApp).settings.settings.collectAsStateWithLifecycle()
    SideEffect {
        input.diagnostics = selected.terminal.metrics
        input.directInput = inputSettings.terminalDirectInput
        input.onEditorKeyboard = if (draft.textMode) ({ editorFocus.requestFocus(); keyboard?.show(); Unit }) else null
    }
    val view = input.terminal
    // Captured destination and draft revision survive changing the selected tab.
    var pasteAsk by remember { mutableStateOf<PendingTerminalPaste?>(null) }
    // Images on their way, one per tab, owned by this screen: leaving it cancels them (nothing runs on in the background).
    fun visibleTab(tab: SshSession): () -> Boolean = {
        sessions.find(tab.id) === tab &&
            !sessionHidden(pendingUndo.hidden.value, SessionKind.TERMINAL, tab.id, TunnelRef(tab.profile, tab.device, tab.port))
    }
    val imageRuns = remember { mutableStateMapOf<Int, ImageRun>() }
    var pickImageFor by remember { mutableStateOf<SshSession?>(null) }
    // The clipboard is read only when the owner confirms the offer (ClipboardImageOffer), never at the Paste tap.
    val imageOffer = remember { ClipboardImageOffer<SshSession> { clipboardImageUris(context) } }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(imagePickerMax())) { uris ->
        val tab = pickImageFor
        pickImageFor = null
        if (uris.isNotEmpty() && tab != null) {
            // The system picker keeps to its limit; an old fallback picker may not.
            if (uris.size > ImageLimits.BATCH_MAX) context.notice(context.getString(R.string.terminal_images_cut, ImageLimits.BATCH_MAX), NoticeKind.INFO)
            startImageInsert(scope, context, tab, uris, imageRuns, visibleTab(tab))
        }
    }
    // The one way into the photo picker (the menu item, the long press on Paste, the editor's button). Callers pass lambdas, not
    // a reference to this local function: equal references are skipped on recomposition and would keep an old tab (see #156).
    fun tabOpen(tab: SshSession) = imageHooks?.isOpen?.invoke(tab) ?: (tab.state.value == SshState.Open)
    val canInsertImage = (imageHooks?.isOpen?.invoke(selected) ?: (state == SshState.Open)) && imageRuns[selected.id] == null
    val shownTab = rememberUpdatedState(selected)
    fun pickImageFor(tab: SshSession) {
        // [tab] is what the caller was composed with; a stale caller (an old tab) or a tab that is not Open or busy does nothing.
        if (tab !== shownTab.value || !tabOpen(tab) || imageRuns[tab.id] != null) return
        if (imageHooks != null) { imageHooks.picked(tab); return }
        pickImageFor = tab
        try {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            pickImageFor = null
            context.notice(context.getString(R.string.terminal_image_no_picker), NoticeKind.WARN)
        }
    }
    // A tab closed (its Undo pending, closed for good, its tunnel gone) takes its image with it.
    LaunchedEffect(list) { cancelRunsOfGoneTabs(imageRuns, list.map { it.id }.toSet()) }
    fun sendPaste(target: SshSession, text: String, submission: DraftSubmission?, connection: SshSession.PasteTarget, execute: Boolean, selection: Any?) {
        val result = attemptPaste(
            available = {
                (!execute || pasteExecutionSelected(selection, currentSelection.value, target.id, current.value.id)) &&
                    sessions.find(target.id) === target && target.state.value == SshState.Open &&
                    !sessionHidden(pendingUndo.hidden.value, SessionKind.TERMINAL, target.id, TunnelRef(target.profile, target.device, target.port))
            },
            paste = {
                // Literal text; explicit long action appends CR inside the captured
                // output transaction, never via whichever tab happens to be current.
                modifiers.clear()
                target.paste(text, connection, execute)
            },
        )
        when (result) {
            PasteAttempt.UNAVAILABLE -> context.notice(context.getString(R.string.terminal_not_open), NoticeKind.WARN)
            PasteAttempt.FAILED -> context.notice(context.getString(R.string.terminal_text_insert_failed), NoticeKind.WARN)
            PasteAttempt.INSERTED -> if (submission != null) {
                val consumed = sessions.drafts.inserted(submission)
                if (consumed && current.value.id == submission.sessionId) {
                    editorValue = TextFieldValue()
                    focusManager.clearFocus(force = true)
                    input.onEditorKeyboard = null
                    // Return control without implicitly showing a hidden IME or sending Enter.
                    input.requestFocus()
                }
            }
        }
    }
    fun insertDraft(execute: Boolean) {
        if (pasteAsk != null || currentEditorSurface.value !== editorSurface || current.value.id != selected.id) return
        val live = sessions.drafts.state.value[selected.id] ?: return
        if (!live.textMode || live.expanded != draft.expanded) return
        val connection = selected.capturePasteTarget() ?: run {
            context.notice(context.getString(R.string.terminal_not_open), NoticeKind.WARN)
            return
        }
        focusManager.clearFocus(force = true)
        val submission = sessions.drafts.submission(selected.id) ?: return
        when (val plan = planPaste(submission.text, pasteBracketed(selected.terminal.bracketedPaste, selected.tmux.inUse))) {
            null -> Unit
            is PastePlan.Paste -> sendPaste(selected, plan.text, submission, connection, execute, selectionToken)
            is PastePlan.Ask -> pasteAsk = PendingTerminalPaste(selected, plan, connection, submission, execute, selectionToken)
        }
    }
    fun setTextMode(enabled: Boolean) {
        if (current.value.id != selected.id || enabled == draft.textMode) return
        // Invalidate callbacks from the outgoing editor before moving focus.
        sessions.drafts.textMode(selected.id, enabled)
        focusManager.clearFocus(force = true)
        if (enabled) {
            input.finishComposition()
            modifiers.clear()
            input.clearFocus()
        }
        if (!enabled) {
            input.onEditorKeyboard = null
            input.showKeyboard(explicit = true)
        }
    }
    fun editDraft(value: TextFieldValue) {
        if (currentEditorSurface.value !== editorSurface) return
        if (sessions.drafts.editFromSurface(
                selected.id, value.text, value.selection.start, value.selection.end, draft.expanded, current.value.id,
            )
        ) editorValue = value
    }
    DisposableEffect(view) {
        sessions.listener = object : TerminalScreenListener {
            override fun onScreenChanged(session: TerminalSession) {
                if (view.currentSession === session) view.onScreenUpdated()
            }

            override fun onCursorStateChanged(visible: Boolean) = Unit

            override fun onPasteAsk(session: SshSession, ask: PastePlan.Ask) {
                val connection = session.capturePasteTarget()
                if (connection != null) pasteAsk = PendingTerminalPaste(session, ask, connection)
                else context.notice(context.getString(R.string.terminal_not_open), NoticeKind.WARN)
            }

            override fun onPasteImage(session: SshSession) {
                if (session.state.value != SshState.Open) context.notice(context.getString(R.string.terminal_not_open), NoticeKind.WARN)
                else if (imageRuns[session.id] == null) imageOffer.offer(session)
            }
        }
        onDispose { sessions.listener = null; input.diagnostics = null }
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
        if (!draft.textMode) input.requestFocus()
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
                    IconButton(onClick = { onOpenFiles(selected) }) {
                        Icon(FolderGlyph, contentDescription = stringResource(R.string.files), modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { setFullscreen(true) }) {
                        Icon(FullscreenGlyph, contentDescription = stringResource(R.string.fullscreen), modifier = Modifier.size(20.dp))
                    }
                    Box {
                        IconButton(onClick = { barMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        AppDropdownMenu(expanded = barMenu, onDismissRequest = { barMenu = false }) {
                            // Text size stays here, not on the bar, so the device name fits; the menu stays open while sizing.
                            Row(
                                Modifier.padding(start = 16.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.terminal_text_size), Modifier.weight(1f, fill = false).padding(end = 12.dp))
                                BarButton("A\u2212", smaller, enabled = fontSp > MIN_FONT_SP, onMenu = true) {
                                    sessions.fontSize.value = (fontSp - 1).coerceAtLeast(MIN_FONT_SP)
                                }
                                BarButton("A+", larger, enabled = fontSp < MAX_FONT_SP, onMenu = true) {
                                    sessions.fontSize.value = (fontSp + 1).coerceAtMost(MAX_FONT_SP)
                                }
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.terminal_text_input)) },
                                onClick = {
                                    barMenu = false
                                    if (draft.textMode) input.showKeyboard(explicit = true) else setTextMode(true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.snippets)) },
                                onClick = { barMenu = false; managing = true },
                            )
                            InsertImageMenuItem(
                                enabled = canInsertImage,
                                onClick = {
                                    barMenu = false
                                    pickImageFor(selected)
                                },
                            )
                            // Off while the device is being asked: a second tap would only ask again.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.terminal_close_all, selected.device)) },
                                enabled = !closingAll,
                                onClick = {
                                    barMenu = false
                                    closingAll = true
                                    val from = selected
                                    scope.launch {
                                        try {
                                            closeAllOfPhone(context, sessions, pendingUndo, from)
                                        } finally {
                                            closingAll = false
                                        }
                                    }
                                },
                            )
                        }
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
                if (fullscreen && state != SshState.Open && state !is SshState.Ended && state !is SshState.Reconnecting) {
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
                // Back after a drop in a fresh shell: said plainly until the owner types.
                if (newShell && state == SshState.Open) {
                    Text(
                        stringResource(R.string.ssh_new_shell),
                        fontSize = 13.sp,
                        color = colors.onTerm,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.termBar)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                // Not while the editor is expanded: its own window covers this one, and carries the bar itself.
                if (!(draft.textMode && draft.expanded)) {
                    imageRuns[selected.id]?.takeIf { !it.cancelling }?.let { run -> ImageProgressBar(run.state, onCancel = run::cancel, keysShown = !draft.textMode) }
                }
                (state as? SshState.Reconnecting)?.let { reconnecting ->
                    // The same bar as Ended: what happens, and what the owner can do meanwhile.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.termBar)
                            .padding(start = 12.dp, end = 4.dp, top = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.terminal_reconnecting, reconnecting.attempt),
                            fontSize = 13.sp,
                            color = colors.onTerm,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Row(modifier = Modifier.align(Alignment.End)) {
                            // Off while a try is under way: a tap shows at once that one started.
                            TextButton(
                                onClick = selected::reconnectNow,
                                enabled = !reconnecting.trying,
                                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                            ) { Text(stringResource(R.string.terminal_reconnect_now)) }
                            // As closing the tab: it goes at once, with Undo on the notice.
                            TextButton(
                                onClick = { closeTab(selected.id) },
                                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                            ) { Text(stringResource(R.string.close)) }
                        }
                    }
                }
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
                // Text mode is only the editor; saved commands remain in Keys and the menu.
                if (!draft.textMode) SnippetsRow(
                    snippets = snippets,
                    onSend = { text, run ->
                        if (state != SshState.Open) {
                            context.notice(notOpen, NoticeKind.WARN)
                        } else {
                            // The Enter key sends CR; so do line breaks inside a command.
                            val line = text.replace("\r\n", "\r").replace('\n', '\r')
                            // Typed first: the snippet must not land in tmux's copy mode.
                            selected.typed()
                            selected.terminal.session.write(if (run) line + "\r" else line)
                            // A snippet is typed as is, without a held Ctrl / Alt / Shift; it uses up the hold.
                            modifiers.clear()
                        }
                    },
                    onManage = { managing = true },
                )
                ExtraKeys(
                    modifiers = modifiers,
                    textMode = draft.textMode,
                    // Refresh the remembered long-click capture on tab changes; a local function reference can stay stale.
                    onTextMode = { enabled -> setTextMode(enabled) },
                    textEditor = {
                        if (!draft.expanded) TerminalTextComposer(
                            sessionId = selected.id,
                            value = editorValue,
                            onValueChange = ::editDraft,
                            focus = editorFocus,
                            canInsert = state == SshState.Open && pasteAsk == null,
                            onInsert = { insertDraft(false) },
                            onInsertAndEnter = { insertDraft(true) },
                            onExpand = { sessions.drafts.expand(selected.id, true); focusManager.clearFocus(force = true) },
                            onKeys = { setTextMode(false) },
                            canInsertImage = canInsertImage,
                            onInsertImage = { pickImageFor(selected) },
                        )
                    },
                    onKeyboard = { show -> if (show) input.showKeyboard(explicit = true) else input.hideKeyboard() },
                    // The same paste as the selection menu's: bracketed when the remote side asked for it.
                    onPaste = { view.currentSession?.onPasteTextFromClipboard() },
                    canOfferImage = canInsertImage,
                    onInsertImage = { pickImageFor(selected) },
                    onKey = { key ->
                        selected.typed()
                        sendExtraKey(view, key, modifiers)
                    },
                )
            }
            if (fullscreen) CollapseButton { setFullscreen(false) }
        }
    }
    if (draft.textMode && draft.expanded) {
        FullscreenTerminalText(onCollapse = { sessions.drafts.expand(selected.id, false) }) {
            imageRuns[selected.id]?.takeIf { !it.cancelling }?.let { run -> ImageProgressBar(run.state, onCancel = run::cancel, keysShown = false) }
            TerminalTextComposer(
                sessionId = selected.id,
                value = editorValue,
                onValueChange = ::editDraft,
                focus = editorFocus,
                canInsert = state == SshState.Open && pasteAsk == null,
                onInsert = { insertDraft(false) },
                onInsertAndEnter = { insertDraft(true) },
                onExpand = { sessions.drafts.expand(selected.id, false) },
                expanded = true,
                onKeys = { setTextMode(false) },
                canInsertImage = canInsertImage,
                onInsertImage = { pickImageFor(selected) },
            )
        }
    }
    // Compose after the full-screen editor so confirmation is always above it.
    imageOffer.pending?.let { tab ->
        ImageOfferSheet(
            onInsert = {
                val uris = imageOffer.confirm()
                if (uris.isNotEmpty()) startImageInsert(scope, context, tab, uris, imageRuns, visibleTab(tab))
                else context.notice(context.getString(R.string.terminal_image_unreadable), NoticeKind.WARN)
            },
            onDismiss = { imageOffer.dismiss() },
        )
    }
    pasteAsk?.let { pending ->
        PasteSheet(
            pending.ask,
            execute = pending.execute,
            onPaste = {
                if (pasteAsk === pending) {
                    pasteAsk = null
                    sendPaste(pending.session, it, pending.submission, pending.connection, pending.execute, pending.selection)
                }
            },
            onDismiss = { if (pasteAsk === pending) pasteAsk = null },
        )
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
                val auth = method.toAuth(password, remember)
                if (loginReplaces) {
                    openedOrGone(sessions.reconnect(from.id, login.user, auth))
                } else {
                    openedOrGone(sessions.openLike(from.id, login.user, auth))
                }
            },
            onDismiss = { loginLikeId = null },
        )
    }

    KeyLoginDialogs(keyLogin)

    when (val s = state) {
        is SshState.HostKeyUnknown -> HostKeyTrustDialog(
            "${selected.device}:${selected.port}", s.presented,
            onTrust = selected::trustHostKey,
            onCancel = { dropTab(selected.id) },
        )
        is SshState.HostKeyChanged -> HostKeyChangedDialog(
            "${selected.device}:${selected.port}", s.pinned, s.presented,
            onForget = selected::forgetOldHostKey,
            onCancel = { dropTab(selected.id) },
        )
        else -> Unit
    }
}

/** A narrow text button of the terminal's bar, so the device's name keeps its room. */
@Composable
private fun BarButton(text: String, description: String, enabled: Boolean = true, onMenu: Boolean = false, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .size(width = 44.dp, height = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = when {
            !enabled -> colors.termMuted
            onMenu -> colors.ink
            else -> colors.onTerm
        })
    }
}

@Composable
private fun statusLine(session: SshSession, state: SshState): String = session.user + " \u00B7 " + when (state) {
    SshState.Connecting -> stringResource(R.string.terminal_state_connecting)
    SshState.Open -> stringResource(R.string.terminal_state_open)
    is SshState.HostKeyUnknown -> stringResource(R.string.terminal_status_host_key_unknown)
    is SshState.HostKeyChanged -> stringResource(R.string.terminal_status_host_key_changed)
    is SshState.Reconnecting -> stringResource(R.string.terminal_state_reconnecting)
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

    // The owner's own input (not the terminal's replies to the remote side) ends the "new shell" bar.
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        session().typed(measureInput = !KeyEvent.isModifierKey(keyCode))
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false

    override fun onLongPress(event: MotionEvent) = false

    override fun readControlKey() = modifiers.takeCtrl()

    override fun readAltKey() = modifiers.takeAlt()

    // Termux reads it per typed character of the soft keyboard (and for hardware keys), then upper-cases.
    override fun readShiftKey() = modifiers.takeShift()

    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        session().typed()
        return false
    }

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

internal const val MIN_FONT_SP = 8
internal const val MAX_FONT_SP = 28
