package ru.adsrv.frpjump.ui.connections

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.connections.LocalPortContext
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.tunnel.DirectMode
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.tunnel.SessionCount
import ru.adsrv.frpjump.tunnel.SessionOn
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.sessionsThrough
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.PinGlyph
import ru.adsrv.frpjump.ui.PowerGlyph
import ru.adsrv.frpjump.ui.notice
import ru.adsrv.frpjump.ui.ssh.KeyLoginDialogs
import ru.adsrv.frpjump.ui.ssh.SshLoginDialog

@Composable
fun kindLabel(kind: PortKind): String = stringResource(
    when (kind) {
        PortKind.WEB -> R.string.kind_web
        PortKind.SSH -> R.string.kind_ssh
        PortKind.TCP -> R.string.kind_tcp
        PortKind.MQTT -> R.string.kind_mqtt
    },
)

/** The connection's name; "Port N" for a tunnel no saved connection names. */
@Composable
fun rowName(row: ConnectionRow): String =
    if (row.saved) connectionLabel(row.connection) else stringResource(R.string.connection_unsaved, row.connection.port)

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
        // Whatever the tunnel itself does: the device has not opened it yet.
        tunnel.state.waitingForDevice -> stringResource(R.string.state_waiting_device) to Tone.NEUTRAL
        else -> when (tunnel.state.status) {
            TunnelStatus.CONNECTING -> stringResource(R.string.state_connecting) to Tone.NEUTRAL
            TunnelStatus.TRYING -> stringResource(R.string.state_trying_direct) to Tone.NEUTRAL
            TunnelStatus.DIRECT -> stringResource(R.string.state_direct) to Tone.GOOD
            // With direct off it is simply how tunnels work; otherwise direct would be better.
            TunnelStatus.RELAYED -> when (tunnel.direct) {
                DirectMode.OFF -> stringResource(R.string.state_through_server) to Tone.GOOD
                // The owner's own choice: nothing to warn about.
                DirectMode.KEPT -> stringResource(R.string.state_kept_through_server) to Tone.NEUTRAL
                DirectMode.ON -> stringResource(R.string.state_through_server) to Tone.WARN
            }
            TunnelStatus.FAILED -> stringResource(R.string.state_failed) to Tone.BAD
        }
    }
}

/**
 * Whether tapping [row]'s state offers "Retry direct" / "Keep through the
 * server": it goes through the server because direct did not work, or
 * because the owner kept it so on this network.
 */
