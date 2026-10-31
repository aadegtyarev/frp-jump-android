package ru.adsrv.frpjump.ui.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.files.ListingCache
import ru.adsrv.frpjump.files.RemoteEntry
import ru.adsrv.frpjump.files.nameMatches
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.AppTopBar
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.PlusGlyph
import ru.adsrv.frpjump.ui.SearchBar
import ru.adsrv.frpjump.ui.SearchGlyph
import ru.adsrv.frpjump.ui.StatusBanner
import ru.adsrv.frpjump.ui.notice
import ru.adsrv.frpjump.ui.shortTime
import ru.adsrv.frpjump.ui.ssh.HostKeyChangedDialog
import ru.adsrv.frpjump.ui.ssh.HostKeyTrustDialog
import ru.adsrv.frpjump.ui.ssh.SshLoginDialog
import java.time.Instant

/**
 * A device's files over SFTP: the path bar, the folder's entries, "+" to
 * upload; Back goes up a folder, at `/` it leaves. Offline, the folder as
 * last seen, marked "No connection".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: FilesSession, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = AppTheme.colors
    var menu by remember { mutableStateOf(false) }
    // The path of the entry whose card is open.
    var card by rememberSaveable { mutableStateOf<String?>(null) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var changingLogin by remember { mutableStateOf(false) }
    // The search field over the top bar, as on Devices and in the MQTT explorer.
    var searching by rememberSaveable { mutableStateOf(false) }
    var focusSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val closeSearch = {
        searching = false
        focusSearch = false
        query = ""
        vm.clearSearch()
    }
    // The files to download once a folder is picked.
    var waiting by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val entries = waiting
        waiting = emptyList()
        if (tree != null) vm.setDownloadFolder(tree, entries)
    }
    val pickUploads = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.upload(it) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is FilesEvent.Notice -> context.notice(event.text, event.kind)
                is FilesEvent.Handover -> {
                    // Handed to another app: that file's card has done its job.
                    if (card == event.path) card = null
                    handOver(context, event.file, event.mime, event.name, event.share)
                }
                is FilesEvent.ShareMany -> shareAll(context, event.files)
                is FilesEvent.PickFolder -> {
                    waiting = event.entries
                    pickFolder.launch(null)
                }
                is FilesEvent.ShowCard -> card = event.path
            }
        }
    }
    // Leaving cancels the transfers going: ask first when there are any.
    // Failed transfers when the question was asked; null while it is not up.
    var leaving by remember { mutableStateOf<Int?>(null) }
    val leave: () -> Unit = {
        val now = vm.state.value
        if (now.running == 0) onBack() else { leaving = now.failed }
    }
    BackHandler { if (!vm.up()) leave() }
    // Declared later, so it comes first: Back closes the search before it goes up a folder.
    BackHandler(enabled = searching) { closeSearch() }
    // Declared later, so it comes first: Back ends a selection before it goes up a folder.
    BackHandler(enabled = state.selection.active) { vm.clearSelection() }

    // Where each folder was scrolled to: going back up returns there, and an action keeps it;
    // kept in the session, so a kept one opens again where it was.
    val list = rememberLazyListState()
    val positions = vm.positions
    var shownPath by remember { mutableStateOf<String?>(null) }
    DisposableEffect(vm) {
        onDispose { shownPath?.let { positions[it] = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset } }
    }
    LaunchedEffect(state.path) {
        val path = state.path ?: return@LaunchedEffect
        if (path == shownPath) return@LaunchedEffect
        // Another folder opened (from the matches, a hit, the path bar): the search was for the one before.
        if (shownPath != null && searching) closeSearch()
        shownPath?.let { positions[it] = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        shownPath = path
        val (index, offset) = positions[path] ?: (0 to 0)
        list.scrollToItem(index, offset)
    }

    val t = vm.target
    val title = t?.let { it.device + if (it.port != 22) ":${it.port}" else "" } ?: stringResource(R.string.files)
    Scaffold(
        containerColor = colors.ground,
        topBar = {
            if (state.selection.active) {
                SelectionBar(
                    count = state.selection.count,
                    available = selectionActions(state.selection.picked(state.entries)),
                    online = state.online,
                    onClear = vm::clearSelection,
                    onSelectAll = vm::selectAll,
                    onAction = { action ->
                        when (action) {
                            SelectionAction.DOWNLOAD -> vm.downloadSelected()
                            SelectionAction.SHARE -> vm.shareSelected()
                        }
                    },
                )
            } else if (searching) {
                SearchBar(
                    query,
                    onQuery = { typed ->
                        // Other text: the search in subfolders was for the old one.
                        if (typed.trim() != query.trim()) vm.clearSearch()
                        query = typed
                    },
                    onClose = closeSearch,
                    focus = focusSearch,
                    onFocused = { focusSearch = false },
                    label = stringResource(R.string.files_search),
                    hint = stringResource(R.string.files_search_hint),
                )
            } else AppTopBar(
                title = title,
                subtitle = t?.let { state.user + " · " + statusWord(state.status) },
                onBack = leave,
                actions = {
                    if (state.status != FilesStatus.Gone) {
                        IconButton(onClick = {
                            searching = true
                            focusSearch = true
                        }) {
                            Icon(SearchGlyph, contentDescription = stringResource(R.string.files_search), tint = colors.ink, modifier = Modifier.size(22.dp))
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.files_actions))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_show_hidden)) },
                                trailingIcon = { Checkbox(checked = state.showHidden, onCheckedChange = null) },
                                onClick = {
                                    menu = false
                                    vm.setShowHidden(!state.showHidden)
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(state.downloadFolder?.let { stringResource(R.string.files_download_folder, it) } ?: stringResource(R.string.files_choose_download_folder))
                                },
                                onClick = {
                                    menu = false
                                    waiting = emptyList()
                                    pickFolder.launch(null)
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // While selecting, the bar has the actions; "+" would be one too many.
            if (state.online && !state.selection.active) {
                FloatingActionButton(
                    onClick = { pickUploads.launch(arrayOf("*/*")) },
                    containerColor = colors.strong,
                    contentColor = colors.onStrong,
                    shape = RoundedCornerShape(18.dp),
                ) { Icon(PlusGlyph, contentDescription = stringResource(R.string.files_upload), modifier = Modifier.size(22.dp)) }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            if (state.status != FilesStatus.Gone) {
                PathBar(state.path, onOpen = vm::open, onType = { typing = true })
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (state.busy && state.entries.isNotEmpty()) LinearProgressIndicator(color = colors.accent, trackColor = colors.ground, modifier = Modifier.fillMaxWidth())
            }
            FilesBanner(state.status, onRetry = vm::refresh, onChangeLogin = { changingLogin = true })
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    // The last row scrolls clear of the "+" button and the navigation bar.
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 88.dp),
                ) {
                    items(state.uploads, key = { "upload-${it.id}" }) { upload ->
                        UploadRow(
                            upload.transfer,
                            onRetry = { vm.retryUpload(upload.id) },
                            onDismiss = { vm.dismissUpload(upload.id) },
                            onCancel = { vm.cancelUpload(upload.id) },
                        )
                    }
                    val text = query.trim()
                    val filtering = searching && text.isNotEmpty()
                    val shown = if (filtering) state.entries.filter { nameMatches(it.name, text) } else state.entries
                    if (state.entries.isEmpty()) {
                        item(key = "empty") { EmptyFolder(state) }
                    } else if (shown.isEmpty()) {
                        item(key = "/no-match") { NoNameMatch(text) }
                    }
                    items(shown, key = { it.name }) { entry ->
                        val selecting = state.selection.active
                        EntryRow(
                            entry = entry,
                            transfer = state.downloads[entry.path],
                            selecting = selecting,
                            selected = entry in state.selection,
                            onTap = {
                                when {
                                    selecting -> vm.toggleSelected(entry)
                                    entry.isDir -> vm.openFolder(entry)
                                    // A file: its card first; nothing downloads until an action is picked.
                                    else -> card = entry.path
                                }
                            },
                            // "Select all" takes the whole folder: no selection from a filtered list.
                            onLongPress = { if (!searching) vm.toggleSelected(entry) },
                            onDetails = { card = entry.path },
                            onRetry = { vm.retry(entry) },
                            onDismiss = { vm.dismissFailure(entry.path) },
                            onCancel = { vm.cancelDownload(entry.path) },
                        )
                        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 70.dp))
                    }
                    if (filtering && state.path != null && state.status != FilesStatus.Gone) {
                        subfolderSearch(
                            query = text,
                            search = state.deepSearch?.takeIf { it.query == text && it.root == state.path },
                            online = state.online,
                            onStart = { vm.searchSubfolders(text) },
                            onStop = vm::stopSearch,
                            onOpen = vm::openFound,
                        )
                    }
                }
            }
        }
    }

    leaving?.let { failedAtAsk ->
        val running = state.running
        val answer = leaveAnswer(running, failedAtAsk, state.failed)
        // They ended while the question was up: leave as asked when all went well, else stay on the failures.
        LaunchedEffect(answer) {
            when (answer) {
                LeaveAnswer.ASK -> Unit
                LeaveAnswer.STAY -> leaving = null
                LeaveAnswer.LEAVE -> {
                    leaving = null
                    onBack()
                }
            }
        }
        if (answer == LeaveAnswer.ASK) {
            AlertDialog(
                onDismissRequest = { leaving = null },
                title = { Text(pluralStringResource(R.plurals.files_leave_title, running, running)) },
                text = { Text(stringResource(R.string.files_leave_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        leaving = null
                        onBack()
                    }) { Text(stringResource(R.string.files_leave), color = colors.error) }
                },
                dismissButton = { TextButton(onClick = { leaving = null }) { Text(stringResource(R.string.files_stay)) } },
            )
        }
    }
    // A hit in a subfolder has its card too, over the hits.
    val carded = card?.let { path -> state.entries.firstOrNull { it.path == path } ?: state.foundEntry?.takeIf { it.path == path } }
    // The folder changed under the card (or the entry went): it closes rather than show something stale.
    LaunchedEffect(card, carded == null) { if (card != null && carded == null && state.path != null) card = null }
    carded?.let { entry ->
        FileCard(
            entry = entry,
            transfer = state.downloads[entry.path],
            online = state.online,
            actions = CardActions(
                open = { if (it.isDir) vm.openFolder(it) else vm.openWith(it) },
                download = vm::download,
                share = vm::share,
                goToTarget = vm::goToTarget,
                retry = vm::retry,
                dismissFailure = { vm.dismissFailure(it.path) },
                cancel = { vm.cancelDownload(it.path) },
            ),
            onDismiss = { card = null },
        )
    }
    if (typing) {
        GoToFolderDialog(
            current = state.path ?: "/",
            onGo = { typed ->
                typing = false
                vm.openTyped(typed)
            },
            onDismiss = { typing = false },
        )
    }
    val target = t?.let { "${it.device}:${it.port}" }.orEmpty()
    if (changingLogin) {
        SshLoginDialog(
            target = target,
            initial = vm.login(),
            hasRememberedPassword = vm::hasRememberedPassword,
            onConnect = { user, method, password, remember ->
                changingLogin = false
                vm.changeLogin(user, method, password, remember)
            },
            onDismiss = { changingLogin = false },
        )
    }
    when (val s = state.status) {
        is FilesStatus.HostKeyUnknown -> HostKeyTrustDialog(target, s.presented, onTrust = vm::trustHostKey, onCancel = onBack)
        is FilesStatus.HostKeyChanged -> HostKeyChangedDialog(target, s.pinned, s.presented, onForget = vm::forgetOldHostKey, onCancel = onBack)
        else -> Unit
    }
}

