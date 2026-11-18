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
import ru.adsrv.frpjump.connections.TunnelsView
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.snapshotFlow
import ru.adsrv.frpjump.tunnel.SessionOn
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import ru.adsrv.frpjump.ui.AssistantGlyph
import ru.adsrv.frpjump.ui.devices.OpenRow
import ru.adsrv.frpjump.ui.devices.SwipeReveal
import ru.adsrv.frpjump.ui.devices.closesOpenRow
import ru.adsrv.frpjump.ui.devices.touchesRow
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
    /** Opens the assistant chat with this draft (nothing is sent); null while the assistant is off: no such menu item and no such swipe. */
    onAskAssistant: ((draft: String) -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions by vm.actions.sessions.collectAsStateWithLifecycle(emptyList())
    val grants by vm.actions.assistantGrants.collectAsStateWithLifecycle()
    val ui = remember { ConnectionUi() }
    val actions = rememberRowActions(ui, vm.actions, onOpenWeb, onOpenTerminal, onOpenFiles, onOpenMqtt, onAskAssistant)
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
    // The row swiped open, one at a time; Back closes it first. A finger landing anywhere else, a scroll or a pull closes it too.
    val openRow = remember { OpenRow() }
    BackHandler(enabled = openRow.name != null) { openRow.close() }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, openRow) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it) openRow.close() }
    }
    LaunchedEffect(state.refreshing) { if (state.refreshing) openRow.close() }
    Column(Modifier.fillMaxSize().closesOpenRow(openRow)) {
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
                state = listState,
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
                if (all.isNotEmpty() && view.isEmpty) {
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
                tunnelRows(view, state, sessions, grants, ui, actions, { vm.actions.forgetAssistant(it) }, openRow)
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

/**
 * The rows of the Tunnels list: the favourites, a group of their own on top (each card with its device, in a fixed order), then the devices with
 * their other connections. Each card is swipeable ([TunnelCard]).
 */
internal fun LazyListScope.tunnelRows(
    view: TunnelsView,
    state: ConnectionsUiState,
    sessions: List<SessionOn>,
    grants: Map<GrantTarget, AssistantMark>,
    ui: ConnectionUi,
    actions: RowActions,
    onForgetAssistant: (ConnectionRow) -> Unit,
    openRow: OpenRow,
) {
    if (view.favorites.isNotEmpty()) {
        item(key = "favorites") { SectionHeading(stringResource(R.string.connections_favorites), stringResource(R.string.connections_favorites_spoken), first = true) }
        items(view.favorites, key = { it.row.key }) { favorite ->
            TunnelCard(favorite.row, favorite.group.offline, favorite.group.name, state, sessions, grants, ui, actions, onForgetAssistant, openRow)
        }
    }
    view.groups.forEachIndexed { index, group ->
        item(key = "device/${group.key}") {
            GroupHeader(group, state.colors[group.profile]?.takeIf { state.manyProfiles }, first = index == 0 && view.favorites.isEmpty())
        }
        items(group.rows, key = { it.key }) { row ->
            TunnelCard(row, group.offline, null, state, sessions, grants, ui, actions, onForgetAssistant, openRow)
        }
    }
}

/** The heading of the favourites group: the same place and weight as a device's name. */
@Composable
private fun SectionHeading(text: String, spoken: String, first: Boolean) {
    val muted = AppTheme.colors.muted
    Text(
        // The star keeps the muted colour it has on the cards; the words stay in ink.
        androidx.compose.ui.text.buildAnnotatedString {
            val star = text.indexOf('\u2605')
            if (star < 0) append(text) else {
                append(text.substring(0, star))
                withStyle(androidx.compose.ui.text.SpanStyle(color = muted)) { append('\u2605') }
                append(text.substring(star + 1))
            }
        },
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppTheme.colors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = if (first) 4.dp else 16.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = spoken
            },
    )
}

/** How far a card slides aside for a button. */
private val REVEAL_WIDTH = 92.dp

/**
 * One connection's card with its swipes ([SwipeReveal]): to the left it reveals "Ask the assistant" (absent while the assistant is off), to the right
 * the favourites star. Nothing runs by the swipe: the revealed button's tap does, then the card slides back. [deviceName] is set where the card has
 * no device header over it (the favourites).
 */
@Composable
private fun TunnelCard(
    row: ConnectionRow,
    offline: Boolean,
    deviceName: String?,
    state: ConnectionsUiState,
    sessions: List<SessionOn>,
    grants: Map<GrantTarget, AssistantMark>,
    ui: ConnectionUi,
    actions: RowActions,
    onForgetAssistant: (ConnectionRow) -> Unit,
    openRow: OpenRow,
) {
    val colors = AppTheme.colors
    val ask = actions.ask?.takeIf { row.saved }
    val revealed = openRow.name == row.key
    SwipeReveal(
        open = revealed,
        enabled = ask != null,
        onOpenChange = { now -> openRow.reported(row.key, now) },
        actionWidth = revealWidth(),
        action = {
            RevealButton(
                stringResource(R.string.terminal_ask_assistant),
                glyph = { Icon(AssistantGlyph, contentDescription = null, tint = colors.onAccentContainer, modifier = Modifier.size(22.dp)) },
                modifier = Modifier.testTag("swipe_ask_assistant"),
            ) {
                openRow.close()
                ask?.invoke(row)
            }
        },
        modifier = Modifier.touchesRow(openRow, row.key).testTag("tunnel_card_${row.connection.id}"),
        startAction = {
            val remove = row.connection.favorite
            RevealButton(
                stringResource(if (remove) R.string.favorite_remove else R.string.favorite_add),
                glyph = { Text(if (remove) "\u2606" else "\u2605", fontSize = 24.sp, color = colors.onAccentContainer) },
                modifier = Modifier.testTag("swipe_favorite"),
            ) {
                openRow.close()
                actions.favorite(row)
            }
        },
        startActionWidth = revealWidth(),
        startEnabled = row.saved,
    ) {
        ConnectionCard(
            row = row,
            sshMethod = state.sshMethods[row.key],
            sessions = row.sessionsIn(sessions),
            menuOpen = ui.menu == row.key,
            onMenu = { ui.menu = if (it) row.key else null },
            actions = actions,
            offline = offline,
            deviceName = deviceName,
            assistant = if (row.saved) grants[GrantTarget(row.profile, row.connection.id)] else null,
            onForgetAssistant = { onForgetAssistant(row) },
            revealed = revealed,
            onCloseReveal = openRow::close,
        )
    }
}

/** The button behind a card swiped aside; its description names the action (the glyph is drawn, not read). */
@Composable
private fun RevealButton(label: String, glyph: @Composable () -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = AppTheme.colors.accentContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .width(revealWidth() - 6.dp)
            .fillMaxHeight()
            .semantics { contentDescription = label },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            glyph()
            Text(label, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                color = AppTheme.colors.onAccentContainer, modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics { })
        }
    }
}

/** The swipe button's width: [REVEAL_WIDTH] at the normal font, wider with a larger one so its label wraps between words only. */
@Composable
private fun revealWidth(): androidx.compose.ui.unit.Dp = REVEAL_WIDTH * LocalDensity.current.fontScale.coerceIn(1f, 1.8f)
