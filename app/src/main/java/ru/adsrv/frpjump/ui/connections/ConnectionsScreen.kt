package ru.adsrv.frpjump.ui.connections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import ru.adsrv.frpjump.ui.AppModalBottomSheet
import ru.adsrv.frpjump.ui.SheetHeader
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.connections.onTunnelCount
import ru.adsrv.frpjump.connections.TunnelsView
import ru.adsrv.frpjump.connections.tunnelsView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.snapshotFlow
import ru.adsrv.frpjump.tunnel.SessionOn
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import ru.adsrv.frpjump.ui.AssistantGlyph
import ru.adsrv.frpjump.ui.devices.OpenRow
import ru.adsrv.frpjump.ui.devices.SwipeReveal
import ru.adsrv.frpjump.ui.devices.closesOpenRow
import ru.adsrv.frpjump.ui.devices.touchesRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.connections.DeviceGroup
import kotlinx.coroutines.awaitCancellation
import ru.adsrv.frpjump.tunnel.Waiting
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.caption
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.ui.devices.DeviceTile
import ru.adsrv.frpjump.ui.userText
import ru.adsrv.frpjump.ui.userMessage
import ru.adsrv.frpjump.ui.wasOnline

/**
 * The Connections tab: every saved connection of every profile, under its
 * device; devices with active connections first, then online ones, then
 * the rest. Each connection is the device card's own card. Search by
 * device or connection name once there are many.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectionsScreen(
    vm: ConnectionsViewModel,
    scopeRequest: ArrayList<String>? = null,
    onScopeConsumed: () -> Unit = {},
    onOpenWeb: (ConnectionRow) -> Unit,
    onOpenTerminal: (sessionId: Int) -> Unit,
    onOpenFiles: (targetId: Int) -> Unit,
    onOpenMqtt: (sessionId: Int) -> Unit,
    /** Opens the assistant chat with this draft (nothing is sent); null while the assistant is off: no such menu item and no such swipe. */
    onAskAssistant: ((draft: String) -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions by vm.actions.sessions.collectAsStateWithLifecycle(emptyList())
    val grants by vm.actions.assistantGrants.collectAsStateWithLifecycle()
    val ui = remember { ConnectionUi() }
    val actions = rememberRowActions(ui, vm.actions, onOpenWeb, onOpenTerminal, onOpenFiles, onOpenMqtt, onAskAssistant)
    // While the tab shows, the device lists are kept fresh, as on Devices.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.shown(true)
            try {
                awaitCancellation()
            } finally {
                vm.shown(false)
            }
        }
    }
    val (onlyOn, setOnlyOn) = rememberOnlyOnFilter()
    var adding by remember { mutableStateOf<DeviceGroup?>(null) }
    ConnectionsContent(state, sessions, grants, ui, actions, scopeRequest, onScopeConsumed, onlyOn, setOnlyOn,
        onPull = vm::pull, onDisconnectAll = { done -> vm.actions.disconnectAll(done) },
        onDisconnectSelected = vm.actions::disconnectSelected, onForgetAssistant = vm.actions::forgetAssistant,
        onAdd = { adding = it })
    ConnectionDialogs(ui, vm.actions, existing = vm::connections, rows = state.rows, onOpenTerminal = onOpenTerminal, onOpenFiles = onOpenFiles)
    adding?.let { group ->
        val notificationsFirst = rememberNotificationsFirst()
        ConnectionSheet(device = group.name, editing = null, portLocked = false, existing = vm.connections(group.profile), ports = rememberLocalPortContext(vm.actions, group.profile),
            onSave = { connection, connectNow, done ->
                val save = { vm.actions.save(group.profile, connection, previous = null, connectNow = connectNow, onDone = done) }
                if (connectNow) notificationsFirst(save) else save()
            }, onDismiss = { adding = null })
    }
}

