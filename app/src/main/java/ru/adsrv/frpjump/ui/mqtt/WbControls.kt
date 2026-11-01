package ru.adsrv.frpjump.ui.mqtt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.mqtt.oneLineText
import ru.adsrv.frpjump.mqtt.refusalText
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.NumberInput
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.NumberProblem
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.PUSH_PAYLOAD
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbControl
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbWidget
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WriteFailure
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WriteView
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.enumEntry
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.numberInput
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.numberPrefill
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.rangeBounds
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.rangePayload
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.rangePosition
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.rgbOf
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.rgbPayload
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.shownValue
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.switchOn
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.switchPayload
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.unitsOf
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.widgetOf
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.files.SecondaryButton
import kotlin.math.roundToInt

/** The dialogs a control opens: an input, the enum's titles, the colour picker. */
private enum class ControlDialog { INPUT, ENUM, RGB }

/**
 * A control's row (the Wiren Board plugin): the title (a tap opens the
 * topic's card), its error and its write's answer under it in plain words,
 * and on the right the control by type. [answer] is where the last write
 * stands; [sent] the last value sent to a `wo-switch`. [off] says why the
 * controls are off (a closed or reconnecting session), null while open.
 */
@Composable
internal fun ControlRow(
    control: WbControl,
    language: String,
    answer: WriteView?,
    sent: String?,
    off: String?,
    onTitle: () -> Unit,
    onWrite: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val widget = widgetOf(control.meta)
    val sending = answer as? WriteView.Sending
    val canWrite = off == null && sending == null
    var dialog by rememberSaveable { mutableStateOf<ControlDialog?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.mqtt_open_card), onClick = onTitle),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(oneLineText(control.title), fontSize = 15.sp, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (control.errors.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).background(colors.error, CircleShape))
                        Text(errorWords(control.errors), fontSize = 13.sp, color = colors.error)
                    }
                }
                AnswerLine(answer)
            }
            Box(Modifier.widthIn(max = 220.dp), contentAlignment = Alignment.CenterEnd) {
                when {
                    sending != null && widget != WbWidget.SWITCH && widget != WbWidget.WO_SWITCH && widget != WbWidget.RANGE ->
                        SendingValue(control, sending.asked, language)
                    else -> ControlWidget(control, widget, language, sending, sent, canWrite, off, onWrite, onDialog = { dialog = it })
                }
            }
        }
        if (widget == WbWidget.RANGE) RangeSlider(control, sending, canWrite, off, onWrite)
    }
    when (dialog) {
        ControlDialog.INPUT -> InputDialog(control, numeric = widget == WbWidget.NUMBER, onSend = { dialog = null; onWrite(it) }, onDismiss = { dialog = null })
        ControlDialog.ENUM -> EnumDialog(control, language, onPick = { dialog = null; onWrite(it) }, onDismiss = { dialog = null })
        ControlDialog.RGB -> RgbDialog(control, onSend = { dialog = null; onWrite(it) }, onDismiss = { dialog = null })
        null -> Unit
    }
    // The session closed or dropped while a dialog was open: nothing can be sent.
    if (off != null && dialog != null) LaunchedEffect(Unit) { dialog = null }
}

