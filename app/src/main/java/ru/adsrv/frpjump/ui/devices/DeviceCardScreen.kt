package ru.adsrv.frpjump.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.connections.knownOffline
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.caption
import ru.adsrv.frpjump.ui.wasOnline
import ru.adsrv.frpjump.ui.PlusGlyph
import ru.adsrv.frpjump.ui.PowerGlyph
import ru.adsrv.frpjump.ui.connections.ConnectionCard
import ru.adsrv.frpjump.ui.connections.ConnectionDialogs
import ru.adsrv.frpjump.ui.connections.ConnectionSheet
import ru.adsrv.frpjump.ui.connections.ConnectionUi
import ru.adsrv.frpjump.ui.connections.rememberLocalPortContext
import ru.adsrv.frpjump.ui.connections.rememberNotificationsFirst
import ru.adsrv.frpjump.ui.connections.rememberRowActions
import ru.adsrv.frpjump.ui.connections.sessionsIn

/**
 * A device of the current profile: its state, its connections with their
 * tunnels' state and main action, rare actions under "⋮", and "New
 * connection" as a sheet.
 */
@Composable
fun DeviceCardScreen(
    vm: DeviceCardViewModel,
    onBack: () -> Unit,
    onOpenWeb: (ConnectionRow) -> Unit,
    onOpenTerminal: (sessionId: Int) -> Unit,
    onOpenFiles: (targetId: Int) -> Unit,
    onOpenMqtt: (sessionId: Int) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions by vm.actions.sessions.collectAsStateWithLifecycle(emptyList())
    val ui = remember { ConnectionUi() }
    val actions = rememberRowActions(ui, vm.actions, onOpenWeb, onOpenTerminal, onOpenFiles, onOpenMqtt)
    val notificationsFirst = rememberNotificationsFirst()
    var adding by rememberSaveable { mutableStateOf(false) }
    val colors = AppTheme.colors
    Scaffold(
        containerColor = colors.ground,
        // Back stays in reach while the list scrolls.
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.ground)
                    .statusBarsPadding()
                    .padding(start = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = colors.ink)
                }
                // This device's tunnels, as the power button on the Devices bar does for the profile's: down at once, with Undo.
                if (state.rows.any { it.active }) {
                    Box(Modifier.weight(1f))
                    val label = stringResource(R.string.disconnect_device_tunnels, vm.device)
                    IconButton(
                        onClick = { vm.actions.disconnectDevice(vm.profile, vm.device) },
                        modifier = Modifier.padding(end = 8.dp).size(44.dp).semantics { contentDescription = label },
                    ) {
                        Icon(PowerGlyph, contentDescription = null, tint = colors.error, modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                containerColor = colors.strong,
                contentColor = colors.onStrong,
                shape = RoundedCornerShape(18.dp),
                icon = { Icon(PlusGlyph, contentDescription = null, modifier = Modifier.size(20.dp)) },
                text = { Text(stringResource(R.string.new_connection_button), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            // The last card scrolls clear of the floating button (and the navigation bar under it).
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") { Header(vm.device, state.device, state.profileName) }
            state.mismatch?.let { item(key = "mismatch") { Problem(stringResource(R.string.mismatch_banner, it)) } }
            state.syncError?.let { item(key = "sync") { Problem(it) } }
            item(key = "section") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.connections_header).uppercase(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                        color = colors.muted,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.rows.isNotEmpty()) {
                        Text(
                            stringResource(R.string.connections_active_of, state.rows.count { it.active }, state.rows.size),
                            fontSize = 13.sp,
                            color = colors.muted,
                        )
                    }
                }
            }
            if (state.rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.device_no_connections),
                        color = colors.muted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(state.rows, key = { it.key }) { row ->
                ConnectionCard(
                    row = row,
                    sshMethod = if (row.connection.kind == PortKind.SSH) state.sshMethods[row.connection.port] else null,
                    sessions = row.sessionsIn(sessions),
                    menuOpen = ui.menu == row.key,
                    onMenu = { ui.menu = if (it) row.key else null },
                    actions = actions,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    // As on the Connections tab: down on a device offline now, it cannot be connected.
                    offline = knownOffline(state.device),
                )
            }
        }
    }
    ConnectionDialogs(ui, vm.actions, existing = { vm.connections() }, rows = state.rows, onOpenTerminal = onOpenTerminal, onOpenFiles = onOpenFiles)
    if (adding) {
        ConnectionSheet(
            device = vm.device,
            editing = null,
            portLocked = false,
            existing = vm.connections(),
            ports = rememberLocalPortContext(vm.actions, vm.profile),
            onSave = { connection, connectNow, done ->
                val save = { vm.actions.save(vm.profile, connection, previous = null, connectNow = connectNow, onDone = done) }
                if (connectNow) notificationsFirst(save) else save()
            },
            onDismiss = { adding = false },
        )
    }
}

/**
 * The device's icon on a tile, its whole name and under it its whole label
 * (wrapped, never cut), then its state and its profile, as the mockup.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(name: String, device: DeviceRow?, profileName: String) {
    val colors = AppTheme.colors
    Column {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val iconName = device?.icon?.let { stringResource(it.caption) }
                Box(Modifier.semantics { iconName?.let { contentDescription = it } }) {
                    DeviceTile(device?.icon, dot = null, size = 48.dp, ring = colors.ground)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    device?.label?.let { Text(it, fontSize = 15.sp, color = colors.muted) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (device != null) {
                    val (dot, text) = when {
                        device.online && device.stale -> colors.online.copy(alpha = 0.4f) to wasOnline(device)
                        device.online -> colors.online to stringResource(R.string.device_online)
                        else -> colors.offline to stringResource(R.string.device_offline)
                    }
                    HeaderChip {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(dot, CircleShape),
                        )
                        Text(text, fontSize = 13.sp)
                    }
                }
                if (profileName.isNotBlank()) HeaderChip { Text(stringResource(R.string.device_profile_chip, profileName), fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun HeaderChip(content: @Composable () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .background(colors.card, CircleShape)
            .border(1.dp, colors.outline, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Problem(text: String) {
    Text(text, color = AppTheme.colors.error, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
}