/** Shared production content; synthetic tests supply state and callbacks without activating profile runtimes. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ConnectionsContent(
    state: ConnectionsUiState, sessions: List<SessionOn>, grants: Map<GrantTarget, AssistantMark>,
    ui: ConnectionUi, actions: RowActions, scopeRequest: ArrayList<String>?, onScopeConsumed: () -> Unit,
    onlyOn: Boolean, setOnlyOn: (Boolean) -> Unit, onPull: () -> Unit,
    onDisconnectAll: (() -> Unit) -> Unit, onDisconnectSelected: (Set<ru.adsrv.frpjump.tunnel.TunnelRef>) -> Unit,
    onForgetAssistant: (ConnectionRow) -> Unit, onAdd: (DeviceGroup) -> Unit,
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    // "Disconnect all": the tunnels show down at once, with Undo. Only one still being restored is told
    // to its server now: then it spins until every server was asked (the line below tells the rest).
    var disconnecting by remember { mutableStateOf(false) }

    val restoring = state.restoring?.takeIf { it > 0 }
    // Search: the header's pill expands to the whole header; ← closes it and clears the query, so does Back.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var focusOnOpen by rememberSaveable { mutableStateOf(false) }
    val closeSearch = {
        searching = false
        query = ""
        focusOnOpen = false
    }
    BackHandler(enabled = searching) { closeSearch() }
    var scope by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(stateSaver = listSaver(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(setOf<String>()) }
    LaunchedEffect(scopeRequest) {
        if (scopeRequest != null) {
            scope = scopeRequest
            searching = false; query = ""; focusOnOpen = false; selected = emptySet()
            onScopeConsumed()
        }
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    fun included(group: DeviceGroup) = inDeviceScope(scope, group)
    val scoped = state.groups.filter(::included)
    val all = scoped.flatMap { it.rows }
    val selectedActive = selectedTunnelRefs(all, selected)
    val label: (ConnectionRow) -> String = { row -> if (row.saved) connectionLabel(context, row.connection) else context.getString(R.string.connection_unsaved, row.connection.port) }
    val actionScope = ru.adsrv.frpjump.connections.filterGroups(scoped, query, label)
    val actionRows = actionScope.flatMap { it.rows }
    val actionActive = selectedTunnelRefs(actionRows, actionRows.map { it.key }.toSet())
    val count = actionActive.size + if (scope.isEmpty() && query.isBlank()) restoring ?: 0 else 0
    val view = tunnelsView(scoped, onlyOn, query, label)
    // The row swiped open, one at a time; Back closes it first. A finger landing anywhere else, a scroll or a pull closes it too.
    val openRow = remember { OpenRow() }
    BackHandler(enabled = openRow.name != null) { openRow.close() }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, openRow) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it) openRow.close() }
    }
    LaunchedEffect(state.refreshing) { if (state.refreshing) openRow.close() }
    Column(Modifier.fillMaxSize().closesOpenRow(openRow)) {
        if (selected.isEmpty()) TunnelsHeader(
            searching = searching,
            query = query,
            onQuery = { query = it },
            onOpenSearch = {
                searching = true
                focusOnOpen = true
            },
            onCloseSearch = closeSearch,
            focusOnOpen = focusOnOpen,
            onFocused = { focusOnOpen = false },
            scopeOn = view.filtered,
            filterOn = onlyOn && count > 0,
            count = count,
            onFilter = setOnlyOn,
            stopBusy = disconnecting,
            onStopAll = {
                if (scope.isEmpty() && query.isBlank()) { disconnecting = true; onDisconnectAll { disconnecting = false } }
                else onDisconnectSelected(actionActive)
            },
            deviceFilter = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = emptySet(); filterOpen = true }, modifier = Modifier.size(48.dp).background(colors.card, RoundedCornerShape(12.dp)).border(1.dp, colors.outline, RoundedCornerShape(12.dp)).testTag("tunnels_devices_filter")) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(ru.adsrv.frpjump.ui.DeviceGlyph, contentDescription = stringResource(R.string.tunnels_device_filter, scope.size / 2), tint = colors.ink)
                            if (scope.isNotEmpty()) Text("${scope.size / 2}", modifier = Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 3.dp), fontSize = 10.sp, color = colors.accent)
                        }
                    }
                    if (scope.isNotEmpty()) TextButton(onClick = { scope = arrayListOf(); selected = emptySet() }, modifier = Modifier.size(44.dp).testTag("tunnels_devices_reset").semantics { contentDescription = context.getString(R.string.tunnels_filter_reset) }) { Text("×") }
                }
            },
            modifier = Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        if (selected.isNotEmpty()) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("tunnels_selection"), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selected = emptySet() }) { Text("×", modifier = Modifier.semantics { contentDescription = context.getString(R.string.close) }) }
            Text(stringResource(R.string.tunnels_selected_count, selected.size), modifier = Modifier.weight(1f))
            IconButton(onClick = { selected = (view.favorites.map { it.row } + view.groups.flatMap { it.rows }).map { it.key }.toSet() }, modifier = Modifier.testTag("tunnels_select_all")) {
                Icon(ru.adsrv.frpjump.ui.SelectAllGlyph, contentDescription = stringResource(R.string.tunnels_select_all))
            }
            IconButton(onClick = { onDisconnectSelected(selectedActive); selected = emptySet() }, enabled = selectedActive.isNotEmpty(), modifier = Modifier.testTag("tunnels_stop_selected")) {
                Icon(ru.adsrv.frpjump.ui.PowerGlyph, contentDescription = stringResource(R.string.tunnels_stop_selected, selectedActive.size), tint = colors.error)
            }
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onPull,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "status") {
                    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.tunnelStates.filterKeys { profile -> scope.isEmpty() || scope.chunked(2).any { it.firstOrNull() == profile } }.forEach { (profile, tunnels) ->
                            tunnels.mismatch?.let { Text("${state.profileNames[profile].orEmpty()}: " + stringResource(R.string.mismatch_banner, it.userText()), color = colors.error) }
                            tunnels.syncError?.let { Text("${state.profileNames[profile].orEmpty()}: " + it.userText(), color = colors.error) }
                        }
                        // Where "Disconnect all" was tapped: why tunnels may come back, until they are back.
                        if (state.disconnectAllFailed && !disconnecting && restoring != null) {
                            Text(stringResource(R.string.disconnect_all_failed), fontSize = 14.sp, color = colors.error)
                        }
                        restoring?.let { count ->
                            val waiting = when (state.restoreWaiting) {
                                Waiting.UNLOCK -> stringResource(R.string.restore_waiting_unlock)
                                Waiting.NETWORK -> stringResource(R.string.restore_waiting_network)
                                null -> null
                            }
                            val line = pluralStringResource(R.plurals.tunnels_restoring, count, count)
                            Text(waiting?.let { "$line $it" } ?: line, fontSize = 14.sp, color = colors.muted)
                        }
                    }
                }
                if (state.loaded && all.isEmpty() && scope.isEmpty()) {
                    item(key = "empty") { Text(stringResource(R.string.connections_empty), fontSize = 14.sp, color = colors.muted, modifier = Modifier.padding(4.dp)) }
                }
                // A search that finds nothing says so, rather than leave an empty screen.
                if (all.isNotEmpty() && view.isEmpty) {
                    item(key = "no-match") {
                        Text(
                            stringResource(R.string.connections_no_match, query.trim()),
                            color = colors.muted,
                            modifier = Modifier
                                .padding(4.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                // Nothing on among the shown, but the tunnels that are off match: one line that turns the filter off.
                view.inAll?.let { found ->
                    item(key = "in-all") {
                        Text(
                            stringResource(R.string.connections_in_all, found),
                            color = colors.accent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { setOnlyOn(false) }
                                .padding(horizontal = 4.dp, vertical = 12.dp)
                                .testTag(TUNNELS_IN_ALL_TAG),
                        )
                    }
                }
                tunnelRows(view, state, sessions, grants, ui, actions, onForgetAssistant, openRow,
                    scope = scope, query = query, selected = selected,
                    onSelect = { selected = selected + it },
                    onToggle = { selected = if (it in selected) selected - it else selected + it },
                    onAdd = onAdd.takeIf { selected.isEmpty() })
            }
        }
    }
    if (filterOpen) DeviceFilterSheet(state.devices, state.profileNames, scope,
        onToggle = { device ->
            selected = emptySet()
            val checked = scope.isNotEmpty() && inDeviceScope(scope, device)
            scope = ArrayList(if (checked) scope.chunked(2).filterNot { it == listOf(device.profile, device.name) }.flatten() else scope + listOf(device.profile, device.name))
        }, onClear = { scope = arrayListOf(); selected = emptySet() }, onDismiss = { filterOpen = false })

}

/** Device filter uses the same profile/device identity as the list and routing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceFilterSheet(devices: List<DeviceGroup>, profileNames: Map<String, String>, scope: List<String>, onToggle: (DeviceGroup) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = AppTheme.colors
    val wanted = query.trim()
    val matches = devices.filter { device ->
        ru.adsrv.frpjump.devices.searchMatches(device.name, wanted) ||
            ru.adsrv.frpjump.devices.searchMatches(device.device?.label, wanted) ||
            ru.adsrv.frpjump.devices.searchMatches(profileNames[device.profile], wanted)
    }.sortedWith(compareBy<DeviceGroup> { profileNames[it.profile].orEmpty() }.thenBy { it.profile }.thenBy { it.name.lowercase(java.util.Locale.ROOT) })
    val multiProfile = devices.map { it.profile }.distinct().size > 1
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 16.dp)) {
            SheetHeader(sheet, onDismiss = onDismiss) {
                Text(if (scope.isEmpty()) stringResource(R.string.tunnels_picker_all) else stringResource(R.string.tunnels_selected_count, scope.size / 2), fontWeight = FontWeight.SemiBold)
            }
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.devices_search)) }, modifier = Modifier.fillMaxWidth().testTag("tunnels_filter_search"), singleLine = true)
            LazyColumn(Modifier.weight(1f, fill = false).testTag("tunnels_filter_list")) {
                matches.groupBy { it.profile }.forEach { (profile, entries) ->
                    if (multiProfile) item(key = "profile/$profile") {
                        Text(profileLabel(profile, profileNames), color = colors.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
                    }
                    items(entries, key = { it.key }) { device ->
                        val checked = scope.isNotEmpty() && inDeviceScope(scope, device)
                        val description = listOfNotNull(device.name, device.device?.label?.takeIf { it.isNotBlank() }, profileLabel(profile, profileNames).takeIf { multiProfile }).joinToString(" · ")
                        Row(Modifier.fillMaxWidth().testTag("tunnels_filter_device_${device.key}").heightIn(min = 52.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(device) }).clearAndSetSemantics {
                            contentDescription = description
                            role = Role.Checkbox
                            toggleableState = ToggleableState(checked)
                            onClick { onToggle(device); true }
                        }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(device.name)
                                device.device?.label?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.muted, fontSize = 12.sp) }
                            }
                            Checkbox(checked, onCheckedChange = null)
                        }
                    }
                }
                if (matches.isEmpty()) item { Text(stringResource(R.string.devices_no_match, query.trim()), color = colors.muted, modifier = Modifier.padding(12.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (scope.isNotEmpty()) TextButton(onClick = onClear, modifier = Modifier.testTag("tunnels_filter_clear")) { Text(stringResource(R.string.tunnels_picker_clear)) }
                Button(onClick = onDismiss, modifier = Modifier.testTag("tunnels_filter_done")) { Text(stringResource(R.string.tunnels_picker_done)) }
            }
        }
    }
}

/** Profile names can collide, so retain their distinct identities in the UI. */
internal fun profileLabel(id: String, names: Map<String, String>): String {
    val name = names[id].orEmpty().ifBlank { id }
    return if (names.count { it.value == name } > 1) "$name · $id" else name
}