/** The answer of the last write in the error colours (refused, no answer), or a short one muted; read out as it comes. */
@Composable
private fun AnswerLine(answer: WriteView?) {
    val colors = AppTheme.colors
    val (text, error) = when (answer) {
        is WriteView.Failed -> failureText(answer.failure) to true
        is WriteView.Answered -> answerText(answer.answer) to false
        else -> return
    }
    Text(
        text,
        fontSize = 13.sp,
        color = if (error) colors.error else colors.muted,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun failureText(failure: WriteFailure): String = when (failure) {
    is WriteFailure.Refused -> stringResource(R.string.mqtt_publish_refused, refusalText(failure.refused))
    WriteFailure.NoAnswerFromDevice -> stringResource(R.string.wb_no_answer_device)
    WriteFailure.NoAnswerFromBroker -> stringResource(R.string.wb_no_answer_broker)
    is WriteFailure.NotSent -> answerText(failure.failed)
}

/** The asked value muted with a spinner: the write is on its way; not tappable. */
@Composable
private fun SendingValue(control: WbControl, asked: String, language: String) {
    val colors = AppTheme.colors
    val shown = shownValue(control.meta, asked, language)
    val spoken = stringResource(R.string.wb_sending)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { stateDescription = spoken },
    ) {
        CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Text(oneLineText(shown.text, 40) + (shown.units?.let { " $it" } ?: ""), fontSize = 15.sp, color = colors.muted, textAlign = TextAlign.End, maxLines = 2)
    }
}

@Composable
private fun ControlWidget(
    control: WbControl,
    widget: WbWidget,
    language: String,
    sending: WriteView.Sending?,
    sent: String?,
    canWrite: Boolean,
    off: String?,
    onWrite: (String) -> Unit,
    onDialog: (ControlDialog) -> Unit,
) {
    val colors = AppTheme.colors
    val offState = Modifier.semantics { off?.let { stateDescription = it } }
    when (widget) {
        WbWidget.SWITCH, WbWidget.WO_SWITCH -> {
            val shown = if (widget == WbWidget.WO_SWITCH) sent else control.value
            val on = switchOn(sending?.asked ?: shown)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    sending != null -> CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    // A missing or other value is off, and also shown as text.
                    widget == WbWidget.SWITCH && control.value?.trim() != "1" && control.value?.trim() != "0" ->
                        Text(control.value?.let { oneLineText(it, 20) } ?: stringResource(R.string.wb_no_value), fontSize = 13.sp, color = colors.muted)
                }
                val spoken = control.title
                val sendingWord = stringResource(R.string.wb_sending)
                Switch(
                    checked = on,
                    enabled = canWrite,
                    onCheckedChange = { onWrite(switchPayload(it)) },
                    modifier = offState.semantics {
                        contentDescription = spoken
                        if (sending != null) stateDescription = sendingWord
                    },
                )
            }
        }
        WbWidget.PUSHBUTTON -> SecondaryButton(oneLineText(control.title, 24), null, enabled = canWrite, offState) { onWrite(PUSH_PAYLOAD) }
        WbWidget.ALARM -> {
            val raised = control.value?.trim() == "1"
            val spoken = stringResource(if (raised) R.string.wb_alarm_on else R.string.wb_alarm_off)
            Box(
                Modifier
                    .size(20.dp)
                    .background(if (raised) colors.error else Color.Transparent, CircleShape)
                    .border(2.dp, if (raised) colors.error else colors.outline, CircleShape)
                    .semantics { contentDescription = spoken },
            )
        }
        WbWidget.RGB -> {
            val rgb = rgbOf(control.value)
            Row(
                Modifier
                    .clickable(enabled = canWrite, role = Role.Button, onClickLabel = stringResource(R.string.wb_pick_colour)) { onDialog(ControlDialog.RGB) }
                    .then(offState)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(control.value?.let { oneLineText(it, 20) } ?: stringResource(R.string.wb_no_value), fontSize = 13.sp, color = colors.muted)
                Box(
                    Modifier
                        .size(32.dp)
                        .background(rgb?.let { Color(it.first, it.second, it.third) } ?: colors.tile, RoundedCornerShape(8.dp))
                        .border(1.dp, colors.outline, RoundedCornerShape(8.dp)),
                )
            }
        }
        WbWidget.ENUM, WbWidget.NUMBER, WbWidget.TEXT -> Row(
            Modifier
                .clickable(enabled = canWrite, role = Role.Button, onClickLabel = stringResource(R.string.wb_change_value)) {
                    onDialog(if (widget == WbWidget.ENUM) ControlDialog.ENUM else ControlDialog.INPUT)
                }
                .then(offState)
                .heightIn(min = 40.dp)
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlValue(control, language, Modifier.weight(1f, fill = false), accent = canWrite)
            if (widget == WbWidget.ENUM) Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = if (canWrite) colors.accent else colors.muted)
        }
        // The slider under the title shows a range's value.
        WbWidget.RANGE -> Unit
        WbWidget.READ_ONLY -> ControlValue(control, language)
    }
}

