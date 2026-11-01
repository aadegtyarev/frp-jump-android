package ru.adsrv.frpjump.ui.connections

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import ru.adsrv.frpjump.connections.CONNECTIONS_SEARCH_FROM
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.connections.DeviceGroup
import ru.adsrv.frpjump.connections.filterGroups
import kotlinx.coroutines.awaitCancellation
import ru.adsrv.frpjump.tunnel.Waiting
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.PowerGlyph
import ru.adsrv.frpjump.ui.SearchBar
import ru.adsrv.frpjump.ui.SearchGlyph
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
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions by vm.actions.sessions.collectAsStateWithLifecycle(emptyList())
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
    // Search, as on Devices: the title turns into a field; ✕ or Back closes it and shows them all again.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var focusOnOpen by rememberSaveable { mutableStateOf(false) }
    val closeSearch = {
        searching = false
        query = ""
        focusOnOpen = false
    }
    BackHandler(enabled = searching) { closeSearch() }
    val all = state.rows
    val shown = filterGroups(state.groups, query) { row -> if (row.saved) connectionLabel(context, row.connection) else context.getString(R.string.connection_unsaved, row.connection.port) }
    Column(Modifier.fillMaxSize()) {
        if (searching) {
            SearchBar(
                query,
                onQuery = { query = it },
                onClose = closeSearch,
                focus = focusOnOpen,
                onFocused = { focusOnOpen = false },
                label = stringResource(R.string.connections_search),
                hint = stringResource(R.string.connections_search_hint),
            )
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::pull,
            modifier = Modifier
                .fillMaxSize()
                .then(if (searching) Modifier else Modifier.statusBarsPadding()),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "title") {
                    Column(Modifier.padding(start = 4.dp, top = if (searching) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!searching) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.tab_connections), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (all.any { it.active } || restoring != null || disconnecting) {
                                    DisconnectAllButton(busy = disconnecting, onClick = disconnectAll)
                                }
                                if (all.size >= CONNECTIONS_SEARCH_FROM) {
                                    IconButton(onClick = {
                                        searching = true
                                        focusOnOpen = true
                                    }, modifier = Modifier.size(44.dp)) {
                                        Icon(SearchGlyph, contentDescription = stringResource(R.string.connections_search), tint = colors.ink, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }
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
                        )
                    }
                }
            }
        }
    }
    ConnectionDialogs(ui, vm.actions, existing = vm::connections, rows = all, onOpenTerminal = onOpenTerminal, onOpenFiles = onOpenFiles)
}

/** The power button of the Devices bar, for every profile's tunnels; a spinner while servers are told. */
@Composable
private fun DisconnectAllButton(busy: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val label = stringResource(R.string.disconnect_all_tunnels)
    IconButton(onClick = onClick, enabled = !busy, modifier = Modifier.size(44.dp).semantics { contentDescription = label }) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = colors.error, strokeWidth = 2.dp)
        } else {
            Icon(PowerGlyph, contentDescription = null, tint = colors.error, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * A device over its connections: its whole name (wrapped, never cut) after
 * its state as a dot, the Devices list's colours; [profileColor] as a bar
 * before it when the tab shows more than one profile.
 */
@Composable
private fun GroupHeader(group: DeviceGroup, profileColor: String?, first: Boolean) {
    val colors = AppTheme.colors
    val device = group.device
    val (dot, spoken) = when {
        device == null -> colors.faint to null
        !device.online -> colors.offline to stringResource(R.string.device_offline)
        // Online as of the last answer only.
        device.stale -> colors.online.copy(alpha = 0.4f) to wasOnline(device)
        else -> colors.online to stringResource(R.string.device_online)
    }
    val description = listOfNotNull(group.name, spoken).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = if (first) 4.dp else 16.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        profileColor?.let {
            Box(
                Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .background(colors.profile(it), RoundedCornerShape(2.dp)),
            )
        }
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Text(group.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.weight(1f))
    }
}
