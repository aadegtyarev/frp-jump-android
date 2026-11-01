package ru.adsrv.frpjump.ui.devices

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.devices.DeviceIcon
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.devices.DisplayChange
import ru.adsrv.frpjump.devices.DisplayFailure
import ru.adsrv.frpjump.devices.IconPick
import ru.adsrv.frpjump.devices.LABEL_MAX
import ru.adsrv.frpjump.devices.LabelRefusal
import ru.adsrv.frpjump.devices.displayChange
import ru.adsrv.frpjump.devices.labelLength
import ru.adsrv.frpjump.devices.limitLabel
import ru.adsrv.frpjump.ui.AppSheet
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.MainButton
import ru.adsrv.frpjump.ui.caption

/**
 * «Подпись и значок» of [device]: the label field with its counter, the
 * icon tiles and «Сохранить», which sends only what changed ([onSave]
 * calls back with null on success, then the sheet closes). [focusLabel]:
 * opened from «Подпись…», the keyboard comes up in the field. Closing it
 * any other way drops the draft.
 */
@Composable
fun DeviceDisplaySheet(
    device: DeviceRow,
    focusLabel: Boolean,
    onSave: (DisplayChange, done: (DisplayFailure?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val loaded = device.label.orEmpty()
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(loaded, TextRange(loaded.length))) }
    val initialPick = remember(device.iconChoice) { IconPick.of(device.iconChoice) }
    var pick by remember { mutableStateOf(initialPick) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<DisplayFailure?>(null) }
    val change = displayChange(field.text, pick, device.label, device.iconChoice)

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (focusLabel) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    AppSheet(title = stringResource(R.string.device_display_title), subtitle = device.name, onDismiss = onDismiss) {
        val length = labelLength(field.text)
        val spokenCount = stringResource(R.string.device_label_counter_spoken, length, LABEL_MAX)
        Field(
            label = stringResource(R.string.device_label_field),
            value = field,
            onValueChange = { new ->
                val edit = limitLabel(field.text, new.text, new.selection.end)
                // As typed (the keyboard's composition kept), unless it had to change.
                field = if (edit.text == new.text) new else TextFieldValue(edit.text, TextRange(edit.cursor))
                if (failure is DisplayFailure.Label || failure is DisplayFailure.LabelText) failure = null
            },
            placeholder = stringResource(R.string.device_label_hint),
            problem = when (val f = failure) {
                is DisplayFailure.Label -> stringResource(f.refusal.text)
                is DisplayFailure.LabelText -> f.text
                else -> null
            },
            focusRequester = focus,
            counter = {
                Text(
                    stringResource(R.string.device_label_counter, length, LABEL_MAX),
                    modifier = Modifier.clearAndSetSemantics { contentDescription = spokenCount },
                )
            },
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.device_icon_section), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
            val tiles = buildList {
                add(IconPick.Auto)
                DeviceIcon.entries.forEach { add(IconPick.Known(it)) }
                // Only when the server has a choice this app does not know: shown as it is, never offered otherwise.
                if (initialPick is IconPick.Other) add(initialPick)
            }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { tile ->
                            IconTile(
                                tile,
                                kind = device.kind,
                                selected = tile == pick,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { pick = tile }
                        }
                        // The last row keeps the tiles' width.
                        repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }

        val retry = failure != null
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MainButton(
                text = stringResource(if (retry) R.string.retry else R.string.save),
                busy = busy,
                enabled = !change.empty,
                onClick = {
                    busy = true
                    failure = null
                    onSave(change) { result ->
                        busy = false
                        failure = result
                        if (result == null) onDismiss()
                    }
                },
            )
            val below = when (val f = failure) {
                DisplayFailure.Disabled -> stringResource(R.string.display_phone_disabled)
                DisplayFailure.Unreachable -> stringResource(R.string.display_unreachable)
                is DisplayFailure.Other -> f.text
                else -> null
            }
            below?.let {
                Text(
                    it,
                    color = colors.error,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** One tile: the glyph over its caption, the accent border when [selected]; TalkBack reads the caption and "selected". */
@Composable
private fun IconTile(tile: IconPick, kind: String?, selected: Boolean, enabled: Boolean, modifier: Modifier, onPick: () -> Unit) {
    val colors = AppTheme.colors
    val detected = kind?.let(DeviceIcon::fromWire)
    val (glyph, caption) = when (tile) {
        IconPick.Auto -> detected to (
            detected?.let { stringResource(R.string.device_icon_auto_detected, stringResource(it.caption)) }
                ?: stringResource(R.string.device_icon_auto)
            )
        is IconPick.Known -> tile.icon to stringResource(tile.icon.caption)
        is IconPick.Other -> null to stringResource(R.string.device_icon_other)
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .heightIn(min = 88.dp)
            .background(if (selected) colors.accentContainer else colors.card, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.outline, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DeviceTile(glyph, dot = null, size = 36.dp, ring = colors.card)
        Text(
            caption,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onAccentContainer else colors.ink,
        )
    }
}

/** The app's own words for a refused label. */
@get:StringRes
private val LabelRefusal.text: Int
    get() = when (this) {
        LabelRefusal.TOO_LONG_RAW -> R.string.label_refused_too_long_raw
        LabelRefusal.TOO_LONG -> R.string.label_refused_too_long
        LabelRefusal.CONTROL -> R.string.label_refused_control
        LabelRefusal.INVISIBLE -> R.string.label_refused_invisible
        LabelRefusal.NOT_VISIBLE -> R.string.label_refused_not_visible
    }