/** A `range`: an integer slider from min to max (rounded to whole steps), its value and units next to it; sent once on release. */
@Composable
private fun RangeSlider(control: WbControl, sending: WriteView.Sending?, canWrite: Boolean, off: String?, onWrite: (String) -> Unit) {
    val colors = AppTheme.colors
    val bounds = rangeBounds(control.meta)
    val status = rangePosition(sending?.asked ?: control.value, bounds)
    // While dragging the slider follows the finger; otherwise the status value (or the asked one) places it.
    var dragging by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(status.toFloat()) }
    if (!dragging) position = status.toFloat()
    val span = bounds.last - bounds.first
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Slider(
            value = position,
            onValueChange = {
                dragging = true
                position = it
            },
            onValueChangeFinished = {
                dragging = false
                val picked = position.roundToInt().coerceIn(bounds)
                if (picked != rangePosition(control.value, bounds) || control.value == null) onWrite(rangePayload(picked))
            },
            valueRange = bounds.first.toFloat()..bounds.last.toFloat(),
            enabled = canWrite && span > 0,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = control.title
                off?.let { stateDescription = it }
            },
        )
        Row(Modifier.widthIn(min = 64.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (sending != null) CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp).padding(end = 4.dp))
            val units = unitsOf(control.meta)?.let { " $it" }.orEmpty()
            Text(position.roundToInt().toString() + units, fontSize = 15.sp, color = if (sending != null) colors.muted else colors.ink)
        }
    }
}

/**
 * A one-field input for a numeric or text control: prefilled with the
 * value, selected whole; a number is checked against min and max and
 * rounded to the precision before Send.
 */
@Composable
private fun InputDialog(control: WbControl, numeric: Boolean, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    val initial = if (numeric) numberPrefill(control.value, control.meta) else control.value.orEmpty()
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var problem by remember { mutableStateOf<NumberProblem?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val send = {
        if (!numeric) {
            onSend(field.text)
        } else {
            when (val input = numberInput(field.text, control.meta)) {
                is NumberInput.Ok -> onSend(input.payload)
                is NumberInput.Bad -> problem = input.problem
            }
        }
    }
    val bounds = listOfNotNull(
        control.meta.min?.let { stringResource(R.string.wb_number_min, it.toPlainString()) },
        control.meta.max?.let { stringResource(R.string.wb_number_max, it.toPlainString()) },
    ).joinToString(" · ").takeIf { numeric && it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(oneLineText(control.title)) },
        text = {
            Field(
                label = stringResource(R.string.wb_value),
                value = field,
                onValueChange = {
                    if (it.text != field.text) problem = null
                    field = it
                },
                keyboard = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                focusRequester = focus,
                problem = problem?.let { numberProblemText(it) },
                note = bounds,
                onDone = send,
            )
        },
        confirmButton = { TextButton(onClick = send) { Text(stringResource(R.string.mqtt_publish_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun numberProblemText(problem: NumberProblem): String = when (problem) {
    NumberProblem.NotANumber -> stringResource(R.string.wb_number_not_number)
    is NumberProblem.BelowMin -> stringResource(R.string.wb_number_min, problem.min.toPlainString())
    is NumberProblem.AboveMax -> stringResource(R.string.wb_number_max, problem.max.toPlainString())
}

/** The enum's titles, the current one marked; a pick sends its key as written in the meta. */
@Composable
private fun EnumDialog(control: WbControl, language: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val current = control.value?.let { enumEntry(control.meta, it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(oneLineText(control.title)) },
        text = {
            LazyColumn {
                items(control.meta.enum, key = { it.key }) { entry ->
                    val picked = entry == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = picked, role = Role.RadioButton) { onPick(entry.key) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picked, onClick = null)
                        Text(entry.title.pick(language) ?: entry.key, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Three sliders (R, G, B) over a swatch of the colour; Send sends `R;G;B`. */
@Composable
private fun RgbDialog(control: WbControl, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    val start = rgbOf(control.value) ?: Triple(0, 0, 0)
    var r by rememberSaveable { mutableIntStateOf(start.first) }
    var g by rememberSaveable { mutableIntStateOf(start.second) }
    var b by rememberSaveable { mutableIntStateOf(start.third) }
    val colors = AppTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(oneLineText(control.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(Color(r, g, b), RoundedCornerShape(12.dp))
                        .border(1.dp, colors.outline, RoundedCornerShape(12.dp)),
                )
                Text(rgbPayload(r, g, b), fontSize = 13.sp, color = colors.muted)
                ChannelSlider("R", r) { r = it }
                ChannelSlider("G", g) { g = it }
                ChannelSlider("B", b) { b = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSend(rgbPayload(r, g, b)) }) { Text(stringResource(R.string.mqtt_publish_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChannelSlider(name: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, fontSize = 13.sp, modifier = Modifier.width(16.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f).semantics { contentDescription = name },
        )
        Text(value.toString(), fontSize = 13.sp, modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
    }
}