/** One accessible checked target replaces the card's actions while selecting. */
@Composable
internal fun SelectableTunnelRow(label: String, selecting: Boolean, checked: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Box(if (selecting) Modifier.clearAndSetSemantics {
        contentDescription = label
        role = Role.Checkbox
        toggleableState = ToggleableState(checked)
        onClick { onToggle(); true }
    } else Modifier) {
        content()
        if (selecting) Surface(modifier = Modifier.matchParentSize().clickable(onClick = onToggle), color = AppTheme.colors.ground.copy(alpha = 0.30f)) {
            Box(contentAlignment = Alignment.TopEnd) { Checkbox(checked = checked, onCheckedChange = null) }
        }
    }
}

/**
 * The filter "only tunnels that are on" as last chosen (on the first time), and a setter that keeps the choice in the settings, in the app's
 * scope so that leaving the tab does not cancel the write; a write that fails says so.
 */
@Composable
private fun rememberOnlyOnFilter(): Pair<Boolean, (Boolean) -> Unit> {
    val app = LocalContext.current.applicationContext as FrpJumpApp
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    return settings.connectionsOnlyOn to { value: Boolean ->
        app.appScope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching { app.settings.update { it.copy(connectionsOnlyOn = value) } }.exceptionOrNull() }
            if (failure != null) app.notices.show(Texts.get(R.string.settings_save_failed, failure.userMessage()), NoticeKind.WARN, long = true)
        }
        Unit
    }
}

