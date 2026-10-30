package ru.adsrv.frpjump.ui.connections

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.ssh.SshLoginDialog

@Composable
fun kindLabel(kind: PortKind): String = stringResource(
    when (kind) {
        PortKind.WEB -> R.string.kind_web
        PortKind.SSH -> R.string.kind_ssh
        PortKind.TCP -> R.string.kind_tcp
    },
)

/** The connection's name; "Port N" for a tunnel no saved connection names. */
@Composable
fun rowName(row: ConnectionRow): String =
    if (row.saved) row.connection.name else stringResource(R.string.connection_unsaved, row.connection.port)

/** How a state reads: its colour family. */
enum class Tone { GOOD, WARN, BAD, NEUTRAL }

/** The tunnel's state in the words used everywhere; null when not connected and nothing is going on. */
@Composable
fun stateOf(row: ConnectionRow): Pair<String, Tone>? {
    val tunnel = row.tunnel
    return when {
        row.pending == PendingOp.DISCONNECTING -> stringResource(R.string.state_disconnecting) to Tone.NEUTRAL
        tunnel == null && row.pending == PendingOp.CONNECTING -> stringResource(R.string.state_connecting) to Tone.NEUTRAL
        tunnel == null -> null
        else -> when (tunnel.state.status) {
            TunnelStatus.CONNECTING -> stringResource(R.string.state_connecting) to Tone.NEUTRAL
            TunnelStatus.READY -> stringResource(R.string.state_connected) to Tone.GOOD
            TunnelStatus.DIRECT -> stringResource(R.string.state_direct) to Tone.GOOD
            TunnelStatus.RELAYED -> stringResource(R.string.state_through_server) to Tone.WARN
            TunnelStatus.FAILED -> stringResource(R.string.state_failed) to Tone.BAD
        }
    }
}

/** What went wrong with [row], in plain words: its last connect/disconnect, or frpc's reason. */
fun problemOf(row: ConnectionRow): String? =
    row.error ?: row.tunnel?.state?.takeIf { it.status == TunnelStatus.FAILED }?.detail

/** Text and container colours of a [Tone]. */
@Composable
fun toneColors(tone: Tone): Pair<Color, Color> {
    val c = AppTheme.colors
    return when (tone) {
        Tone.GOOD -> c.onAccentContainer to c.accentContainer
        Tone.WARN -> c.onWarningContainer to c.warningContainer
        Tone.BAD -> c.onErrorContainer to c.errorContainer
        Tone.NEUTRAL -> c.muted to c.tile
    }
}

/** The state as a pill, as the device card shows it. */
@Composable
fun StatePill(text: String, tone: Tone) {
    val (ink, container) = toneColors(tone)
    Text(
        text,
        color = ink,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .background(container, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** `127.0.0.1:<local port>` in the mockups' monospace. */
@Composable
fun LocalAddress(port: Int, size: Int = 13, modifier: Modifier = Modifier) {
    Text(
        localAddress(port),
        fontFamily = FontFamily.Monospace,
        fontSize = size.sp,
        color = AppTheme.colors.muted,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

fun localAddress(port: Int) = "127.0.0.1:$port"

/** The main action of an active connection: Open, Terminal, or Copy address. */
@Composable
fun mainActionLabel(row: ConnectionRow): String = stringResource(
    when {
        !row.saved -> R.string.copy_address
        row.connection.kind == PortKind.WEB -> R.string.open
        row.connection.kind == PortKind.SSH -> R.string.terminal
        else -> R.string.copy_address
    },
)

/** A filled accent button of the mockups' rows (40 dp high). */
@Composable
fun MainButton(text: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.accent),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.height(40.dp),
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

/**
 * The outlined "Connect" of an inactive row; a spinner in its place while
 * connecting, "Retry" after a failure.
 */
@Composable
fun ConnectButton(row: ConnectionRow, onClick: () -> Unit) {
    val c = AppTheme.colors
    val busy = row.pending != null
    OutlinedButton(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (busy) c.outline else c.accent),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = c.card, contentColor = c.accent),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.height(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // The label keeps the button's width while the spinner shows.
            val label = stringResource(if (row.error != null) R.string.retry else R.string.connect)
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (busy) Color.Transparent else c.accent)
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
        }
    }
}

/**
 * Runs an action that brings a tunnel up after asking for notifications the
 * first time: a notification enqueued while the permission is undecided is
 * dropped, and the service's notification is what "Disconnect all" lives
 * on. The action runs either way.
 */
@Composable
fun rememberNotificationsFirst(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val waiting = remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        waiting.value?.invoke()
        waiting.value = null
    }
    return { action ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            waiting.value = action
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
}

/** Which dialog or sheet a connections screen shows. */
@Stable
class ConnectionUi {
    var login by mutableStateOf<ConnectionRow?>(null)
    var deleting by mutableStateOf<ConnectionRow?>(null)
    var editing by mutableStateOf<ConnectionRow?>(null)
    var menu by mutableStateOf<String?>(null)
}

/** Everything a row can do, wired to [actions] and the screen's navigation. */
class RowActions(
    val connect: (ConnectionRow) -> Unit,
    val main: (ConnectionRow) -> Unit,
    val disconnect: (ConnectionRow) -> Unit,
    val copy: (ConnectionRow) -> Unit,
    val browser: (ConnectionRow) -> Unit,
    val sshApp: (ConnectionRow) -> Unit,
    val change: (ConnectionRow) -> Unit,
    val delete: (ConnectionRow) -> Unit,
)

@Composable
fun rememberRowActions(
    ui: ConnectionUi,
    actions: ConnectionActions,
    onOpenWeb: (ConnectionRow) -> Unit,
): RowActions {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val notificationsFirst = rememberNotificationsFirst()
    val copy = { row: ConnectionRow ->
        row.tunnel?.let {
            val address = localAddress(it.localPort)
            clipboard.setText(AnnotatedString(address))
            Toast.makeText(context, context.getString(R.string.address_copied, address), Toast.LENGTH_SHORT).show()
        }
        Unit
    }
    return RowActions(
        connect = { row -> notificationsFirst { actions.connect(row) } },
        main = { row ->
            when {
                row.tunnel == null -> Unit
                !row.saved || row.connection.kind == PortKind.TCP -> copy(row)
                row.connection.kind == PortKind.WEB -> onOpenWeb(row)
                else -> ui.login = row
            }
        },
        disconnect = actions::disconnect,
        copy = copy,
        browser = { row -> row.tunnel?.let { openInBrowser(context, row.connection, it.localPort) } },
        sshApp = { row -> row.tunnel?.let { openSshExternally(context, row.connection.sshUser, it.localPort) } },
        change = { ui.editing = it },
        delete = { ui.deleting = it },
    )
}

/** The rare actions of a row, in a "⋮" menu (or on a long press). */
@Composable
fun ConnectionMenu(expanded: Boolean, row: ConnectionRow, actions: RowActions, onDismiss: () -> Unit) {
    val kind = row.connection.kind
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val item = @Composable { label: Int, action: (ConnectionRow) -> Unit ->
            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                onDismiss()
                action(row)
            })
        }
        if (row.tunnel != null) {
            item(R.string.disconnect, actions.disconnect)
            if (row.saved && kind != PortKind.TCP) item(R.string.copy_address, actions.copy)
            if (row.saved && kind == PortKind.WEB) item(R.string.open_in_browser, actions.browser)
            if (row.saved && kind == PortKind.SSH) item(R.string.open_in_ssh_app, actions.sshApp)
        }
        if (row.saved) {
            item(R.string.change, actions.change)
            item(R.string.delete, actions.delete)
        }
    }
}

