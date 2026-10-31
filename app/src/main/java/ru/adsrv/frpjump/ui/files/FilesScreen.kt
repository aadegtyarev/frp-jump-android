package ru.adsrv.frpjump.ui.files

import android.widget.Toast
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.files.EntryType
import ru.adsrv.frpjump.files.RemoteEntry
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.AppTopBar
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.PlusGlyph
import ru.adsrv.frpjump.ui.shortTime
import ru.adsrv.frpjump.ui.ssh.HostKeyChangedDialog
import ru.adsrv.frpjump.ui.ssh.HostKeyTrustDialog
import java.time.Instant

/**
 * A device's files over SFTP: the path bar, the folder's entries, "+" to
 * upload; Back goes up a folder, at `/` it leaves. Offline, the folder as
 * last seen, marked "No connection".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: FilesViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = AppTheme.colors
    var menu by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf<String?>(null) }
    var typing by rememberSaveable { mutableStateOf(false) }
    // The file to download once a folder is picked.
    var waiting by remember { mutableStateOf<RemoteEntry?>(null) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val entry = waiting
        waiting = null
        if (tree != null) vm.setDownloadFolder(tree, entry)
    }
    val pickUploads = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.upload(it) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is FilesEvent.Toast -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                is FilesEvent.Handover -> handOver(context, event.file, event.mime, event.name, event.share)
                is FilesEvent.PickFolder -> {
                    waiting = event.entry
                    pickFolder.launch(null)
                }
            }
        }
    }
    BackHandler { if (!vm.up()) onBack() }

    // Where each folder was scrolled to: going back up returns there, and an action keeps it.
    val list = rememberLazyListState()
    val positions = remember { mutableMapOf<String, Pair<Int, Int>>() }
    var shownPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.path) {
        val path = state.path ?: return@LaunchedEffect
        if (path == shownPath) return@LaunchedEffect
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
            AppTopBar(
                title = title,
                subtitle = t?.let { it.user + " · " + statusWord(state.status) },
                onBack = onBack,
                actions = {
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
                                    waiting = null
                                    pickFolder.launch(null)
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.online) {
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
            StatusBanner(state.status, onRetry = vm::refresh)
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
                    items(state.uploads.size, key = { "upload-$it" }) { UploadRow(state.uploads[it]) }
                    if (state.entries.isEmpty()) item(key = "empty") { EmptyFolder(state) }
                    items(state.entries, key = { it.name }) { entry ->
                        EntryRow(
                            entry = entry,
                            transfer = state.downloads[entry.path],
                            online = state.online,
                            menuOpen = rowMenu == entry.path,
                            onMenu = { rowMenu = if (it) entry.path else null },
                            onTap = {
                                when {
                                    entry.isDir -> vm.open(entry.path)
                                    entry.link?.broken == true -> Toast.makeText(context, context.getString(R.string.files_broken_toast), Toast.LENGTH_SHORT).show()
                                    entry.type == EntryType.FILE -> vm.openWith(entry)
                                    else -> Toast.makeText(context, context.getString(R.string.files_not_regular, entry.name), Toast.LENGTH_SHORT).show()
                                }
                            },
                            actions = EntryActions(
                                open = { vm.open(it.path) },
                                download = vm::download,
                                share = vm::share,
                                goToTarget = vm::goToTarget,
                            ),
                        )
                        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 70.dp))
                    }
                }
            }
        }
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
private fun StatusBanner(status: FilesStatus, onRetry: () -> Unit) {
    val colors = AppTheme.colors
    val (text, detail, retry) = when (status) {
        is FilesStatus.Offline -> Triple(
            status.cachedAt?.let { stringResource(R.string.files_offline_cached, shortTime(Instant.ofEpochMilli(it))) }
                ?: stringResource(R.string.files_offline_none),
            status.reason,
            stringResource(R.string.retry),
        )
        is FilesStatus.Failed -> Triple(status.message, null, stringResource(R.string.retry))
        FilesStatus.TunnelConnecting -> Triple(stringResource(R.string.files_tunnel_connecting), null, null)
        else -> return
    }
    val error = status is FilesStatus.Failed
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(if (error) colors.errorContainer else colors.warningContainer, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ink = if (error) colors.onErrorContainer else colors.onWarningContainer
        if (retry == null) CircularProgressIndicator(Modifier.size(16.dp), color = ink, strokeWidth = 2.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, fontSize = 14.sp, color = ink)
            detail?.let { Text(it, fontSize = 12.sp, color = ink) }
        }
        retry?.let { TextButton(onClick = onRetry) { Text(it, color = ink) } }
    }
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