const val TUNNELS_IN_ALL_TAG = "tunnels_in_all"

/**
 * A device over its connections: its tile as on Devices, smaller (the
 * icon, its state as a dot in the Devices list's colours; no dot and the
 * generic glyph while its profile's list is not read), then its whole name
 * and, muted under it, its label, both wrapped and never cut; [profileColor]
 * as a bar before it when the tab shows more than one profile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupHeader(group: DeviceGroup, profileColor: String?, first: Boolean, profileName: String? = null) {
    val colors = AppTheme.colors
    val device = group.device
    val (dot, spoken) = when {
        device == null -> null to null
        !device.online -> colors.offline to stringResource(R.string.device_offline)
        // Online as of the last answer only.
        device.stale -> colors.online.copy(alpha = 0.4f) to wasOnline(device)
        else -> colors.online to stringResource(R.string.device_online)
    }
    val description = listOfNotNull(group.name, profileName, device?.label, spoken, device?.icon?.let { stringResource(it.caption) }).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = if (first) 4.dp else 16.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        profileColor?.let {
            Box(
                Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .background(colors.profile(it), RoundedCornerShape(2.dp)),
            )
        }
        DeviceTile(device?.icon, dot, size = 28.dp, ring = colors.ground)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(group.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            profileName?.let { Text("· $it", fontSize = 13.sp, color = colors.muted) }
            device?.label?.takeIf { it.isNotBlank() }?.let { Text("· $it", fontSize = 13.sp, color = colors.muted) }
        }
    }
}

/**
 * The rows of the Tunnels list: the favourites, a group of their own on top (each card with its device, in a fixed order), then the devices with
 * their other connections. Each card is swipeable ([TunnelCard]).
 */
