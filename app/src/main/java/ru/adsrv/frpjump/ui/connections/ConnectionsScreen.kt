package ru.adsrv.frpjump.ui.connections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.tunnel.Waiting
import androidx.compose.ui.res.pluralStringResource
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.tunnel.DirectMode
import ru.adsrv.frpjump.tunnel.SessionCount
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.LinkGlyph

/**
 * The Connections tab: every saved connection of every profile, marked
 * with its profile's colour; active ones on top with their state, local
 * address and main action, the rest below with a connect button. Long
 * press a row for its rare actions.
 */
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
    // "Disconnect all" spins until every server was asked (the rows and the line below tell the rest).
    var disconnecting by remember { mutableStateOf(false) }
    val disconnectAll = {
        disconnecting = true
        vm.actions.disconnectAll { disconnecting = false }
    }
    // Every session and file screen closes with the tunnels: asked first when any is open.
    var confirmAll by remember { mutableStateOf(false) }
    val restoring = state.restoring?.takeIf { it > 0 }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tab_connections), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (state.active.isNotEmpty() || restoring != null || disconnecting) {
                        OutlinedButton(
                            onClick = { if (vm.actions.openThrough(null).any) confirmAll = true else disconnectAll() },
                            enabled = !disconnecting,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, colors.outline),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.card, contentColor = colors.error),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            modifier = Modifier.height(40.dp),
                        ) {
                            if (disconnecting) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = colors.error, strokeWidth = 2.dp)
                                Spacer8()
                            }
                            Text(stringResource(R.string.disconnect_all), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.error)
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
        if (state.loaded && state.active.isEmpty() && state.saved.isEmpty()) {
            item(key = "empty") { Text(stringResource(R.string.connections_empty), fontSize = 14.sp, color = colors.muted, modifier = Modifier.padding(4.dp)) }
        }
        if (state.active.isNotEmpty()) {
            item(key = "active") { SectionTitle(stringResource(R.string.connections_active, state.active.size), top = 4) }
            items(state.active, key = { it.key }) { row -> Line(row, state.colors[row.profile], row.sessionsIn(sessions), ui, actions) }
        }
        if (state.saved.isNotEmpty()) {
            item(key = "saved") { SectionTitle(stringResource(R.string.connections_saved), top = if (state.active.isEmpty()) 4 else 12) }
            items(state.saved, key = { it.key }) { row -> Line(row, state.colors[row.profile], row.sessionsIn(sessions), ui, actions) }
        }
    }
    if (confirmAll) {
        val open = vm.actions.openThrough(null)
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.disconnect_all_confirm_title)) },
            text = if (open.any) ({ OpenThroughText(open) }) else null,
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    disconnectAll()
                }) { Text(stringResource(R.string.disconnect_all), color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    ConnectionDialogs(ui, vm.actions, existing = vm::connections, rows = state.active + state.saved, onOpenTerminal = onOpenTerminal, onOpenFiles = onOpenFiles)
}

@Composable
private fun Spacer8() = Box(Modifier.width(8.dp))

@Composable
private fun SectionTitle(text: String, top: Int) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.7.sp,
        color = AppTheme.colors.muted,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = top.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Line(row: ConnectionRow, profileColor: String?, sessions: SessionCount, ui: ConnectionUi, actions: RowActions) {
    val colors = AppTheme.colors
    val c = row.connection
    Box {
        Surface(
            color = colors.card,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = {}, onLongClick = { if (hasMenu(row)) ui.menu = row.key }),
        ) {
            Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .width(6.dp)
                            .fillMaxHeight()
                            .background(profileColor?.let(colors::profile) ?: colors.faint, RoundedCornerShape(3.dp)),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            buildAnnotatedString {
                                append(rowName(row))
                                withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = colors.muted)) {
                                    append(" ")
                                    append(stringResource(R.string.connection_on_device, c.device))
                                }
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val state = stateOf(row)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state != null) {
                                val choice = offersDirectChoice(row)
                                val chevron = choice && row.tunnel?.direct != DirectMode.KEPT
                                Text(
                                    if (chevron) "${state.first}  ›" else state.first,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = toneInk(state.second),
                                    modifier = if (choice) Modifier.clip(RoundedCornerShape(6.dp)).clickable { actions.directChoice(row) } else Modifier,
                                )
                            }
                            if (row.tunnel != null) {
                                // Gives way to the sessions pill: the address shortens to ":port" first.
                                LocalAddress(row.tunnel.localPort, size = 12, modifier = Modifier.weight(1f, fill = false), pinned = row.tunnel.pinned)
                            } else if (state == null) {
                                Text(stringResource(R.string.connection_sub_short, kindLabel(c.kind), c.port), fontSize = 12.sp, color = colors.muted)
                            }
                            SessionsPill(sessions)
                        }
                    }
                    if (row.tunnel != null) {
                        DisconnectIcon(row, 40.dp) { actions.disconnect(row) }
                        MainButton(mainActionLabel(row, sessions)) { actions.main(row) }
                    } else {
                        ConnectIcon(row) { actions.connect(row) }
                    }
                    if (hasMenu(row)) {
                        Box {
                            IconButton(onClick = { ui.menu = row.key }, modifier = Modifier.size(width = 28.dp, height = 44.dp)) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.connection_actions, rowName(row)), tint = colors.muted)
                            }
                            ConnectionMenu(ui.menu == row.key, row, actions, onDismiss = { ui.menu = null })
                        }
                    } else {
                        // Keeps the buttons of an unsaved row where a saved row's are.
                        Box(Modifier.width(28.dp))
                    }
                }
                problemOf(row)?.let { Text(it, color = colors.error, fontSize = 13.sp) }
            }
        }
    }
}

/** The state's colour as text on a card (the pill colours are for pills). */
@Composable
private fun toneInk(tone: Tone) = when (tone) {
    Tone.GOOD -> AppTheme.colors.accent
    Tone.WARN -> AppTheme.colors.warning
    Tone.BAD -> AppTheme.colors.error
    Tone.NEUTRAL -> AppTheme.colors.muted
}

/** The mockup's square link button; a spinner while connecting. */
@Composable
private fun ConnectIcon(row: ConnectionRow, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val busy = row.pending != null
    Surface(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(12.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.outline),
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
            } else {
                Icon(LinkGlyph, contentDescription = stringResource(R.string.connect_named, rowName(row)), tint = colors.accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}
