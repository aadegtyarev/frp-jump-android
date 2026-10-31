package ru.adsrv.frpjump.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.PlusGlyph
import ru.adsrv.frpjump.ui.connections.ConnectButton
import ru.adsrv.frpjump.ui.connections.ConnectionDialogs
import ru.adsrv.frpjump.ui.connections.ConnectionMenu
import ru.adsrv.frpjump.ui.connections.ConnectionSheet
import ru.adsrv.frpjump.ui.connections.ConnectionUi
import ru.adsrv.frpjump.ui.connections.LocalAddress
import ru.adsrv.frpjump.ui.connections.MainButton
import ru.adsrv.frpjump.ui.connections.RowActions
import ru.adsrv.frpjump.ui.connections.StatePill
import ru.adsrv.frpjump.ui.connections.offersDirectChoice
import ru.adsrv.frpjump.ui.connections.kindLabel
import ru.adsrv.frpjump.ui.connections.mainActionLabel
import ru.adsrv.frpjump.ui.connections.problemOf
import ru.adsrv.frpjump.ui.connections.rememberNotificationsFirst
import ru.adsrv.frpjump.ui.connections.rememberRowActions
import ru.adsrv.frpjump.ui.connections.rowName
import ru.adsrv.frpjump.ui.connections.stateOf

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
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ui = remember { ConnectionUi() }
    val actions = rememberRowActions(ui, vm.actions, onOpenWeb)
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
                    sshMethod = if (row.connection.kind == PortKind.SSH) vm.actions.sshLogin(row).method else null,
                    menuOpen = ui.menu == row.key,
                    onMenu = { ui.menu = if (it) row.key else null },
                    actions = actions,
                )
            }
        }
    }
    ConnectionDialogs(ui, vm.actions, existing = { vm.connections() }, rows = state.rows, onOpenTerminal = onOpenTerminal)
    if (adding) {
        ConnectionSheet(
            device = vm.device,
            editing = null,
            portLocked = false,
            existing = vm.connections(),
            onSave = { connection, connectNow, done ->
                val save = { vm.actions.save(vm.profile, connection, previous = null, connectNow = connectNow, onDone = done) }
                if (connectNow) notificationsFirst(save) else save()
            },
            onDismiss = { adding = false },
        )
    }
}

/** The device's name, its state and its profile, as the mockup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(name: String, device: DeviceRow?, profileName: String) {
    val colors = AppTheme.colors
    Column {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (device != null) {
                    val (dot, text) = when {
                        device.online && device.stale -> colors.online.copy(alpha = 0.4f) to stringResource(R.string.device_was_online)
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

/**
 * One connection as the mockup's card: kind tile, name and what it is, "⋮";
 * below, the state, local address and main action when up, or "Not
 * connected" and Connect. Long press opens the same menu as "⋮".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionCard(
    row: ConnectionRow,
    sshMethod: SshAuthMethod?,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    actions: RowActions,
) {
    val colors = AppTheme.colors
    val c = row.connection
    val up = row.tunnel != null
    Surface(
        color = colors.card,
        shape = RoundedCornerShape(18.dp),
        border = if (up) BorderStroke(1.5.dp, colors.accent.copy(alpha = 0.45f)) else BorderStroke(1.dp, colors.divider),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .combinedClickable(onClick = {}, onLongClick = { onMenu(true) }),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KindTile(c.kind, up, row.saved)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(rowName(row), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle(row, sshMethod), fontSize = 13.sp, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { onMenu(true) }, modifier = Modifier.width(36.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.connection_actions, rowName(row)), tint = colors.muted)
                    }
                    ConnectionMenu(menuOpen, row, actions, onDismiss = { onMenu(false) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val state = stateOf(row)
                if (state != null) {
                    StatePill(state.first, state.second, onClick = { actions.directChoice(row) }.takeIf { offersDirectChoice(row) })
                } else Text(stringResource(R.string.state_not_connected), fontSize = 13.sp, color = colors.muted)
                // The address gives way (ellipsis) before the main action does.
                row.tunnel?.let { LocalAddress(it.localPort, modifier = Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
                if (up) MainButton(mainActionLabel(row)) { actions.main(row) } else ConnectButton(row) { actions.connect(row) }
            }
            problemOf(row)?.let { Text(it, color = colors.error, fontSize = 13.sp) }
        }
    }
}

/** "Web · port 80", "SSH · port 22 · root · phone key", …; an unsaved tunnel shows its device and port only. */
@Composable
private fun subtitle(row: ConnectionRow, sshMethod: SshAuthMethod?): String {
    val c = row.connection
    if (!row.saved) return "${c.device}:${c.port}"
    val kind = kindLabel(c.kind)
    return when {
        c.kind == PortKind.WEB && c.https -> stringResource(R.string.connection_sub_https, kind, c.port)
        c.kind == PortKind.SSH && sshMethod == SshAuthMethod.PASSWORD -> stringResource(R.string.connection_sub_ssh_password, kind, c.port, c.sshUser)
        c.kind == PortKind.SSH -> stringResource(R.string.connection_sub_ssh_key, kind, c.port, c.sshUser)
        else -> stringResource(R.string.connection_sub, kind, c.port)
    }
}

/** WEB / SSH / TCP on a tile, in the accent while connected. */
@Composable
private fun KindTile(kind: PortKind, up: Boolean, saved: Boolean) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .size(40.dp)
            .background(if (up) colors.accentContainer else colors.tile, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(
                when {
                    !saved || kind == PortKind.TCP -> R.string.kind_tcp_tile
                    kind == PortKind.WEB -> R.string.kind_web_tile
                    else -> R.string.kind_ssh_tile
                },
            ),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (up) colors.onAccentContainer else colors.muted,
        )
    }
}