internal fun LazyListScope.tunnelRows(
    view: TunnelsView,
    state: ConnectionsUiState,
    sessions: List<SessionOn>,
    grants: Map<GrantTarget, AssistantMark>,
    ui: ConnectionUi,
    actions: RowActions,
    onForgetAssistant: (ConnectionRow) -> Unit,
    openRow: OpenRow,
    scope: List<String> = emptyList(),
    query: String = "",
    selected: Set<String> = emptySet(),
    onSelect: ((String) -> Unit)? = null,
    onToggle: (String) -> Unit = {},
    onAdd: ((DeviceGroup) -> Unit)? = null,
) {
    val scoped = state.groups.filter { inDeviceScope(scope, it) }
    val groups = if (scope.isNotEmpty()) {
        val missingFavorites = view.favorites.map { it.group.copy(rows = emptyList()) }.distinctBy { it.profile to it.name }
        val selectedDevices = if (query.isBlank()) state.devices.filter { inDeviceScope(scope, it) } else emptyList()
        view.groups + (missingFavorites + selectedDevices).distinctBy { it.profile to it.name }
            .filter { device -> view.groups.none { it.profile == device.profile && it.name == device.name } }
    } else view.groups
    if (view.favorites.isNotEmpty()) {
        item(key = "favorites") { SectionHeading(stringResource(R.string.connections_favorites), stringResource(R.string.connections_favorites_spoken), first = true) }
        items(view.favorites, key = { it.row.key }) { favorite ->
            val row = favorite.row
            val context = LocalContext.current
            val selectionLabel = listOf(connectionLabel(context, row.connection), favorite.group.name, profileLabel(favorite.group.profile, state.profileNames)).joinToString(" · ")
            SelectableTunnelRow(selectionLabel, selected.isNotEmpty(), row.key in selected, onToggle = { onToggle(row.key) }) {
                TunnelCard(row, favorite.group.offline, favorite.group.name + if (state.profileNames.size > 1) " · " + profileLabel(favorite.group.profile, state.profileNames) else "", state, sessions, grants, ui, actions, onForgetAssistant, openRow,
                    onSelect = onSelect?.let { select -> { select(row.key) } })
            }
        }
    }
    groups.forEachIndexed { index, group ->
        item(key = "device/${group.key}") {
            val addLabel = stringResource(R.string.tunnels_add_for, group.name, profileLabel(group.profile, state.profileNames))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { GroupHeader(group, state.colors[group.profile]?.takeIf { state.manyProfiles }, first = index == 0 && view.favorites.isEmpty(), profileName = if (state.profileNames.size > 1) profileLabel(group.profile, state.profileNames) else null) }
                if (onAdd != null && scoped.any { it.profile == group.profile && it.name == group.name && it.rows.isNotEmpty() }) TextButton(onClick = { onAdd(group) }, modifier = Modifier.testTag("tunnels_add_${group.key}").semantics { contentDescription = addLabel }) { Text("+") }
            }
        }
        if (onAdd != null && group.rows.isEmpty() && scoped.none { it.profile == group.profile && it.name == group.name && it.rows.isNotEmpty() }) item(key = "empty/${group.key}") {
            val addLabel = stringResource(R.string.tunnels_add_for, group.name, profileLabel(group.profile, state.profileNames))
            Column {
                Text(stringResource(R.string.tunnels_device_empty), color = AppTheme.colors.muted)
                TextButton(onClick = { onAdd(group) }, modifier = Modifier.semantics { contentDescription = addLabel }) { Text(stringResource(R.string.new_connection)) }
            }
        }
        items(group.rows, key = { it.key }) { row ->
            val context = LocalContext.current
            val selectionLabel = listOf(connectionLabel(context, row.connection), group.name, profileLabel(group.profile, state.profileNames)).joinToString(" · ")
            SelectableTunnelRow(selectionLabel, selected.isNotEmpty(), row.key in selected, onToggle = { onToggle(row.key) }) {
                TunnelCard(row, group.offline, null, state, sessions, grants, ui, actions, onForgetAssistant, openRow,
                    onSelect = onSelect?.let { select -> { select(row.key) } })
            }
        }
    }
}