@Composable
private fun statusWord(status: FilesStatus): String = stringResource(
    when (status) {
        FilesStatus.Ready -> R.string.terminal_state_open
        FilesStatus.TunnelConnecting -> R.string.files_tunnel_connecting
        is FilesStatus.Offline, FilesStatus.Gone -> R.string.files_no_connection
        is FilesStatus.Failed -> R.string.terminal_state_ended
        is FilesStatus.HostKeyUnknown -> R.string.terminal_status_host_key_unknown
        is FilesStatus.HostKeyChanged -> R.string.terminal_status_host_key_changed
        FilesStatus.Starting -> R.string.files_connecting
    },
)

/** Why the folder is not live, next to it, with what may fix it. */
@Composable
private fun FilesBanner(status: FilesStatus, onRetry: () -> Unit, onChangeLogin: () -> Unit) {
    val (text, detail, retry) = when (status) {
        is FilesStatus.Offline -> Triple(
            status.cachedAt?.let { stringResource(R.string.files_offline_cached, shortTime(Instant.ofEpochMilli(it))) }
                ?: stringResource(R.string.files_offline_none),
            listOfNotNull(
                status.reason,
                stringResource(R.string.files_truncated, ListingCache.MAX_ENTRIES).takeIf { status.truncated },
            ).joinToString("\n"),
            stringResource(R.string.retry),
        )
        // A refused login does not get better by trying again: change it.
        is FilesStatus.Failed -> Triple(status.message, null, stringResource(if (status.loginRefused) R.string.files_change_login else R.string.retry))
        FilesStatus.TunnelConnecting -> Triple(stringResource(R.string.files_tunnel_connecting), null, null)
        else -> return
    }
    StatusBanner(
        text = text,
        detail = detail,
        error = status is FilesStatus.Failed,
        busy = retry == null,
        action = retry,
        onAction = if ((status as? FilesStatus.Failed)?.loginRefused == true) onChangeLogin else onRetry,
    )
}

/** An empty list says why: still connecting, an empty folder, only hidden files, or nothing known offline. */
@Composable
private fun EmptyFolder(state: FilesUiState) {
    val colors = AppTheme.colors
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        when {
            state.status == FilesStatus.Gone -> Text(stringResource(R.string.files_gone), color = colors.muted, fontSize = 14.sp)
            state.status == FilesStatus.Starting || state.busy -> CircularProgressIndicator(color = colors.accent)
            state.status == FilesStatus.Ready && state.hasHidden -> Text(stringResource(R.string.files_hidden_only), color = colors.muted, fontSize = 14.sp)
            state.status == FilesStatus.Ready -> Text(stringResource(R.string.files_empty), color = colors.muted, fontSize = 14.sp)
            else -> Unit
        }
    }
}

/** "Tap the path to type one": absolute, relative to the folder shown, or `..`. */
@Composable
private fun GoToFolderDialog(current: String, onGo: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_go_to_folder)) },
        text = {
            Field(
                label = stringResource(R.string.files_path),
                value = text,
                onValueChange = { text = it },
                mono = true,
                keyboard = KeyboardType.Uri,
                focusRequester = focus,
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onGo(text) }) { Text(stringResource(R.string.files_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
