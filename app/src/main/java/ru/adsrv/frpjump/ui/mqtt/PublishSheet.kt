package ru.adsrv.frpjump.ui.mqtt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.mqtt.MqttState
import ru.adsrv.frpjump.mqtt.PUBLISH_TIMEOUT_MS
import ru.adsrv.frpjump.mqtt.PublishAnswer
import ru.adsrv.frpjump.mqtt.PublishFailure
import ru.adsrv.frpjump.mqtt.TopicProblem
import ru.adsrv.frpjump.mqtt.isReservedTopic
import ru.adsrv.frpjump.mqtt.refusalText
import ru.adsrv.frpjump.mqtt.topicProblem
import ru.adsrv.frpjump.ui.AppSheet
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.MainButton
import ru.adsrv.frpjump.ui.Segmented

/** QoS levels offered, as the switch shows them. */
private val QOS_LEVELS = listOf(0, 1, 2)

/**
 * "Publish…" of a topic's or a branch's card: the topic prefilled with
 * [path], cursor at its end, so a subtopic is a `/name` away; the value as
 * text; Retain (off) and QoS (1). Send checks the topic first and answers
 * under the button; it is off, saying why, while the session is not open.
 * The sheet stays open after an answer, so the next value is one tap away.
 */
@Composable
fun PublishSheet(
    path: String,
    state: MqttState,
    onPublish: (topic: String, payload: String, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    var topic by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(path, TextRange(path.length))) }
    var payload by rememberSaveable { mutableStateOf("") }
    var retain by rememberSaveable { mutableStateOf(false) }
    var qos by rememberSaveable { mutableStateOf(1) }
    var problem by remember { mutableStateOf<TopicProblem?>(null) }
    var sending by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<PublishAnswer?>(null) }
    var edits by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // An answer is about what was sent: any change makes it stale.
    fun changed() {
        answer = null
        edits++
    }
    AppSheet(title = stringResource(R.string.mqtt_publish_title), onDismiss = onDismiss) {
        Field(
            label = stringResource(R.string.mqtt_topic),
            value = topic,
            onValueChange = {
                if (it.text != topic.text) {
                    problem = null
                    changed()
                }
                topic = it
            },
            mono = true,
            keyboard = KeyboardType.Uri,
            focusRequester = focus,
            problem = problem?.let { topicProblemText(it) },
            note = stringResource(R.string.mqtt_topic_reserved).takeIf { isReservedTopic(topic.text) },
        )
        Field(
            label = stringResource(R.string.mqtt_card_value),
            value = payload,
            onValueChange = {
                payload = it
                changed()
            },
            mono = true,
            singleLine = false,
            minLines = 3,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = retain, role = Role.Switch, onValueChange = {
                    retain = it
                    changed()
                }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.mqtt_publish_retain), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.mqtt_publish_retain_hint), fontSize = 13.sp, color = colors.muted)
            }
            Switch(checked = retain, onCheckedChange = null)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.mqtt_publish_qos), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
            Segmented(QOS_LEVELS, qos, label = { it.toString() }, onPick = {
                qos = it
                changed()
            })
        }
        val open = state is MqttState.Open
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MainButton(
                text = stringResource(R.string.mqtt_publish_send),
                busy = sending,
                enabled = open,
                onClick = {
                    val text = topic.text
                    val found = topicProblem(text)
                    problem = found
                    if (found != null) return@MainButton
                    sending = true
                    answer = null
                    val sentAt = edits
                    onPublish(text, payload, qos, retain) {
                        sending = false
                        // An edit made while sending makes the answer stale: drop it.
                        if (edits == sentAt) answer = it
                    }
                },
            )
            // The answer stays until something changes; while the session is not open, why Send is off.
            val lines = listOfNotNull(
                answer?.takeIf { !sending }?.let { answerText(it) to answerColor(it) },
                blockedText(state).takeIf { !open }?.let { it to colors.muted },
            )
            lines.forEach { (text, color) ->
                Text(
                    text,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** Why Send is off: the session's state, in the words of the explorer's banner. */
@Composable
private fun blockedText(state: MqttState): String = when (state) {
    MqttState.Connecting -> stringResource(R.string.mqtt_connecting)
    MqttState.Reconnecting -> stringResource(R.string.mqtt_reconnecting)
    is MqttState.NeedsLogin -> stringResource(R.string.mqtt_login_needed)
    is MqttState.Failed -> stringResource(R.string.state_not_connected)
    MqttState.Ended -> stringResource(R.string.mqtt_closed)
    is MqttState.Open -> ""
}

@Composable
private fun answerText(answer: PublishAnswer): String = when (answer) {
    PublishAnswer.Accepted -> stringResource(R.string.mqtt_publish_accepted)
    is PublishAnswer.Refused -> stringResource(R.string.mqtt_publish_refused, refusalText(answer))
    PublishAnswer.Unconfirmed -> stringResource(R.string.mqtt_publish_unconfirmed)
    is PublishAnswer.Failed -> when (answer.failure) {
        PublishFailure.NOT_CONNECTED -> stringResource(R.string.mqtt_publish_not_connected)
        PublishFailure.LOST -> stringResource(R.string.mqtt_publish_lost)
        PublishFailure.TIMEOUT -> stringResource(R.string.mqtt_publish_timeout, (PUBLISH_TIMEOUT_MS / 1000).toInt())
        PublishFailure.OTHER -> stringResource(R.string.mqtt_publish_failed, answer.detail.orEmpty())
    }
}

@Composable
private fun answerColor(answer: PublishAnswer) = when (answer) {
    PublishAnswer.Accepted -> AppTheme.colors.ink
    PublishAnswer.Unconfirmed -> AppTheme.colors.muted
    is PublishAnswer.Refused, is PublishAnswer.Failed -> AppTheme.colors.error
}
