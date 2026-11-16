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
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import ru.adsrv.frpjump.sessions.SessionCloser
import ru.adsrv.frpjump.sessions.sessionHidden
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.ui.SectionTitleRow
import ru.adsrv.frpjump.ui.SettingsGear

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
    onSettings: () -> Unit,
    /** The assistant's own sessions: listed in a group of their own while the owner has them not as tabs. Null: none (tests). */
    assistant: ru.adsrv.frpjump.assistant.AssistantSessions? = null,
) {
    val allTerminals by terminals.sessions.collectAsStateWithLifecycle()
    val everyTerminal by terminals.all.collectAsStateWithLifecycle()
    val remembered by (assistant?.entries ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<ru.adsrv.frpjump.assistant.AssistantSessions.Entry>()) }).collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val allExplorers by mqtt.sessions.collectAsStateWithLifecycle()
    val gone by hidden.collectAsStateWithLifecycle()
    val list = remember(allTerminals, gone) {
        allTerminals.filterNot { sessionHidden(gone, SessionKind.TERMINAL, it.id, TunnelRef(it.profile, it.device, it.port)) }
    }
    val explorers = remember(allExplorers, gone) {
        allExplorers.filterNot { sessionHidden(gone, SessionKind.MQTT, it.id, TunnelRef(it.profile, it.device, it.port)) }
    }
    // The assistant's sessions as its records say (the records are the ONE list, each once), except those the owner has opened as tabs (they are in the list above).
    val mine = remember(everyTerminal, remembered, gone) {
        remembered.mapNotNull { entry ->
            val live = everyTerminal.firstOrNull { it.tabNameKey() == entry.key }
            val id = live?.id ?: entry.id
            if (live?.shown?.value == true || sessionHidden(gone, SessionKind.TERMINAL, id, TunnelRef(entry.key.profile, entry.key.device, entry.key.port))) null
            else AssistantRow(entry.id, entry.name?.text ?: ru.adsrv.frpjump.ui.ssh.tabLabel(entry.key.user, entry.key.device, entry.key.port, live?.number ?: ru.adsrv.frpjump.ssh.tmuxNumber(entry.key.session).toInt().coerceAtLeast(1)), live, id)
        }
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
            SectionTitleRow(
                stringResource(R.string.tab_sessions),
                Modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
            ) { SettingsGear(onSettings) }
        }
        if (list.isEmpty() && explorers.isEmpty() && mine.isEmpty()) {
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
        // The assistant's own sessions: only while there are some. Open shows one as a normal tab (attaching it first when it is detached); it stays marked as the assistant's.
        if (mine.isNotEmpty()) {
            item(key = "assistant-title") {
                Text(stringResource(R.string.sessions_assistant_group), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp).testTag("sessions_assistant_group"))
            }
            items(mine, key = { "a${it.id}" }) { row ->
                AssistantLine(row, onOpen = { assistant?.let { a -> scope.launch { a.show(row.id)?.let { onOpenTerminal(it.id) } } } }, onClose = { close.terminal(row.sessionId) })
            }
        }
    }
}

/** One of the assistant's sessions in the group: [session] is null while it is remembered and not attached (detached). */
internal class AssistantRow(val id: Int, val title: String, val session: SshSession?, /** The id the owner's Close and the Undo use: the live session's, else the record's. */ val sessionId: Int = id)

@Composable
internal fun AssistantLine(row: AssistantRow, onOpen: () -> Unit, onClose: () -> Unit) {
    val colors = AppTheme.colors
    val live = row.session?.state?.collectAsStateWithLifecycle()?.value
    val (text, color) = when {
        row.session == null -> stringResource(R.string.terminal_state_detached) to colors.muted
        live == SshState.Open -> stringResource(R.string.terminal_state_open) to colors.accent
        live is SshState.Ended -> stringResource(R.string.terminal_state_ended) to colors.muted
        live is SshState.Reconnecting -> stringResource(R.string.terminal_state_reconnecting) to colors.muted
        live == SshState.Connecting -> stringResource(R.string.terminal_state_connecting) to colors.muted
        else -> stringResource(R.string.terminal_state_asks) to colors.warning
    }
    Surface(color = colors.card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("sessions_assistant_${row.id}")) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SessionTile(dot = null) { TerminalIcon() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
            }
            androidx.compose.material3.TextButton(onClick = onOpen, modifier = Modifier.testTag("sessions_assistant_open_${row.id}")) { Text(stringResource(R.string.sessions_assistant_open)) }
            androidx.compose.material3.TextButton(onClick = onClose, modifier = Modifier.testTag("sessions_assistant_close_${row.id}")) { Text(stringResource(R.string.sessions_assistant_close)) }
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
