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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
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

/** The Terminals tab: the open SSH sessions; tap one to show it in the terminal. */
@Composable
fun TerminalsScreen(
    sessions: SshSessions,
    /** Profile id to its colour's name, for the marks. */
    profileColors: Map<String, String>,
    onOpen: (sessionId: Int) -> Unit,
) {
    val list by sessions.sessions.collectAsStateWithLifecycle()
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
                stringResource(R.string.tab_terminals),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp),
            )
        }
        if (list.isEmpty()) {
            item(key = "empty") {
                Surface(color = colors.card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TerminalTile(dot = null)
                        Text(stringResource(R.string.terminals_empty), fontSize = 14.sp, color = colors.muted, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        items(list, key = { it.id }) { session -> SessionLine(session, profileColors[session.profile]) { onOpen(session.id) } }
    }
}

@Composable
private fun SessionLine(session: SshSession, profileColor: String?, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val state by session.state.collectAsStateWithLifecycle()
    val (text, color) = when (state) {
        SshState.Connecting -> stringResource(R.string.terminal_state_connecting) to colors.muted
        SshState.Open -> stringResource(R.string.terminal_state_open) to colors.accent
        is SshState.Ended -> stringResource(R.string.terminal_state_ended) to colors.muted
        else -> stringResource(R.string.terminal_state_asks) to colors.warning
    }
    Surface(onClick = onClick, color = colors.card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TerminalTile(dot = colors.profile(profileColor ?: "grey"))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(session.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
            }
        }
    }
}

/** The terminal glyph on a tile; [dot], the profile's colour, in the corner. */
@Composable
private fun TerminalTile(dot: Color?) {
    val colors = AppTheme.colors
    Box(Modifier.size(40.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .background(colors.tile, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TerminalGlyph, contentDescription = null, tint = colors.ink, modifier = Modifier.size(22.dp))
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
