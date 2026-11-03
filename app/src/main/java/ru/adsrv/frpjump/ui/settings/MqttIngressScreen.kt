package ru.adsrv.frpjump.ui.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.settings.MqttIngressLimits
import ru.adsrv.frpjump.settings.MqttOverflow
import ru.adsrv.frpjump.settings.validMqttIngressLimits
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.FormScreen

/** Keeps the form until storage actually commits; an old save cannot dismiss a replacement form. */
@Composable
internal fun MqttIngressScreen(
    initial: MqttIngressLimits,
    onSave: (MqttIngressLimits, (String?) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var payload by rememberSaveable { mutableStateOf(initial.payloadBytes.toString()) }
    var queue by rememberSaveable { mutableStateOf(initial.queueBytes.toString()) }
    var messages by rememberSaveable { mutableStateOf(initial.queueMessages.toString()) }
    var mode by rememberSaveable { mutableStateOf(initial.overflow) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf(true) }
    var request by remember { mutableStateOf<Any?>(null) }
    DisposableEffect(Unit) { onDispose { active = false; request = null } }
    val p = payload.toIntOrNull()
    val q = queue.toIntOrNull()
    val n = messages.toIntOrNull()
    val limits = if (p == null || q == null || n == null) null else MqttIngressLimits(p, q, n, mode)
    val valid = limits != null && validMqttIngressLimits(limits)
    FormScreen(
        title = stringResource(R.string.mqtt_ingress_title),
        action = stringResource(R.string.save),
        busy = busy,
        actionEnabled = valid,
        error = failure,
        onBack = { if (!busy) onBack() },
        onAction = {
            if (valid && !busy) {
                val owner = Any()
                request = owner
                busy = true
                failure = null
                val complete: (String?) -> Unit = { error ->
                    if (active && request === owner) {
                        request = null
                        busy = false
                        failure = error
                        if (error == null) onBack()
                    }
                }
                try { onSave(limits!!, complete) }
                catch (error: Exception) { complete(error.javaClass.simpleName) }
            }
        },
    ) {
        Text(stringResource(R.string.mqtt_ingress_apply_hint), color = AppTheme.colors.muted, fontSize = 13.sp)
        Text(stringResource(R.string.mqtt_ingress_accounting_hint), color = AppTheme.colors.muted, fontSize = 13.sp)
        fun digits(text: String) = text.filter { it in '0'..'9' }.take(10)
        Field(stringResource(R.string.mqtt_ingress_payload), payload, { payload = digits(it); failure = null },
            Modifier.testTag("mqtt_limit_payload"), enabled = !busy, keyboard = KeyboardType.Number)
        Field(stringResource(R.string.mqtt_ingress_queue), queue, { queue = digits(it); failure = null },
            Modifier.testTag("mqtt_limit_queue"), enabled = !busy, keyboard = KeyboardType.Number)
        Field(stringResource(R.string.mqtt_ingress_messages), messages, { messages = digits(it); failure = null },
            Modifier.testTag("mqtt_limit_messages"), enabled = !busy, keyboard = KeyboardType.Number)
        Text(stringResource(R.string.mqtt_ingress_ranges), color = if (valid) AppTheme.colors.muted else AppTheme.colors.error, fontSize = 13.sp)
        Text(stringResource(R.string.mqtt_ingress_overflow))
        MqttOverflow.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().testTag("mqtt_overflow_${option.name}")
                .selectable(selected = mode == option, enabled = !busy, role = Role.RadioButton,
                    onClick = { mode = option; failure = null }), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = mode == option, onClick = null, enabled = !busy)
                Text(stringResource(if (option == MqttOverflow.DROP_INCOMING) R.string.mqtt_ingress_drop else R.string.mqtt_ingress_stop))
            }
        }
    }
}
