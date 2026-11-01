package ru.adsrv.frpjump.ui.devices

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.devices.filterDevices
import ru.adsrv.frpjump.devices.portsShown
import ru.adsrv.frpjump.ui.LinkGlyph
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.wasOnline
import ru.adsrv.frpjump.ui.NotificationsOffLine
import ru.adsrv.frpjump.ui.SearchBar
import ru.adsrv.frpjump.ui.SearchGlyph
import ru.adsrv.frpjump.ui.PowerGlyph
import ru.adsrv.frpjump.ui.ageText
import ru.adsrv.frpjump.ui.caption
import ru.adsrv.frpjump.ui.shortTime
import ru.adsrv.frpjump.ui.profiles.Chip
import ru.adsrv.frpjump.ui.profiles.ProfileItem
import ru.adsrv.frpjump.ui.profiles.ProfileSwitcher
import ru.adsrv.frpjump.ui.profiles.removedElsewhere
import java.time.Instant

/**
 * The current profile's devices under the profile switcher. [profiles]
 * feeds the switcher, the tunnel count and the line about profiles
 * removed on their servers; [onProfiles] opens the profile list,
 * [onTunnels] the Connections tab (the tunnel count's tap).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    vm: DevicesViewModel,
    profiles: List<ProfileItem>,
    onProfiles: () -> Unit,
    onOpen: (String) -> Unit,
    onTunnels: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    // The app's poller fetches the list only while this screen is visible.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.shown(true)
            try {
                awaitCancellation()
            } finally {
                vm.shown(false)
            }
        }
    }
    // E.g. "Forget profile" done, or the server not told: say it on the screen the owner lands on.
    val current = profiles.firstOrNull { it.current }
    LaunchedEffect(notice, current?.id) {
        if (notice != null) vm.takeNotice()?.let { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long) }
    }
    // Search: the bar turns into a field and the list follows it; ✕ or Back closes it and shows them all again.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // Focus and keyboard only when the magnifier opened it, not on every return to this screen.
    var focusOnOpen by rememberSaveable { mutableStateOf(false) }
    val closeSearch = {
        searching = false
        query = ""
        focusOnOpen = false
    }
    BackHandler(enabled = searching) { closeSearch() }
    // Another profile shown: its list is not filtered by the old query.
    var searchedProfile by rememberSaveable { mutableStateOf(current?.id) }
    LaunchedEffect(current?.id) {
        val now = current?.id ?: return@LaunchedEffect
        if (searchedProfile != null && searchedProfile != now) closeSearch()
        searchedProfile = now
    }
    // The row swiped open, one at a time; another profile shown starts with none. Back closes it first.
    val openRow = remember(current?.id) { OpenRow() }
    BackHandler(enabled = openRow.name != null) { openRow.close() }
    Scaffold(
        // Anywhere on the screen, after the rows saw the finger: one landing off the open row closes it.
        modifier = Modifier.closesOpenRow(openRow),
        topBar = {
            if (searching) {
                SearchBar(
                    query,
                    onQuery = { query = it },
                    onClose = closeSearch,
                    focus = focusOnOpen,
                    onFocused = { focusOnOpen = false },
                    label = stringResource(R.string.devices_search),
                    hint = stringResource(R.string.devices_search_hint),
                )
            } else {
                // The bar's "disconnect all": this profile's tunnels, as its count shows, go down at once, with Undo.
                Header(current, onProfiles, onTunnels, onDisconnect = { current?.let { vm.actions.disconnectProfile(it.id) } }, onSearch = {
                    searching = true
                    focusOnOpen = true
                }.takeIf { !state.devices.isNullOrEmpty() })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            RemovedElsewhereLine(removedElsewhere(profiles), onProfiles)
            NotificationsOffLine()
            // Above the list, not in it: an item inserted before the first row would be scrolled out of sight.
            val staleSince = state.staleSince
            if (staleSince != null) {
                OfflineBanner(staleSince, state.unreachable, state.error, onRetry = vm::pull)
            } else state.error?.let { error ->
                Text(error, color = AppTheme.colors.error, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            DeviceRows(state, query, openRow, vm::pull, onOpen, onDisconnect = { device -> current?.let { vm.actions.disconnectDevice(it.id, device) } })
        }
    }
}

/**
 * The switcher, this profile's tunnel count (a tap: [onTunnels]) with the
 * power button that disconnects them all ([onDisconnect]), and search
 * ([onSearch], null while there is nothing to search); terminals and settings are tabs of their own.
 */
