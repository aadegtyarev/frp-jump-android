package ru.adsrv.frpjump.ui.connections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.connections.connectBlocked
import ru.adsrv.frpjump.connections.connectionTitle
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.tunnel.DirectMode
import ru.adsrv.frpjump.tunnel.SessionCount
import ru.adsrv.frpjump.ui.AppTheme

/**
 * One connection, the same on the device card and the Connections tab:
 * kind tile, its own name (when it has one) over the port and login, "⋮";
 * below, the state, Disconnect and the main action when up, or "Not
 * connected" and Connect. A tap on the card does its main action, a long
 * press opens the same menu as "⋮". On a device known to be [offline]
 * ([ru.adsrv.frpjump.connections.knownOffline]), a connection that is down
 * is dimmed and cannot be connected; its "⋮" and long press work.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectionCard(
    row: ConnectionRow,
    sshMethod: SshAuthMethod?,
    sessions: SessionCount,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    actions: RowActions,
    modifier: Modifier = Modifier,
    offline: Boolean = false,
) {
    val colors = AppTheme.colors
    val c = row.connection
    val up = row.tunnel != null
    // Up, it stays usable: Disconnect is what the owner needs then.
    val unreachable = connectBlocked(row, offline)
    val tapEnabled = cardTap(row) != CardTap.NONE && !unreachable
    val openMenu by rememberUpdatedState { onMenu(true) }
    val details = details(row, sshMethod)
    // Its own name only when it says more than the tile: "z2m", not "SSH".
    val title = if (row.saved) connectionTitle(c, kindLabel(c.kind)) else rowName(row)
    Surface(
        color = colors.card,
        shape = RoundedCornerShape(18.dp),
        border = if (up) BorderStroke(1.5.dp, colors.accent.copy(alpha = 0.45f)) else BorderStroke(1.dp, colors.divider),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            // Anywhere but its buttons: its main action, as the button labels it (connecting first when down);
            // being disconnected, or its device offline, no action of its own, for touch or TalkBack,
            // while a long press still opens its menu (TalkBack has "⋮" for it).
            .then(
                if (tapEnabled) {
                    Modifier.combinedClickable(
                        onClickLabel = cardTapLabel(row, sessions),
                        onClick = { actions.tap(row) },
                        onLongClick = { if (hasMenu(row)) onMenu(true) },
                    )
                } else if (hasMenu(row)) {
                    Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { openMenu() }) }
                } else {
                    Modifier
                },
            ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.alpha(if (unreachable) DIMMED else 1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        KindTile(c.kind, up, row.saved)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            // The name is what tells connections apart: on lines of its own, never cut.
                            if (title != null) {
                                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                details?.let { Text(it, fontSize = 13.sp, color = colors.muted) }
                            } else if (details != null) {
                                Text(details, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            // On a line of its own: next to a long state and the main action it would be cut.
                            if (row.tunnel != null || sessions.total > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.tunnel?.let { LocalAddress(it.localPort, modifier = Modifier.weight(1f, fill = false), pinned = it.pinned) }
                                    SessionsPill(sessions)
                                }
                            }
                        }
                    }
                }
                if (hasMenu(row)) {
                    Box {
                        IconButton(onClick = { onMenu(true) }, modifier = Modifier.width(36.dp)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.connection_actions, rowName(row)), tint = colors.muted)
                        }
                        ConnectionMenu(menuOpen, row, actions, onDismiss = { onMenu(false) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val state = stateOf(row)
                // The state gives way (ellipsis) before the buttons do.
                Box(Modifier.weight(1f)) {
                    when {
                        state != null -> StatePill(
                            state.first,
                            state.second,
                            onClick = { actions.directChoice(row) }.takeIf { offersDirectChoice(row) },
                            chevron = row.tunnel?.direct != DirectMode.KEPT,
                        )
                        // Why Connect is off, where it is.
                        unreachable -> Text(stringResource(R.string.state_device_offline), fontSize = 13.sp, color = colors.muted)
                        else -> Text(stringResource(R.string.state_not_connected), fontSize = 13.sp, color = colors.muted)
                    }
                }
                if (up) {
                    DisconnectIcon(row, 40.dp) { actions.disconnect(row) }
                    MainButton(mainActionLabel(row, sessions)) { actions.main(row) }
                } else {
                    ConnectButton(row, enabled = !unreachable) { actions.connect(row) }
                }
            }
            problemOf(row)?.let { Text(it, color = colors.error, fontSize = 13.sp) }
        }
    }
}

/** How much a connection that cannot be connected now is faded. */
private const val DIMMED = 0.5f

/**
 * "Port 80", "Port 22 · root · phone key", …: what the kind tile does not
 * say already; null for a tunnel no connection names (its name says the port).
 */
@Composable
private fun details(row: ConnectionRow, sshMethod: SshAuthMethod?): String? {
    val c = row.connection
    if (!row.saved) return null
    return when {
        c.kind == PortKind.WEB && c.https -> stringResource(R.string.connection_details_https, c.port)
        c.kind == PortKind.SSH && sshMethod == SshAuthMethod.PASSWORD -> stringResource(R.string.connection_details_ssh_password, c.port, c.sshUser)
        c.kind == PortKind.SSH -> stringResource(R.string.connection_details_ssh_key, c.port, c.sshUser)
        else -> stringResource(R.string.connection_details, c.port)
    }
}

/** WEB / SSH / TCP / MQTT on a tile, in the accent while connected. */
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
                if (!saved) {
                    R.string.kind_tcp_tile
                } else {
                    when (kind) {
                        PortKind.WEB -> R.string.kind_web_tile
                        PortKind.SSH -> R.string.kind_ssh_tile
                        PortKind.TCP -> R.string.kind_tcp_tile
                        PortKind.MQTT -> R.string.kind_mqtt_tile
                    }
                },
            ),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (up) colors.onAccentContainer else colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
