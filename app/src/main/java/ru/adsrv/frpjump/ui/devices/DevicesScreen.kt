package ru.adsrv.frpjump.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import ru.adsrv.frpjump.devices.DeviceRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    vm: DevicesViewModel,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onTerminals: (firstSessionId: Int) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions by vm.sshSessions.collectAsStateWithLifecycle()
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                actions = {
                    sessions.firstOrNull()?.let { first ->
                        TextButton(onClick = { onTerminals(first.id) }) {
                            Text(if (sessions.size == 1) "1 terminal" else "${sessions.size} terminals")
                        }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Above the list, not in it: an item inserted before the first row would be scrolled out of sight.
            val staleSince = state.staleSince
            if (staleSince != null) {
                OfflineBanner(staleSince, state.unreachable, state.error, onRetry = vm::pull)
            } else state.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            DeviceRows(state, vm::pull, onOpen)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceRows(state: DevicesUiState, onPull: () -> Unit, onOpen: (String) -> Unit) {
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onPull,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            val devices = state.devices
            if (devices != null && devices.isEmpty()) {
                item { Text("No other devices yet.", modifier = Modifier.padding(16.dp)) }
            }
            items(devices.orEmpty(), key = { it.name }) { device ->
                DeviceItem(device, onClick = { onOpen(device.name) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DeviceItem(device: DeviceRow, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        leadingContent = {
            val color = when {
                !device.online -> MaterialTheme.colorScheme.outline
                // Online as of the last answer only.
                device.stale -> Color(0xFF2E7D32).copy(alpha = 0.4f)
                else -> Color(0xFF2E7D32)
            }
            Box(
                Modifier
                    .size(10.dp)
                    .background(color, CircleShape),
            )
        },
        headlineContent = { Text(device.name) },
        supportingContent = {
            Text(
                when {
                    device.online && device.stale -> "was online"
                    device.online -> "online"
                    else -> "last seen ${device.lastSeen}"
                },
            )
        },
        trailingContent = if (device.disabled) {
            {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        "disabled",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        } else {
            null
        },
    )
}

/** The list is an old one: says since when, and why if known. Tap to try again. */
@Composable
private fun OfflineBanner(since: Instant, unreachable: Boolean, error: String?, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onRetry),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                (if (unreachable) "No connection to the server" else "The server returned an error") +
                    " · updated ${shortTime(since)}",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                listOfNotNull(error, "Tap to retry").joinToString(" · "),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** "14:02" today, "30 Oct 14:02" otherwise, in the phone's zone. */
private fun shortTime(at: Instant): String {
    val local = at.atZone(ZoneId.systemDefault())
    val today = LocalDate.now(local.zone)
    val pattern = if (local.toLocalDate() == today) "HH:mm" else "d MMM HH:mm"
    return DateTimeFormatter.ofPattern(pattern).format(local)
}