@Composable
private fun Header(
    current: ProfileItem?,
    onProfiles: () -> Unit,
    onTunnels: () -> Unit,
    onDisconnect: () -> Unit,
    onSearch: (() -> Unit)?,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) { ProfileSwitcher(current, onProfiles) }
        current?.tunnels?.takeIf { it > 0 }?.let {
            TunnelsChip(it, onTunnels)
            val label = stringResource(R.string.disconnect_all_tunnels)
            IconButton(onClick = onDisconnect, modifier = Modifier.size(44.dp).semantics { contentDescription = label }) {
                Icon(PowerGlyph, contentDescription = null, tint = colors.error, modifier = Modifier.size(20.dp))
            }
        }
        onSearch?.let {
            IconButton(onClick = it, modifier = Modifier.size(44.dp)) {
                Icon(SearchGlyph, contentDescription = stringResource(R.string.devices_search), tint = colors.ink, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** The tunnel count; a tap leads to Connections, open ones on top. Tall as the bar for the finger, the ripple on the chip. */
@Composable
private fun TunnelsChip(count: Int, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.tunnels_show), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.clip(CircleShape).indication(source, ripple())) {
            Chip(pluralStringResource(R.plurals.tunnels, count, count), colors.accentContainer, colors.onAccentContainer)
        }
    }
}

