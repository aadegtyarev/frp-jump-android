package ru.adsrv.frpjump.ui.ssh

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ui.AppTheme

/** What the session tabs do; ids are [SshSession.id]s. */
class SessionTabActions(
    val select: (Int) -> Unit,
    val close: (Int) -> Unit,
    val reconnect: (Int) -> Unit,
    /** An ended session again, as another user or with another method, in its tab. */
    val changeLogin: (Int) -> Unit,
    /** "+": another session like the selected one. */
    val add: () -> Unit,
    /** Long press on "+": the login dialog for the selected one's device and port. */
    val addAs: () -> Unit,
    /** "Use key login" for a session's user, device and port. */
    val keyLogin: (Int) -> Unit,
    /** Whether a tab's menu offers it: that login is still by password. */
    val offersKeyLogin: (Int) -> Boolean,
)

/**
 * The open sessions as a thin row of tabs: the profile's colour, user and
 * device, the state when it is not plain open, and "+" at the end. Long
 * press on a tab: Close / Reconnect / Change login / Use key login.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionTabs(list: List<SshSession>, selectedId: Int, profileColors: Map<String, String>, actions: SessionTabActions) {
    val colors = AppTheme.colors
    val row = rememberLazyListState()
    val at = list.indexOfFirst { it.id == selectedId }
    // A tab picked or opened off screen comes into view.
    LaunchedEffect(selectedId, at) { if (at >= 0) row.animateScrollToItem(at) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(TAB_ROW_HEIGHT)
            .background(colors.termBar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = row,
            // Not filling: "+" follows the last tab, and stays at the edge when they overflow.
            modifier = Modifier
                .weight(1f, fill = false)
                .selectableGroup(),
            contentPadding = PaddingValues(start = 8.dp, end = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(list, key = { it.id }) { s ->
                SessionTab(s, tabLabel(s.user, s.device, s.port, s.number), on = s.id == selectedId, dot = colors.termProfile(profileColors[s.profile] ?: "grey"), actions)
            }
        }
        val description = stringResource(R.string.terminal_new_session)
        val asLabel = stringResource(R.string.terminal_new_session_as)
        Box(
            Modifier
                .size(TAB_ROW_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(role = Role.Button, onLongClickLabel = asLabel, onLongClick = actions.addAs, onClick = actions.add)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = colors.onTerm, modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionTab(s: SshSession, label: String, on: Boolean, dot: Color, actions: SessionTabActions) {
    val colors = AppTheme.colors
    val state by s.state.collectAsStateWithLifecycle()
    val connecting = state == SshState.Connecting || state is SshState.Reconnecting
    val ended = state is SshState.Ended
    var menu by remember { mutableStateOf(false) }
    val stateWord = stringResource(
        when (state) {
            SshState.Connecting -> R.string.terminal_state_connecting
            SshState.Open -> R.string.terminal_state_open
            is SshState.Reconnecting -> R.string.terminal_state_reconnecting
            is SshState.HostKeyUnknown, is SshState.HostKeyChanged -> R.string.terminal_state_asks
            is SshState.Ended -> R.string.terminal_state_ended
        },
    )
    val menuLabel = stringResource(R.string.terminal_tab_menu)
    Box {
        Row(
            Modifier
                .height(TAB_HEIGHT)
                .clip(RoundedCornerShape(8.dp))
                .background(if (on) colors.termKey else Color.Transparent)
                .combinedClickable(
                    role = Role.Tab,
                    onLongClickLabel = menuLabel,
                    onLongClick = { menu = true },
                    onClick = { actions.select(s.id) },
                )
                .semantics {
                    selected = on
                    contentDescription = "$label, $stateWord"
                }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            // Ended or failed: a red dot next to the profile's, so the profile still shows.
            if (ended) Box(Modifier.size(8.dp).background(colors.termError, CircleShape))
            if (connecting) {
                CircularProgressIndicator(color = colors.termMuted, strokeWidth = 1.5.dp, modifier = Modifier.size(10.dp))
            }
            Text(
                label,
                fontSize = 13.sp,
                color = when {
                    connecting -> colors.termMuted.copy(alpha = 0.7f)
                    on -> colors.onTerm
                    else -> colors.termMuted
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .clearAndSetSemantics {},
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.terminal_close_session)) }, onClick = {
                menu = false
                actions.close(s.id)
            })
            // Only for an ended one: on a live shell it would cut it off unasked.
            if (ended) {
                DropdownMenuItem(text = { Text(stringResource(R.string.terminal_reconnect)) }, onClick = {
                    menu = false
                    actions.reconnect(s.id)
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.terminal_change_login)) }, onClick = {
                    menu = false
                    actions.changeLogin(s.id)
                })
            }
            if (actions.offersKeyLogin(s.id)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.key_login)) }, onClick = {
                    menu = false
                    actions.keyLogin(s.id)
                })
            }
        }
    }
}

private val TAB_ROW_HEIGHT = 36.dp
private val TAB_HEIGHT = 30.dp