fun offersDirectChoice(row: ConnectionRow): Boolean {
    val tunnel = row.tunnel ?: return false
    if (row.pending != null || tunnel.state.status != TunnelStatus.RELAYED) return false
    return tunnel.direct == DirectMode.KEPT || (tunnel.direct == DirectMode.ON && tunnel.state.directFailed)
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

/**
 * The state as a pill, as the device card shows it; [onClick] makes it a
 * button, with a "›" when [chevron] (an offer to act, not just details).
 */
@Composable
fun StatePill(text: String, tone: Tone, onClick: (() -> Unit)? = null, chevron: Boolean = true) {
    val (ink, container) = toneColors(tone)
    Text(
        if (onClick != null && chevron) "$text  ›" else text,
        color = ink,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(container, CircleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * `127.0.0.1:<local port>` in the mockups' monospace; where that does not
 * fit, `:<local port>` (a cut address is no use). A small pin before it
 * when the port is [pinned] to the connection.
 */
@Composable
fun LocalAddress(port: Int, size: Int = 13, modifier: Modifier = Modifier, pinned: Boolean = false) {
    var short by remember(port) { mutableStateOf(false) }
    if (pinned) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(PinGlyph, contentDescription = stringResource(R.string.local_port_pinned_mark), tint = AppTheme.colors.muted, modifier = Modifier.size(size.dp + 1.dp))
            LocalAddress(port, size, Modifier.weight(1f, fill = false))
        }
        return
    }
    Text(
        if (short) ":$port" else localAddress(port),
        fontFamily = FontFamily.Monospace,
        fontSize = size.sp,
        color = AppTheme.colors.muted,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { if (!short && it.hasVisualOverflow) short = true },
        modifier = modifier,
    )
}

fun localAddress(port: Int) = "127.0.0.1:$port"

/**
 * The main action of an active connection: Open, Terminal ("Terminal · 2"
 * with [sessions] of 2 or more open), Explore (the MQTT explorer), or Copy (the
 * address; short, it shares the row with Disconnect).
 */
@Composable
fun mainActionLabel(row: ConnectionRow, sessions: SessionCount = SessionCount.NONE): String = when {
    !row.saved -> stringResource(R.string.copy)
    else -> when (row.connection.kind) {
        PortKind.WEB -> stringResource(R.string.open)
        PortKind.SSH -> if (sessions.total >= 2) stringResource(R.string.terminal_count, sessions.total) else stringResource(R.string.terminal)
        PortKind.TCP -> stringResource(R.string.copy)
        PortKind.MQTT -> stringResource(R.string.mqtt_explore)
    }
}

/** The terminal sessions of an SSH row as [count] has them; nothing for none. */
fun ConnectionRow.sessionsIn(open: List<SessionOn>): SessionCount =
    if (connection.kind != PortKind.SSH || !saved) SessionCount.NONE else sessionsThrough(open, TunnelRef(profile, connection.device, connection.port))

/**
 * "1 session" / "2 sessions" by an SSH row's address, when [SessionCount.showsPill]:
 * a red dot first when any of them has ended (its tab offers Reconnect).
 */
@Composable
fun SessionsPill(count: SessionCount) {
    if (!count.showsPill) return
    val c = AppTheme.colors
    val total = pluralStringResource(R.plurals.terminal_sessions_count, count.total, count.total)
    val spoken = if (count.ended > 0) total + ", " + pluralStringResource(R.plurals.terminal_sessions_ended, count.ended, count.ended) else total
    Row(
        Modifier
            .clearAndSetSemantics { contentDescription = spoken }
            .background(c.tile, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (count.ended > 0) Box(Modifier.size(6.dp).background(c.error, CircleShape))
        Text(total, fontSize = 12.sp, color = c.muted, maxLines = 1, softWrap = false)
    }
}

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
 * "Disconnect" of an active row, next to its main action: done with a
 * connection, the owner drops it where they were. A spinner while it goes.
 */
@Composable
fun DisconnectIcon(row: ConnectionRow, size: Dp, onClick: () -> Unit) {
    val c = AppTheme.colors
    val busy = row.pending != null
    val label = stringResource(R.string.disconnect_named, rowName(row))
    Surface(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(12.dp),
        color = c.card,
        border = BorderStroke(1.dp, c.outline),
        // On the button, so the spinner keeps the name too.
        modifier = Modifier.size(size).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = c.error, strokeWidth = 2.dp)
            } else {
                Icon(PowerGlyph, contentDescription = null, tint = c.error, modifier = Modifier.size(20.dp))
            }
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

    /** The row whose "Files" was tapped: the same login dialog, for a file screen. */
    var filesLogin by mutableStateOf<ConnectionRow?>(null)

    var editing by mutableStateOf<ConnectionRow?>(null)
    var menu by mutableStateOf<String?>(null)

    /** The row whose "Through the server" was tapped: Retry direct / Keep through the server. */
    var direct by mutableStateOf<ConnectionRow?>(null)
}

/** Everything a row can do, wired to [actions] and the screen's navigation. */
class RowActions(
    val connect: (ConnectionRow) -> Unit,
    val main: (ConnectionRow) -> Unit,
    val disconnect: (ConnectionRow) -> Unit,
    val copy: (ConnectionRow) -> Unit,
    val browser: (ConnectionRow) -> Unit,
    val sshApp: (ConnectionRow) -> Unit,
    /** "Files" of a saved SSH connection, connected or not. */
    val files: (ConnectionRow) -> Unit,
    val change: (ConnectionRow) -> Unit,
    val delete: (ConnectionRow) -> Unit,
    /** Tapped "Through the server". */
    val directChoice: (ConnectionRow) -> Unit,
    /** "Use key login" of a saved SSH connection. */
    val keyLogin: (ConnectionRow) -> Unit,
    /** Whether the menu offers "Use key login": the method remembered for its port is Password. */
    val offersKeyLogin: (ConnectionRow) -> Boolean,
    /** "Change login": the login dialog, then a terminal with what it gave. */
    val changeLogin: (ConnectionRow) -> Unit,
    /** "Open as MQTT" of a saved TCP connection: it becomes MQTT and its explorer opens. */
    val openAsMqtt: (ConnectionRow) -> Unit,
)

@Composable
fun rememberRowActions(
    ui: ConnectionUi,
    actions: ConnectionActions,
    onOpenWeb: (ConnectionRow) -> Unit,
    onOpenTerminal: (sessionId: Int) -> Unit,
    onOpenFiles: (targetId: Int) -> Unit,
    onOpenMqtt: (sessionId: Int) -> Unit,
): RowActions {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val notificationsFirst = rememberNotificationsFirst()
    val copy = { row: ConnectionRow ->
        row.tunnel?.let {
            val address = localAddress(it.localPort)
            clipboard.setText(AnnotatedString(address))
            context.notice(context.getString(R.string.address_copied, address), NoticeKind.DONE, key = "copied")
        }
        Unit
    }
    return RowActions(
        connect = { row -> notificationsFirst { actions.connect(row) } },
        main = { row ->
            when {
                row.tunnel == null -> Unit
                !row.saved -> copy(row)
                else -> when (row.connection.kind) {
                    PortKind.TCP -> copy(row)
                    PortKind.WEB -> onOpenWeb(row)
                    // Back to the explorer session of this connection, or a new one; the screen shows it connecting.
                    PortKind.MQTT -> actions.openMqtt(row)?.let(onOpenMqtt)
                        ?: context.notice(context.getString(if (row.tunnel == null) R.string.files_tunnel_down else R.string.profile_gone), NoticeKind.WARN)
                    // Back to the session already open on this port; a new one is "+" on its tab row.
                    PortKind.SSH -> actions.openSession(row)?.let(onOpenTerminal) ?: run {
                        // A usable remembered login connects at once; the dialog only when there is none.
                        val login = actions.loginAtOnce(row)
                        val opened = login?.let { actions.openSsh(row, it.user, it.method, password = "", remember = true) }
                        if (opened != null) onOpenTerminal(opened) else ui.login = row
                    }
                }
            }
        },
        // No question: it shows down at once, with Undo on the notice; its sessions close when that goes.
        disconnect = actions::disconnect,
        copy = copy,
        browser = { row -> row.tunnel?.let { openInBrowser(context, row.connection, it.localPort) } },
        sshApp = { row -> row.tunnel?.let { openSshExternally(context, row.connection.sshUser, it.localPort) } },
        files = { row ->
            // Back to the file session kept on this tunnel, in its folder.
            val kept = actions.keptFiles(row)
            val login = if (kept == null) actions.loginAtOnce(row) else null
            when {
                kept != null -> onOpenFiles(kept)
                login == null -> ui.filesLogin = row
                else -> {
                    // As the dialog's Connect: the tunnel first; the file screen waits for it.
                    if (row.tunnel == null && row.pending == null) notificationsFirst { actions.connect(row) }
                    actions.openFiles(row, login.user, login.method, password = "", remember = true)?.let(onOpenFiles)
                }
            }
        },
        change = { ui.editing = it },
        // No question: it goes at once, with Undo on the notice.
        delete = actions::delete,
        directChoice = { ui.direct = it },
        // Down, the tunnel is brought up for it, as Connect does.
        keyLogin = { row -> if (row.tunnel == null) notificationsFirst { actions.startKeyLogin(row) } else actions.startKeyLogin(row) },
        offersKeyLogin = { row -> actions.sshLogin(row).method == SshAuthMethod.PASSWORD },
        changeLogin = { ui.login = it },
        // Down, the tunnel is brought up for it, as Connect does.
        openAsMqtt = { row ->
            val open = { actions.openAsMqtt(row, onOpenMqtt) }
            if (row.tunnel == null && row.pending == null) notificationsFirst(open) else open()
        },
    )
}

/** [ConnectionActions.localPortContext] kept up to date while the sheet is open. */
@Composable
fun rememberLocalPortContext(actions: ConnectionActions, profile: String): LocalPortContext {
    val flow = remember(actions, profile) { actions.localPortContexts(profile) }
    return flow.collectAsStateWithLifecycle(remember(actions, profile) { actions.localPortContext(profile) }).value
}

/** Whether [row] has a "⋮" menu: an unsaved tunnel has nothing in it but Disconnect, which is on the row. */
fun hasMenu(row: ConnectionRow): Boolean = row.saved

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
            // TCP's main action is Copy already.
            if (row.saved && kind != PortKind.TCP) item(R.string.copy_address, actions.copy)
            if (row.saved && kind == PortKind.WEB) item(R.string.open_in_browser, actions.browser)
            if (row.saved && kind == PortKind.SSH) item(R.string.open_in_ssh_app, actions.sshApp)
        }
        // Down, the tunnel is brought up once the login is given.
        if (row.saved && kind == PortKind.SSH) item(R.string.files, actions.files)
        // Terminal logs in at once with what is remembered: another user or method is chosen here.
        if (row.saved && kind == PortKind.SSH) item(R.string.terminal_change_login, actions.changeLogin)
        if (row.saved && kind == PortKind.SSH && actions.offersKeyLogin(row)) item(R.string.key_login, actions.keyLogin)
        if (row.saved && kind == PortKind.TCP) item(R.string.mqtt_open_as, actions.openAsMqtt)
        if (row.saved) {
            item(R.string.change, actions.change)
            item(R.string.delete, actions.delete)
        }
    }
}

