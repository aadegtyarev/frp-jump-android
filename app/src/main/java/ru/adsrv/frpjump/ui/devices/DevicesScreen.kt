package ru.adsrv.frpjump.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.wasOnline
import ru.adsrv.frpjump.ui.NotificationsOffLine
import ru.adsrv.frpjump.ui.DeviceGlyph
import ru.adsrv.frpjump.ui.ageText
import ru.adsrv.frpjump.ui.shortTime
import ru.adsrv.frpjump.ui.profiles.Chip
import ru.adsrv.frpjump.ui.profiles.ProfileItem
import ru.adsrv.frpjump.ui.profiles.ProfileSwitcher
import ru.adsrv.frpjump.ui.profiles.removedElsewhere
import java.time.Instant

/**
 * The current profile's devices under the profile switcher. [profiles]
 * feeds the switcher, the tunnel count and the line about profiles
 * removed on their servers; [onProfiles] opens the profile list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    vm: DevicesViewModel,
    profiles: List<ProfileItem>,
    onProfiles: () -> Unit,
    onOpen: (String) -> Unit,
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
    Scaffold(
        topBar = {
            Header(current, onProfiles)
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
            DeviceRows(state, vm::pull, onOpen)
        }
    }
}

/** The switcher and this profile's tunnel count; terminals and settings are tabs of their own. */
@Composable
private fun Header(current: ProfileItem?, onProfiles: () -> Unit) {
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
            Chip(pluralStringResource(R.plurals.tunnels, it, it), colors.accentContainer, colors.onAccentContainer)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceRows(state: DevicesUiState, onPull: () -> Unit, onOpen: (String) -> Unit) {
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onPull,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
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
            items(devices.orEmpty(), key = { it.name }) { device ->
                DeviceItem(device, onClick = { onOpen(device.name) })
            }
        }
    }
}

@Composable
private fun DeviceItem(device: DeviceRow, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Surface(onClick = onClick, color = colors.card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceTile(
                when {
                    !device.online -> colors.offline
                    // Online as of the last answer only.
                    device.stale -> colors.online.copy(alpha = 0.4f)
                    else -> colors.online
                },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(device.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        device.online && device.stale -> wasOnline(device)
                        device.online -> stringResource(R.string.device_online)
                        device.lastSeen == null -> stringResource(R.string.device_never_seen)
                        else -> stringResource(R.string.device_last_seen, ageText(device.lastSeen))
                    },
                    fontSize = 13.sp,
                    color = colors.muted,
                )
            }
            if (device.disabled) Chip(stringResource(R.string.device_disabled), colors.errorContainer, colors.onErrorContainer)
        }
    }
}

/** A device glyph on a tile, its state as a dot in the corner. */
@Composable
private fun DeviceTile(dot: Color) {
    val colors = AppTheme.colors
    Box(Modifier.size(40.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .background(colors.tile, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(DeviceGlyph, contentDescription = null, tint = colors.ink, modifier = Modifier.size(22.dp))
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
                .size(14.dp)
                .border(2.dp, colors.card, CircleShape)
                .padding(2.dp)
                .background(dot, CircleShape),
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
