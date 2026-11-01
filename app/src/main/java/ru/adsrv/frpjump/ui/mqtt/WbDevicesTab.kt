package ru.adsrv.frpjump.ui.mqtt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.mqtt.MqttState
import ru.adsrv.frpjump.mqtt.oneLineText
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbControl
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbDevice
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbError
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.shownValue
import ru.adsrv.frpjump.ui.AppTheme

/** Characters of a value a control's row shows at most. */
private const val CONTROL_VALUE_CHARS = 60

/**
 * The WB devices tab (the Wiren Board plugin): the devices list, or a
 * device opened inside the tab with its controls. A control's title opens
 * its topic's card.
 */
@Composable
internal fun WbDevicesTab(state: MqttUiState, language: String, padding: PaddingValues, onOpen: (String) -> Unit, onBack: () -> Unit, onCard: (WbControl) -> Unit) {
    val device = state.wbDeviceId
    // Held above the device, so Back from one returns to the list where it was.
    val devices = rememberLazyListState()
    if (device == null) {
        WbDeviceList(state, devices, padding, onOpen)
    } else {
        WbDeviceControls(state, device, language, padding, onBack, onCard)
    }
}

@Composable
private fun WbDeviceList(state: MqttUiState, list: LazyListState, padding: PaddingValues, onOpen: (String) -> Unit) {
    val colors = AppTheme.colors
    if (state.wbDevices.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.wbReading || state.state == MqttState.Connecting) CircularProgressIndicator(color = colors.accent)
            Text(
                stringResource(if (state.wbReading) R.string.wb_reading_devices else R.string.wb_no_devices),
                color = colors.muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp),
    ) {
        items(state.wbDevices, key = { it.id }) { device ->
            DeviceRow(device, onClick = { onOpen(device.id) })
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

/** A device: its title, the id under it in monospace, a red "!" when it or a control reports an error. */
@Composable
private fun DeviceRow(device: WbDevice, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.files_open), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(oneLineText(device.title), fontSize = 15.sp, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(device.id, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (device.hasErrors) ErrorMark(stringResource(R.string.wb_has_errors))
    }
}

/** The red "!" of an error, read out as [spoken]. */
@Composable
internal fun ErrorMark(spoken: String) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .size(22.dp)
            .background(colors.error, CircleShape)
            .clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Text("!", color = colors.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** A device opened in the tab: a header with Back and its title, then its controls in meta order. */
@Composable
private fun WbDeviceControls(state: MqttUiState, id: String, language: String, padding: PaddingValues, onBack: () -> Unit, onCard: (WbControl) -> Unit) {
    val colors = AppTheme.colors
    val view = state.wbDevice
    val list = rememberLazyListState()
    LaunchedEffect(id) { list.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.wb_devices_back), tint = colors.ink)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    oneLineText(view?.title ?: id),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                if (view?.deviceError == true) {
                    Text(stringResource(R.string.wb_device_not_answering), fontSize = 13.sp, color = colors.error)
                }
            }
            if (view?.deviceError == true) ErrorMark(stringResource(R.string.wb_device_not_answering))
        }
        HorizontalDivider(color = colors.divider)
        if (view == null || view.controls.isEmpty()) {
            Text(
                stringResource(R.string.mqtt_no_messages),
                color = colors.muted,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            items(view.controls, key = { it.id }) { control ->
                ControlRow(control, language, onTitle = { onCard(control) })
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

/**
 * A control: its title (a tap opens the topic's card), its error in plain
 * words under it, and its value with units on the right.
 */
@Composable
private fun ControlRow(control: WbControl, language: String, onTitle: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
        }
        ControlValue(control, language, Modifier.widthIn(max = 200.dp))
    }
}

/** The value with its units (or its enum title), "No value" before one is seen. */
@Composable
internal fun ControlValue(control: WbControl, language: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val raw = control.value
    if (raw == null || raw.isEmpty()) {
        // An empty text reads as the tree's empty value, not as nothing at all.
        Text(stringResource(if (raw == null) R.string.wb_no_value else R.string.mqtt_empty_value), fontSize = 14.sp, color = colors.muted, modifier = modifier)
        return
    }
    val shown = shownValue(control.meta, raw, language)
    val text = oneLineText(shown.text, CONTROL_VALUE_CHARS) + (shown.units?.let { " $it" } ?: "")
    Text(text, fontSize = 15.sp, color = colors.ink, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** The errors in plain words, one per letter, the first capitalised: "Read error, polled late" reads as one line. */
@Composable
internal fun errorWords(errors: List<WbError>): String = errors.map { e ->
    when (e) {
        WbError.Read -> stringResource(R.string.wb_error_read)
        WbError.Write -> stringResource(R.string.wb_error_write)
        WbError.PolledLate -> stringResource(R.string.wb_error_polled_late)
        is WbError.Other -> e.letter
    }
}.joinToString(", ").replaceFirstChar { it.uppercaseChar() }