/**
 * The dialogs and the sheet of a connections screen: SSH login, delete,
 * change. [existing] lists a profile's connections, for the sheet's checks.
 */
@Composable
fun ConnectionDialogs(
    ui: ConnectionUi,
    actions: ConnectionActions,
    existing: (profile: String) -> List<Connection>,
    /** The screen's rows as they are now, for the sheet's lock. */
    rows: List<ConnectionRow>,
    onOpenTerminal: (sessionId: Int) -> Unit,
) {
    val context = LocalContext.current
    ui.login?.let { row ->
        SshLoginDialog(
            target = "${row.connection.device}:${row.connection.port}",
            initial = actions.sshLogin(row),
            hasRememberedPassword = { actions.hasRememberedPassword(row, it) },
            onConnect = { user, method: SshAuthMethod, password, remember ->
                ui.login = null
                actions.openSsh(row, user, method, password, remember)?.let(onOpenTerminal)
            },
            onDismiss = { ui.login = null },
        )
    }
    ui.deleting?.let { row ->
        var busy by remember(row.key) { mutableStateOf(false) }
        var error by remember(row.key) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) ui.deleting = null },
            title = { Text(stringResource(R.string.delete_title, row.connection.name)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(if (actions.deleteDisconnects(row)) R.string.delete_text_tunnel else R.string.delete_text))
                    error?.let { Text(it, color = AppTheme.colors.error, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    error = null
                    actions.delete(row) { failure ->
                        busy = false
                        if (failure == null) ui.deleting = null else error = context.getString(R.string.delete_failed, failure)
                    }
                }) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.delete), color = AppTheme.colors.error)
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { ui.deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    ui.editing?.let { row ->
        // Live, not as the row was when "Change" was tapped: a tunnel may come up meanwhile.
        val now = rows.firstOrNull { it.key == row.key } ?: row
        ConnectionSheet(
            device = row.connection.device,
            editing = row.connection,
            portLocked = now.active || now.pending != null,
            existing = existing(row.profile),
            onSave = { connection, _, done -> actions.save(row.profile, connection, previous = row.connection, connectNow = false, onDone = done) },
            onDismiss = { ui.editing = null },
        )
    }
}

private fun openInBrowser(context: Context, connection: Connection, localPort: Int) {
    val scheme = if (connection.https) "https" else "http"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://127.0.0.1:$localPort/")))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.no_browser), Toast.LENGTH_SHORT).show()
    }
}

/** Hands the tunnel to an SSH app (Termius, JuiceSSH, …); a toast if none is installed. */
private fun openSshExternally(context: Context, user: String, localPort: Int) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("ssh://${Uri.encode(user)}@127.0.0.1:$localPort")))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.no_ssh_app), Toast.LENGTH_SHORT).show()
    }
}