/** The heading of the favourites group: the same place and weight as a device's name. */
@Composable
private fun SectionHeading(text: String, spoken: String, first: Boolean) {
    val muted = AppTheme.colors.muted
    Text(
        // The star keeps the muted colour it has on the cards; the words stay in ink.
        androidx.compose.ui.text.buildAnnotatedString {
            val star = text.indexOf('\u2605')
            if (star < 0) append(text) else {
                append(text.substring(0, star))
                withStyle(androidx.compose.ui.text.SpanStyle(color = muted)) { append('\u2605') }
                append(text.substring(star + 1))
            }
        },
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppTheme.colors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = if (first) 4.dp else 16.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = spoken
            },
    )
}

/** How far a card slides aside for a button. */
private val REVEAL_WIDTH = 92.dp

/**
 * One connection's card with its swipes ([SwipeReveal]): to the left it reveals "Ask the assistant" (absent while the assistant is off), to the right
 * the favourites star. Nothing runs by the swipe: the revealed button's tap does, then the card slides back. [deviceName] is set where the card has
 * no device header over it (the favourites).
 */
@Composable
private fun TunnelCard(
    row: ConnectionRow,
    offline: Boolean,
    deviceName: String?,
    state: ConnectionsUiState,
    sessions: List<SessionOn>,
    grants: Map<GrantTarget, AssistantMark>,
    ui: ConnectionUi,
    actions: RowActions,
    onForgetAssistant: (ConnectionRow) -> Unit,
    openRow: OpenRow,
    onSelect: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val ask = actions.ask?.takeIf { row.saved }
    val revealed = openRow.name == row.key
    SwipeReveal(
        open = revealed,
        enabled = ask != null,
        onOpenChange = { now -> openRow.reported(row.key, now) },
        actionWidth = revealWidth(),
        action = {
            RevealButton(
                stringResource(R.string.terminal_ask_assistant),
                glyph = { Icon(AssistantGlyph, contentDescription = null, tint = colors.onAccentContainer, modifier = Modifier.size(22.dp)) },
                modifier = Modifier.testTag("swipe_ask_assistant"),
            ) {
                openRow.close()
                ask?.invoke(row)
            }
        },
        modifier = Modifier.touchesRow(openRow, row.key).testTag("tunnel_card_${row.connection.id}"),
        startAction = {
            val remove = row.connection.favorite
            RevealButton(
                stringResource(if (remove) R.string.favorite_remove else R.string.favorite_add),
                glyph = { Text(if (remove) "\u2606" else "\u2605", fontSize = 24.sp, color = colors.onAccentContainer) },
                modifier = Modifier.testTag("swipe_favorite"),
            ) {
                openRow.close()
                actions.favorite(row)
            }
        },
        startActionWidth = revealWidth(),
        startEnabled = row.saved,
    ) {
        ConnectionCard(
            row = row,
            sshMethod = state.sshMethods[row.key],
            sessions = row.sessionsIn(sessions),
            menuOpen = ui.menu == row.key,
            onMenu = { ui.menu = if (it) row.key else null },
            actions = actions,
            offline = offline,
            deviceName = deviceName,
            assistant = if (row.saved) grants[GrantTarget(row.profile, row.connection.id)] else null,
            onForgetAssistant = { onForgetAssistant(row) },
            revealed = revealed,
            onCloseReveal = openRow::close,
            onSelect = onSelect,
        )
    }
}

/** The button behind a card swiped aside; its description names the action (the glyph is drawn, not read). */
@Composable
private fun RevealButton(label: String, glyph: @Composable () -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = AppTheme.colors.accentContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .width(revealWidth() - 6.dp)
            .fillMaxHeight()
            .semantics { contentDescription = label },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            glyph()
            Text(label, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                color = AppTheme.colors.onAccentContainer, modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics { })
        }
    }
}

/** The swipe button's width: [REVEAL_WIDTH] at the normal font, wider with a larger one so its label wraps between words only. */
@Composable
private fun revealWidth(): androidx.compose.ui.unit.Dp = REVEAL_WIDTH * LocalDensity.current.fontScale.coerceIn(1f, 1.8f)
