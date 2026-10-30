package ru.adsrv.frpjump.ui.devices

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.ports.DevicePort
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.VisitorState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCardScreen(vm: DeviceCardViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var adding by rememberSaveable { mutableStateOf(false) }
    // Asked at the first Connect, before connecting: a notification enqueued
    // while the permission is still undecided is dropped, and the service's
    // notification is what "Disconnect all" lives on. Connects either way.
    var waitingPort by remember { mutableStateOf<DevicePort?>(null) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        waitingPort?.let(vm::connect)
        waitingPort = null
    }
    val connect = { port: DevicePort ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            waitingPort = port
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.connect(port)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.device, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.tunnelCount > 0) TextButton(onClick = vm::disconnectAll) { Text("Disconnect all") }
                    IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Add port") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.mismatch?.let { item { Banner("$it Running tunnels are left as they are.") } }
            state.syncError?.let { item { Banner(it) } }
            items(state.rows, key = { it.port.port }) { row ->
                PortCard(
                    row = row,
                    onConnect = { connect(row.port) },
                    onDisconnect = { vm.disconnect(row.port) },
                    onRemove = { vm.removePort(row.port) },
                    onOpenBrowser = { port -> openInBrowser(context, port) },
                )
            }
        }
    }
    if (adding) {
        AddPortDialog(
            taken = state.rows.map { it.port.port }.toSet(),
            onAdd = {
                vm.addPort(it)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PortCard(
    row: PortRow,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
    onOpenBrowser: (Int) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val tunnel = row.tunnel
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${row.port.label} · ${row.port.port}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!row.preset && tunnel == null && row.pending == null) {
                    IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove port") }
                }
            }
            Text(statusText(row), style = MaterialTheme.typography.bodyMedium)
            row.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (tunnel != null) {
                val address = "127.0.0.1:${tunnel.localPort}"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(address, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clipboard.setText(AnnotatedString(address)) }) { Text("Copy") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tunnel == null) {
                    Button(onClick = onConnect, enabled = row.pending == null) { Text("Connect") }
                } else {
                    OutlinedButton(onClick = onDisconnect, enabled = row.pending == null) { Text("Disconnect") }
                    if (row.port.kind == PortKind.WEB) {
                        Button(onClick = { onOpenBrowser(tunnel.localPort) }) { Text("Open in browser") }
                    }
                }
            }
        }
    }
}

private fun statusText(row: PortRow): String = when {
    row.pending == PendingOp.CONNECTING && row.tunnel == null -> "Connecting…"
    row.pending == PendingOp.DISCONNECTING -> "Disconnecting…"
    row.tunnel == null -> "Not connected"
    else -> label(row.tunnel.state)
}

private fun label(state: VisitorState): String {
    val text = when (state.status) {
        TunnelStatus.CONNECTING -> "Connecting…"
        TunnelStatus.READY -> "Ready: direct or relayed is decided on first use"
        TunnelStatus.DIRECT -> "Direct (peer-to-peer)"
        TunnelStatus.RELAYED -> "Relayed"
        TunnelStatus.FAILED -> "Failed"
    }
    return if (state.detail != null && state.status == TunnelStatus.FAILED) "$text: ${state.detail}" else text
}

private fun openInBrowser(context: android.content.Context, localPort: Int) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$localPort/")))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: the address can still be copied.
    }
}

@Composable
private fun AddPortDialog(taken: Set<Int>, onAdd: (DevicePort) -> Unit, onDismiss: () -> Unit) {
    var portText by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(PortKind.WEB) }
    val port = portText.trim().toIntOrNull()
    val problem = when {
        portText.isBlank() -> null
        port == null || port !in 1..65535 -> "A port is 1–65535."
        port in taken -> "This port is already on the card."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add port") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PortKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.name.lowercase()) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = port != null && problem == null,
                onClick = { port?.let { onAdd(DevicePort(it, label.trim().ifEmpty { "Port $it" }, kind)) } },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
