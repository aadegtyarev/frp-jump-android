package ru.adsrv.frpjump.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import ru.adsrv.frpjump.mqtt.MqttSession
import ru.adsrv.frpjump.mqtt.MqttSessions
import ru.adsrv.frpjump.mqtt.MqttState
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.TerminalGlyph
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.StateFlow
import ru.adsrv.frpjump.sessions.SessionCloser
import ru.adsrv.frpjump.sessions.sessionHidden
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.TunnelRef

/**
 * The Sessions tab: the open terminal sessions, then the MQTT explorer
 * sessions; tap one to return to it, ✕ closes it with Undo (as a
 * terminal's tab and the explorer's "⋮" do). One closed, or whose tunnel
 * was disconnected, while the Undo is on screen is not listed.
 */
@Composable
fun SessionsScreen(
    terminals: SshSessions,
    mqtt: MqttSessions,
    /** [ru.adsrv.frpjump.undo.PendingUndo.hidden]. */
    hidden: StateFlow<Set<String>>,
    close: SessionCloser,
    /** Profile id to its colour's name, for the marks. */
    profileColors: Map<String, String>,
    onOpenTerminal: (sessionId: Int) -> Unit,
    onOpenMqtt: (sessionId: Int) -> Unit,
) {
    val allTerminals by terminals.sessions.collectAsStateWithLifecycle()
    val allExplorers by mqtt.sessions.collectAsStateWithLifecycle()
    val gone by hidden.collectAsStateWithLifecycle()
    val list = remember(allTerminals, gone) {
        allTerminals.filterNot { sessionHidden(gone, SessionKind.TERMINAL, it.id, TunnelRef(it.profile, it.device, it.port)) }
    }
    val explorers = remember(allExplorers, gone) {
        allExplorers.filterNot { sessionHidden(gone, SessionKind.MQTT, it.id, TunnelRef(it.profile, it.device, it.port)) }
    }
    val colors = AppTheme.colors
    LazyColumn(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            Text(
                stringResource(R.string.tab_sessions),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp),
            )
        }
        if (list.isEmpty() && explorers.isEmpty()) {
            item(key = "empty") {
                Surface(color = colors.card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SessionTile(dot = null) { TerminalIcon() }
                        Text(stringResource(R.string.sessions_empty), fontSize = 14.sp, color = colors.muted, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        items(list, key = { "t${it.id}" }) { session ->
            TerminalLine(session, profileColors[session.profile], onClose = { close.terminal(session.id) }) { onOpenTerminal(session.id) }
        }
        items(explorers, key = { "m${it.id}" }) { session ->
            MqttLine(session, profileColors[session.profile], onClose = { close.mqtt(session.id) }) { onOpenMqtt(session.id) }
        }
    }
}

@Composable
private fun TerminalLine(session: SshSession, profileColor: String?, onClose: () -> Unit, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val state by session.state.collectAsStateWithLifecycle()
    val (text, color) = when (state) {
        SshState.Connecting -> stringResource(R.string.terminal_state_connecting) to colors.muted
        SshState.Open -> stringResource(R.string.terminal_state_open) to colors.accent
        is SshState.Reconnecting -> stringResource(R.string.terminal_state_reconnecting) to colors.muted
        is SshState.Ended -> stringResource(R.string.terminal_state_ended) to colors.muted
        else -> stringResource(R.string.terminal_state_asks) to colors.warning
    }
    SessionLine(session.title, text, color, profileColor, onClose, onClick) { TerminalIcon() }
}

/** An explorer session: its device and port, as the explorer's bar names it. */
@Composable
private fun MqttLine(session: MqttSession, profileColor: String?, onClose: () -> Unit, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val state by session.state.collectAsStateWithLifecycle()
    val (text, color) = when (state) {
        MqttState.Connecting -> stringResource(R.string.terminal_state_connecting) to colors.muted
        is MqttState.Open -> stringResource(R.string.mqtt_session_open) to colors.accent
        MqttState.Reconnecting -> stringResource(R.string.mqtt_reconnecting) to colors.warning
        is MqttState.NeedsLogin -> stringResource(R.string.mqtt_state_login) to colors.warning
        is MqttState.Failed -> stringResource(R.string.mqtt_session_not_connected) to colors.error
        MqttState.Ended -> stringResource(R.string.mqtt_session_ended) to colors.muted
    }
    SessionLine("${session.device}:${session.port}", text, color, profileColor, onClose, onClick) {
        Text(stringResource(R.string.kind_mqtt_tile), fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.ink)
    }
}

@Composable
private fun SessionLine(title: String, state: String, stateColor: Color, profileColor: String?, onClose: () -> Unit, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    val colors = AppTheme.colors
    Surface(onClick = onClick, color = colors.card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SessionTile(dot = colors.profile(profileColor ?: "grey"), glyph = glyph)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = stateColor)
            }
            // At the row's end, as Disconnect on a connection row: ✕ as the other closes (search, a transfer).
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.session_close_named, title), tint = colors.muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun TerminalIcon() = Icon(TerminalGlyph, contentDescription = null, tint = AppTheme.colors.ink, modifier = Modifier.size(22.dp))

/** The session's glyph on a tile; [dot], the profile's colour, in the corner. */
@Composable
private fun SessionTile(dot: Color?, glyph: @Composable () -> Unit) {
    val colors = AppTheme.colors
    Box(Modifier.size(40.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .background(colors.tile, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            glyph()
        }
        dot?.let {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .background(colors.card, CircleShape)
                    .padding(2.dp)
                    .background(it, CircleShape),
            )
        }
    }
}