/** One line for profiles whose server deleted this phone while another profile was shown. Tap for the profile list. */
@Composable
private fun RemovedElsewhereLine(removed: List<ProfileItem>, onClick: () -> Unit) {
    if (removed.isEmpty()) return
    val colors = AppTheme.colors
    Text(
        if (removed.size == 1) {
            stringResource(R.string.removed_elsewhere_one, removed.single().name)
        } else {
            pluralStringResource(R.plurals.removed_elsewhere_many, removed.size, removed.size)
        },
        color = colors.onErrorContainer,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(colors.errorContainer, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/**
 * The device rows; a row with tunnels open swipes left to show its power
 * button ([onDisconnect] with the device's name). One row is open at a time;
 * it slides back when the list moves or refreshes (and, from the screen
 * around it, on Back or a finger landing anywhere else).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceRows(state: DevicesUiState, query: String, openRow: OpenRow, onPull: () -> Unit, onOpen: (String) -> Unit, onDisconnect: (String) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState, openRow) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it) openRow.close() }
    }
    LaunchedEffect(state.refreshing) { if (state.refreshing) openRow.close() }
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onPull,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val devices = state.devices
            if (devices != null && devices.isEmpty()) {
                item {
                    Text(stringResource(R.string.devices_empty), color = AppTheme.colors.muted, modifier = Modifier.padding(4.dp))
                }
            }
            val shown = devices?.let { filterDevices(it, query) }.orEmpty()
            // A search that finds nothing says so, rather than leave an empty screen.
            if (!devices.isNullOrEmpty() && shown.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.devices_no_match, query.trim()),
                        color = AppTheme.colors.muted,
                        // Read out as the typing changes it.
                        modifier = Modifier
                            .padding(4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            items(shown, key = { it.name }) { device ->
                val ports = state.tunnelPorts[device.name].orEmpty()
                val open = openRow.name == device.name
                SwipeReveal(
                    open = open,
                    enabled = ports.isNotEmpty(),
                    onOpenChange = { now -> openRow.reported(device.name, now) },
                    actionWidth = REVEAL_WIDTH,
                    action = {
                        DisconnectDeviceButton(device.name) {
                            openRow.close()
                            onDisconnect(device.name)
                        }
                    },
                    modifier = Modifier.touchesRow(openRow, device.name),
                ) {
                    DeviceItem(
                        device,
                        ports,
                        // Open: a tap puts it back; closed, it opens the card as before.
                        onClick = { if (open) openRow.close() else onOpen(device.name) },
                        onDisconnect = { onDisconnect(device.name) }.takeIf { ports.isNotEmpty() },
                    )
                }
            }
        }
    }
}

/** How far a row slides aside for its power button. */
private val REVEAL_WIDTH = 76.dp

/** The power button behind a row swiped aside: this device's tunnels, as the bar's does for the profile. */
@Composable
private fun DisconnectDeviceButton(device: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val label = stringResource(R.string.disconnect_device_tunnels, device)
    Surface(
        onClick = onClick,
        color = colors.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .width(REVEAL_WIDTH - 6.dp)
            .fillMaxHeight()
            .semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(PowerGlyph, contentDescription = null, tint = colors.error, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * One device; [onDisconnect], while it has tunnels, is also offered to
 * TalkBack as an action of the row, since the swipe is not.
 */
@Composable
private fun DeviceItem(device: DeviceRow, ports: List<Int>, onClick: () -> Unit, onDisconnect: (() -> Unit)?) {
    val colors = AppTheme.colors
    val disconnectLabel = stringResource(R.string.disconnect_device_tunnels, device.name)
    val stateText = when {
        device.online && device.stale -> wasOnline(device)
        device.online -> stringResource(R.string.device_online)
        device.lastSeen == null -> stringResource(R.string.device_never_seen)
        else -> stringResource(R.string.device_last_seen, ageText(device.lastSeen))
    }
    // Name, label, state, then the icon's name (the generic glyph says nothing).
    val spoken = listOfNotNull(
        device.name,
        device.label,
        stateText,
        stringResource(R.string.device_tunnels_open, ports.joinToString(", ")).takeIf { ports.isNotEmpty() },
        stringResource(R.string.device_disabled).takeIf { device.disabled },
        device.icon?.let { stringResource(it.caption) },
    ).joinToString(", ")
    Surface(
        onClick = onClick,
        color = colors.card,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                if (onDisconnect != null) {
                    customActions = listOf(CustomAccessibilityAction(disconnectLabel) { onDisconnect(); true })
                }
            },
    ) {
        Row(
            Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .clearAndSetSemantics { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceTile(
                device.icon,
                when {
                    !device.online -> colors.offline
                    // Online as of the last answer only.
                    device.stale -> colors.online.copy(alpha = 0.4f)
                    else -> colors.online
                },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(device.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                LabelAndState(device.label, state = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stateText,
                            fontSize = 13.sp,
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (ports.isNotEmpty()) TunnelPorts(ports)
                    }
                })
            }
            if (device.disabled) Chip(stringResource(R.string.device_disabled), colors.errorContainer, colors.onErrorContainer)
        }
    }
}

/** " · ⛓ 22 80 +2": the ports this phone has tunnels open to, in the accent; read out in words. */
@Composable
private fun TunnelPorts(ports: List<Int>) {
    val colors = AppTheme.colors
    val (shown, more) = portsShown(ports)
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(" · ", fontSize = 13.sp, color = colors.muted)
        Icon(LinkGlyph, contentDescription = null, tint = colors.accent, modifier = Modifier.size(13.dp))
        Text(
            (shown.map { it.toString() } + listOfNotNull("+$more".takeIf { more > 0 })).joinToString(" ", prefix = " "),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The list is an old one: says since when, and why if known. Tap to try again. */
@Composable
private fun OfflineBanner(since: Instant, unreachable: Boolean, error: String?, onRetry: () -> Unit) {
    val colors = AppTheme.colors
    Surface(
        onClick = onRetry,
        color = colors.warningContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(if (unreachable) R.string.offline_unreachable else R.string.offline_server_error, shortTime(since)),
                color = colors.onWarningContainer,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(error, stringResource(R.string.tap_to_retry)).joinToString(" · "),
                color = colors.onWarningContainer,
                fontSize = 13.sp,
            )
        }
    }
}
