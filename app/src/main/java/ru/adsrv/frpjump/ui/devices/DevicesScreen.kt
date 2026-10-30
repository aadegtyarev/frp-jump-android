package ru.adsrv.frpjump.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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

private const val POLL_MS = 2_000L

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
    // Poll only while this screen is visible; stops when backgrounded or covered.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollEvery(POLL_MS) }
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
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::pull,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                state.error?.let { error ->
                    item {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
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
}

@Composable
private fun DeviceItem(device: DeviceRow, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        leadingContent = {
            val color = if (device.online) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline
            Box(
                Modifier
                    .size(10.dp)
                    .background(color, CircleShape),
            )
        },
        headlineContent = { Text(device.name) },
        supportingContent = { Text(if (device.online) "online" else "last seen ${device.lastSeen}") },
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
