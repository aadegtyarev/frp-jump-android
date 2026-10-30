package ru.adsrv.frpjump.ui.connections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionDraft
import ru.adsrv.frpjump.connections.PORT_PRESETS
import ru.adsrv.frpjump.connections.PortProblem
import ru.adsrv.frpjump.connections.connectionOf
import ru.adsrv.frpjump.connections.draftOf
import ru.adsrv.frpjump.connections.draftProblems
import ru.adsrv.frpjump.connections.parsePort
import ru.adsrv.frpjump.connections.resolvedName
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ui.AppTheme
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
    var connectNow by rememberSaveable { mutableStateOf(true) }
    var tried by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val draft = ConnectionDraft(kind, portText, name, https, user)
    val fallbackKinds = mapOf(
        PortKind.WEB to stringResource(R.string.kind_web),
        PortKind.SSH to stringResource(R.string.kind_ssh),
        PortKind.TCP to stringResource(R.string.kind_tcp),
    )
    val fallback = { k: PortKind, p: Int -> context.getString(R.string.connection_default_name, fallbackKinds.getValue(k), p) }
    val savedName = resolvedName(draft, fallback)
    val problems = draftProblems(draft, savedName, device, existing, editing?.id)

    val save = {
        tried = true
        if (problems.ok && !busy) {
            busy = true
            error = null
            val connection = connectionOf(draft, editing?.id ?: UUID.randomUUID().toString(), device, savedName)
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
            KindSwitch(kind) { picked ->
                if (picked != kind) {
                    // A preset of the old kind (or nothing) gives way to the new kind's first preset.
                    val wasPreset = portText.isBlank() || PORT_PRESETS[kind].orEmpty().any { it.port.toString() == portText }
                    if (!portLocked && wasPreset) {
                        portText = PORT_PRESETS.getValue(picked).first().port.toString()
                        https = false
                    }
                    kind = picked
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val portProblem = problems.port?.takeIf { tried || it == PortProblem.OUT_OF_RANGE }
                Field(
                    label = stringResource(R.string.port_on_device),
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    enabled = !portLocked,
                    mono = true,
                    keyboard = KeyboardType.Number,
                    problem = when (portProblem) {
                        PortProblem.MISSING -> stringResource(R.string.port_missing)
                        PortProblem.OUT_OF_RANGE -> stringResource(R.string.port_out_of_range)
                        null -> null
                    },
                    note = stringResource(R.string.port_locked).takeIf { portLocked },
                )
                if (!portLocked) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PORT_PRESETS.getValue(kind).forEach { preset ->
                            val label = preset.name?.let { stringResource(R.string.preset_named, it, preset.port) }
                                ?: if (kind == PortKind.WEB && preset.port == 443) stringResource(R.string.preset_named, "HTTPS", 443) else preset.port.toString()
                            PresetChip(label, selected = parsePort(portText) == preset.port) {
                                portText = preset.port.toString()
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
                placeholder = stringResource(R.string.connection_name_hint, savedName.ifEmpty { "homeui" }),
                problem = stringResource(R.string.connection_name_taken).takeIf { problems.nameTaken },
            )
            if (kind == PortKind.WEB) {
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
                    enabled = !busy && (!tried || problems.ok),
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

/** Web / SSH / TCP as the mockup's segmented control. */
@Composable
private fun KindSwitch(kind: PortKind, onPick: (PortKind) -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.tile, RoundedCornerShape(14.dp))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PortKind.entries.forEach { k ->
            val on = k == kind
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(10.dp)) else Modifier)
                    .background(if (on) colors.card else colors.tile, RoundedCornerShape(10.dp))
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(k) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(kindLabel(k), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) colors.accent else colors.muted)
            }
        }
    }
}

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

/** A labelled field as the mockups draw them: the label above, the problem below. */
@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    mono: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    problem: String? = null,
    note: String? = null,
) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, color = colors.muted)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            isError = problem != null,
            placeholder = placeholder?.let { { Text(it, color = colors.muted) } },
            supportingText = (problem ?: note)?.let { { Text(it) } },
            textStyle = if (mono) TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp) else TextStyle(fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = colors.outline,
                focusedBorderColor = colors.accent,
                unfocusedContainerColor = colors.card,
                focusedContainerColor = colors.card,
                disabledContainerColor = colors.tile,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
