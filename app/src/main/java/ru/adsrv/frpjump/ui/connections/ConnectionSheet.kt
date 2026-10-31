package ru.adsrv.frpjump.ui.connections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionDraft
import ru.adsrv.frpjump.connections.LocalPortContext
import ru.adsrv.frpjump.connections.LocalPortProblem
import ru.adsrv.frpjump.connections.localPortProblemWords
import ru.adsrv.frpjump.connections.PORT_PRESETS
import ru.adsrv.frpjump.connections.asksForHttps
import ru.adsrv.frpjump.connections.schemeIsHttps
import ru.adsrv.frpjump.connections.PortProblem
import ru.adsrv.frpjump.connections.connectionOf
import ru.adsrv.frpjump.connections.draftOf
import ru.adsrv.frpjump.connections.draftProblems
import ru.adsrv.frpjump.connections.parsePort
import ru.adsrv.frpjump.connections.draftLabel
import ru.adsrv.frpjump.connections.isPresetName
import ru.adsrv.frpjump.connections.nameAfterPreset
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.NoticeLayer
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.Segmented
import java.util.UUID

/**
 * "New connection" to [device] (or "Change connection" for [editing]) as
 * the mockup's bottom sheet: kind, port with presets, name, then HTTPS for
 * web or the user for SSH, and "Connect now" for a new one. [portLocked]
 * keeps the port of a connection whose tunnel is up. [onSave] gets the
 * connection, "connect now" and a callback taking null or the failure.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionSheet(
    device: String,
    editing: Connection?,
    portLocked: Boolean,
    existing: List<Connection>,
    /** Every profile's pins and the tunnels up now, for the local port's check. */
    ports: LocalPortContext,
    onSave: (connection: Connection, connectNow: Boolean, done: (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val start = remember(editing) { editing?.let(::draftOf) ?: ConnectionDraft(PortKind.WEB, "80", "") }
    var kind by rememberSaveable { mutableStateOf(start.kind) }
    var portText by rememberSaveable { mutableStateOf(start.portText) }
    var name by rememberSaveable { mutableStateOf(start.name) }
    var https by rememberSaveable { mutableStateOf(start.https) }
    var user by rememberSaveable { mutableStateOf(start.user) }
    var localPortText by rememberSaveable { mutableStateOf(start.localPortText) }
    var connectNow by rememberSaveable { mutableStateOf(true) }
    var tried by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val draft = ConnectionDraft(kind, portText, name, https, user, localPortText)
    val kindWord = { k: PortKind -> kindWord(context, k) }
    val shownName = draftLabel(draft, kindWord)
    val problems = draftProblems(draft, device, existing, editing?.id, kindWord)
    val localProblem = ports.problem(localPortText, device, parsePort(portText), editing?.id)
    val ok = problems.ok && localProblem == null

    val save = {
        tried = true
        if (ok && !busy) {
            busy = true
            error = null
            val connection = connectionOf(draft, editing?.id ?: UUID.randomUUID().toString(), device)
            onSave(connection, editing == null && connectNow) { failure ->
                busy = false
                if (failure == null) onDismiss() else error = context.getString(R.string.save_failed, failure)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.card,
    ) {
        // A message posted while the sheet is open shows on it, not under its scrim.
        NoticeLayer {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(if (editing == null) R.string.new_connection else R.string.change_connection),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.connection_to, device), fontSize = 13.sp, color = colors.muted)
                }
                KindSwitch(kind, enabled = !portLocked) { picked ->
                    if (picked != kind) {
                        // A preset of the old kind (or nothing) gives way to the new kind's first preset.
                        val wasPreset = portText.isBlank() || PORT_PRESETS[kind].orEmpty().any { it.port.toString() == portText }
                        if (!portLocked && wasPreset) {
                            portText = PORT_PRESETS.getValue(picked).first().port.toString()
                            https = false
                        }
                        // A preset's name belongs to the old kind's port; the default name says the new one.
                        if (isPresetName(name)) name = ""
                        kind = picked
                    }
                }
                problems.protocolClash?.let {
                    Text(
                        stringResource(if (it == "ssh") R.string.protocol_clash_ssh else R.string.protocol_clash_other),
                        color = colors.error,
                        fontSize = 13.sp,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val portProblem = problems.port?.takeIf { tried || it == PortProblem.OUT_OF_RANGE }
                    Field(
                        label = stringResource(R.string.port_on_device),
                        value = portText,
                        onValueChange = { typed ->
                            portText = typed.filter(Char::isDigit).take(5)
                            // 80 and 443 set the scheme, so a port typed after them starts from it.
                            parsePort(portText)?.takeIf { !asksForHttps(it) }?.let { https = schemeIsHttps(it, https) }
                        },
                        enabled = !portLocked,
                        mono = true,
                        keyboard = KeyboardType.Number,
                        problem = when (portProblem) {
                            PortProblem.MISSING -> stringResource(R.string.port_missing)
                            PortProblem.OUT_OF_RANGE -> stringResource(R.string.port_out_of_range)
                            null -> null
                        },
                        note = stringResource(R.string.kind_locked).takeIf { portLocked },
                    )
                    if (!portLocked) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PORT_PRESETS.getValue(kind).forEach { preset ->
                                val label = preset.name?.let { stringResource(R.string.preset_named, it, preset.port) }
                                    ?: webSchemeLabel(kind, preset.port)?.let { stringResource(R.string.preset_named, it, preset.port) }
                                    ?: preset.port.toString()
                                PresetChip(label, selected = parsePort(portText) == preset.port) {
                                    portText = preset.port.toString()
                                    name = nameAfterPreset(name, preset)
                                    if (kind == PortKind.WEB) https = preset.port == 443
                                }
                            }
                        }
                    }
                }
                Field(
                    label = stringResource(R.string.connection_name),
                    value = name,
                    onValueChange = { name = it.take(40) },
                    placeholder = stringResource(R.string.connection_name_hint, shownName.ifEmpty { kindWord(kind) }),
                    problem = stringResource(R.string.connection_name_taken).takeIf { problems.nameTaken },
                )
                // 80 and 443 say their scheme themselves; other ports may be either.
                if (kind == PortKind.WEB && asksForHttps(parsePort(portText))) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = https, role = Role.Switch, onValueChange = { https = it }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.https), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.https_hint), fontSize = 13.sp, color = colors.muted)
                        }
                        Switch(checked = https, onCheckedChange = null)
                    }
                }
                // Blank is "Auto"; a number pins 127.0.0.1:N to this connection for good.
                Field(
                    label = stringResource(R.string.local_port),
                    value = localPortText,
                    onValueChange = { typed -> localPortText = typed.filter(Char::isDigit).take(5) },
                    enabled = !portLocked,
                    mono = true,
                    keyboard = KeyboardType.Number,
                    placeholder = stringResource(R.string.local_port_auto),
                    problem = localProblem?.let { localPortProblemText(it) },
                    note = stringResource(if (portLocked) R.string.local_port_locked else R.string.local_port_note),
                )
                if (kind == PortKind.SSH) {
                    Field(
                        label = stringResource(R.string.ssh_user),
                        value = user,
                        onValueChange = { user = it.trim().take(64) },
                        keyboard = KeyboardType.Ascii,
                        problem = stringResource(R.string.ssh_user_missing).takeIf { problems.userMissing },
                    )
                }
                if (editing == null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = connectNow, role = Role.Checkbox, onValueChange = { connectNow = it }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = connectNow, onCheckedChange = null)
                        Text(stringResource(R.string.connect_now), fontSize = 15.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, color = colors.error, fontSize = 14.sp) }
                    Button(
                        onClick = save,
                        enabled = !busy && (!tried || ok),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            disabledContainerColor = if (busy) colors.accent else colors.tile,
                            disabledContentColor = if (busy) AppTheme.colors.card else colors.muted,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = colors.card, strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.save), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/** Why a typed local port cannot be pinned, in words. */
@Composable
private fun localPortProblemText(problem: LocalPortProblem): String =
    localPortProblemWords(problem).let { (res, args) -> stringResource(res, *args.toTypedArray()) }

/** Web / SSH / TCP as the mockup's segmented control. */
@Composable
private fun KindSwitch(kind: PortKind, enabled: Boolean, onPick: (PortKind) -> Unit) =
    Segmented(PortKind.entries, kind, label = { kindLabel(it) }, enabled = enabled, onPick = onPick)

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) colors.accentContainer else colors.tile,
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.outline),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (selected) colors.onAccentContainer else colors.ink,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** The web presets on their standard ports say their scheme: "HTTP · 80", "HTTPS · 443". */
private fun webSchemeLabel(kind: PortKind, port: Int): String? = when {
    kind != PortKind.WEB -> null
    port == 80 -> "HTTP"
    port == 443 -> "HTTPS"
    else -> null
}