/**
 * The dialogs and the sheet of a connections screen: SSH login, change. [existing] lists a profile's connections, for the sheet's checks.
 */
@Composable
fun ConnectionDialogs(
    ui: ConnectionUi,
    actions: ConnectionActions,
    existing: (profile: String) -> List<Connection>,
    /** The screen's rows as they are now, for the sheet's lock. */
    rows: List<ConnectionRow>,
    onOpenTerminal: (sessionId: Int) -> Unit,
    /** Opens the file screen whose login has this id. */
    onOpenFiles: (targetId: Int) -> Unit,
) {
    val notificationsFirst = rememberNotificationsFirst()
    KeyLoginDialogs(actions.keyLogin)
    ui.filesLogin?.let { row ->
        SshLoginDialog(
            target = "${row.connection.device}:${row.connection.port}",
            initial = actions.sshLogin(row),
            hasRememberedPassword = { actions.hasRememberedPassword(row, it) },
            onConnect = { user, method: SshAuthMethod, password, remember ->
                ui.filesLogin = null
                // As Terminal needs it: the tunnel first; the file screen waits for it.
                if (row.tunnel == null && row.pending == null) notificationsFirst { actions.connect(row) }
                actions.openFiles(row, user, method, password, remember)?.let(onOpenFiles)
            },
            onDismiss = { ui.filesLogin = null },
        )
    }
    ui.login?.let { row ->
        SshLoginDialog(
            target = "${row.connection.device}:${row.connection.port}",
            initial = actions.sshLogin(row),
            hasRememberedPassword = { actions.hasRememberedPassword(row, it) },
            onConnect = { user, method: SshAuthMethod, password, remember ->
                ui.login = null
                // "Change login" from "⋮" may come with the tunnel down: it is brought up first.
                val open = { actions.openSshWhenUp(row, user, method, password, remember, onOpenTerminal) }
                if (row.tunnel == null && row.pending == null) notificationsFirst(open) else open()
            },
            onDismiss = { ui.login = null },
        )
    }
    ui.direct?.let { row ->
        val kept = row.tunnel?.direct == DirectMode.KEPT
        val device = row.connection.device
        AlertDialog(
            onDismissRequest = { ui.direct = null },
            title = { Text(stringResource(if (kept) R.string.state_kept_through_server else R.string.state_through_server)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (kept) R.string.direct_kept_text else R.string.direct_failed_text, device))
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            ui.direct = null
                            actions.retryDirect(row)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.retry_direct)) }
                    if (!kept) {
                        OutlinedButton(
                            onClick = {
                                ui.direct = null
                                actions.keepThroughServer(row)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.keep_through_server), color = AppTheme.colors.accent) }
                    }
                }
            },
            // The two answers are the buttons above, full width; tapping outside closes it.
            confirmButton = {},
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
            ports = rememberLocalPortContext(actions, row.profile),
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
        context.notice(context.getString(R.string.no_browser), NoticeKind.WARN)
    }
}

/** Hands the tunnel to an SSH app (Termius, JuiceSSH, …); a toast if none is installed. */
private fun openSshExternally(context: Context, user: String, localPort: Int) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("ssh://${Uri.encode(user)}@127.0.0.1:$localPort")))
    } catch (_: ActivityNotFoundException) {
        context.notice(context.getString(R.string.no_ssh_app), NoticeKind.WARN)
    }
}
