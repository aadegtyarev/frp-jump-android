package ru.adsrv.frpjump.ui.connections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import ru.adsrv.frpjump.connections.tunnelsView
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import ru.adsrv.frpjump.ui.userMessage
import ru.adsrv.frpjump.ui.wasOnline

/**
 * The Connections tab: every saved connection of every profile, under its
 * device; devices with active connections first, then online ones, then
 * the rest. Each connection is the device card's own card. Search by
 * device or connection name once there are many.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    vm: ConnectionsViewModel,
    onOpenWeb: (ConnectionRow) -> Unit,
    onOpenTerminal: (sessionId: Int) -> Unit,
    onOpenFiles: (targetId: Int) -> Unit,
    onOpenMqtt: (sessionId: Int) -> Unit,
    onSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions by vm.actions.sessions.collectAsStateWithLifecycle(emptyList())
    val grants by vm.actions.assistantGrants.collectAsStateWithLifecycle()
    val ui = remember { ConnectionUi() }
    val actions = rememberRowActions(ui, vm.actions, onOpenWeb, onOpenTerminal, onOpenFiles, onOpenMqtt)
    val colors = AppTheme.colors
    val context = LocalContext.current
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
    // "Disconnect all": the tunnels show down at once, with Undo. Only one still being restored is told
    // to its server now: then it spins until every server was asked (the line below tells the rest).
    var disconnecting by remember { mutableStateOf(false) }
    val disconnectAll = {
        disconnecting = true
        vm.actions.disconnectAll { disconnecting = false }
    }
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
    val (onlyOn, setOnlyOn) = rememberOnlyOnFilter()
    val all = state.rows
    val count = onTunnelCount(state.groups, restoring ?: 0)
    val view = tunnelsView(state.groups, onlyOn, query) { row -> if (row.saved) connectionLabel(context, row.connection) else context.getString(R.string.connection_unsaved, row.connection.port) }
    val shown = view.groups
    Column(Modifier.fillMaxSize()) {
        TunnelsHeader(
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
            onStopAll = disconnectAll,
            onSettings = onSettings,
            modifier = Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::pull,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "status") {
                    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                if (state.loaded && all.isEmpty()) {
                    item(key = "empty") { Text(stringResource(R.string.connections_empty), fontSize = 14.sp, color = colors.muted, modifier = Modifier.padding(4.dp)) }
                }
                // A search that finds nothing says so, rather than leave an empty screen.
                if (all.isNotEmpty() && shown.isEmpty()) {
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
                shown.forEachIndexed { index, group ->
                    item(key = "device/${group.key}") {
                        GroupHeader(group, state.colors[group.profile]?.takeIf { state.manyProfiles }, first = index == 0)
                    }
                    items(group.rows, key = { it.key }) { row ->
                        ConnectionCard(
                            row = row,
                            sshMethod = state.sshMethods[row.key],
                            sessions = row.sessionsIn(sessions),
                            menuOpen = ui.menu == row.key,
                            onMenu = { ui.menu = if (it) row.key else null },
                            actions = actions,
                            offline = group.offline,
                            assistant = if (row.saved) grants[GrantTarget(row.profile, row.connection.id)] else null,
                            onForgetAssistant = { vm.actions.forgetAssistant(row) },
                        )
                    }
                }
            }
        }
    }
    ConnectionDialogs(ui, vm.actions, existing = vm::connections, rows = all, onOpenTerminal = onOpenTerminal, onOpenFiles = onOpenFiles)
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
@Composable
private fun GroupHeader(group: DeviceGroup, profileColor: String?, first: Boolean) {
    val colors = AppTheme.colors
    val device = group.device
    val (dot, spoken) = when {
        device == null -> null to null
        !device.online -> colors.offline to stringResource(R.string.device_offline)
        // Online as of the last answer only.
        device.stale -> colors.online.copy(alpha = 0.4f) to wasOnline(device)
        else -> colors.online to stringResource(R.string.device_online)
    }
    val description = listOfNotNull(group.name, device?.label, spoken, device?.icon?.let { stringResource(it.caption) }).joinToString(", ")
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
        Column(Modifier.weight(1f)) {
            Text(group.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            device?.label?.let { Text(it, fontSize = 13.sp, color = colors.muted) }
        }
    }
}
