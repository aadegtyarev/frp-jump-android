package ru.adsrv.frpjump.ui.mqtt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import ru.adsrv.frpjump.mqtt.JsonProblem
import ru.adsrv.frpjump.mqtt.MAX_JSON_BYTES
import ru.adsrv.frpjump.mqtt.MAX_JSON_DEPTH
import ru.adsrv.frpjump.mqtt.PublishRequest
import ru.adsrv.frpjump.mqtt.PublishStart
import androidx.compose.ui.semantics.contentDescription
import ru.adsrv.frpjump.mqtt.SentTopic
import ru.adsrv.frpjump.mqtt.checkJson
import ru.adsrv.frpjump.mqtt.formatJson
import ru.adsrv.frpjump.mqtt.oneLineText
import ru.adsrv.frpjump.ui.files.SecondaryButton
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ui.notice
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
import ru.adsrv.frpjump.mqtt.plugins.CommandTarget
import ru.adsrv.frpjump.mqtt.plugins.Rule
import ru.adsrv.frpjump.mqtt.plugins.commandFor
import ru.adsrv.frpjump.ui.AppSheet
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.MainButton
import ru.adsrv.frpjump.ui.Segmented

/** QoS levels offered, as the switch shows them. */
private val QOS_LEVELS = listOf(0, 1, 2)

/** The payload's formats, as the switch shows them: text, then JSON. */
private val FORMATS = listOf(false, true)

/** The Status topic / Command topic switch: true is the status topic (the one in the field). */
private val TARGETS = listOf(true, false)

/** Characters of a recent payload shown on its chip. */
private const val RECENT_CHARS = 40

/**
 * "Publish…" of a topic's or a branch's card, or "Publish here…" of a
 * level, as [start] says: the topic prefilled, cursor at its end, so a
 * subtopic is a `/name` away; the value prefilled (the topic's current
 * text) and selected whole, so typing replaces it; the focus on the value
 * for a topic with a value, else on the topic. The value as Text or JSON
 * (checked as typed, Format pretty-prints it); the last payloads sent to
 * the topic in the field, a tap puts one in the value; Retain, QoS and the
 * format as last sent to the prefilled topic (from [published]), else
 * retain off, QoS 1, Text. Send checks the topic and, as JSON, the value
 * first and answers on the button itself until something changes; it is
 * off, saying why, while the session is not open. The sheet stays open
 * after an answer, so the next value is one tap away. When one of [rules]
 * (the "Status and command topics" plugin) matches the topic in the field,
 * the value goes to its command topic by default, the switch above Send
 * offering the status topic for this send, the rule named under it; a
 * one-template rule only says the topic takes commands itself.
 */
@Composable
fun PublishSheet(
    start: PublishStart,
    state: MqttState,
    published: List<SentTopic>?,
    /** The plugin's rules in force for the connection, in order; empty: the value goes to the topic in the field. */
    rules: List<Rule>,
    onPublish: (PublishRequest, done: (PublishAnswer) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val path = start.topic
    var topic by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(path, TextRange(path.length))) }
    var payloadField by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(start.payload, TextRange(0, start.payload.length)))
    }
    val payload = payloadField.text
    val last = published?.firstOrNull { it.topic == path }
    var json by rememberSaveable { mutableStateOf(last?.json ?: false) }
    var retain by rememberSaveable { mutableStateOf(last?.retain ?: false) }
    var qos by rememberSaveable { mutableStateOf(last?.qos ?: 1) }
    // The settings last sent to [path] are taken once, when read: a choice made since is not overridden.
    var settled by rememberSaveable { mutableStateOf(published != null) }
    LaunchedEffect(published) {
        if (settled || published == null) return@LaunchedEffect
        settled = true
        published.firstOrNull { it.topic == path }?.let {
            retain = it.retain
            qos = it.qos
            json = it.json
        }
    }
    var problem by remember { mutableStateOf<TopicProblem?>(null) }
    // An empty JSON value is flagged once Send or Format was tapped, not while the field is still untouched.
    var jsonFlagged by remember { mutableStateOf(false) }
    val jsonCheck = remember(payload, json) { if (json) checkJson(payload) else null }
    val jsonProblem = jsonCheck?.problem?.let { jsonProblemText(it) }
    var sending by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<PublishAnswer?>(null) }
    var edits by remember { mutableStateOf(0) }
    // Editing the topic matches it again; a switch to the status topic holds for the rule it was made for.
    val target = remember(topic.text, rules) { commandFor(topic.text, rules) }
    var toStatus by remember(target) { mutableStateOf(false) }
    val sendTo = (target as? CommandTarget.Elsewhere)?.takeIf { !toStatus }?.topic ?: topic.text
    val toCommand = target is CommandTarget.Itself || (target is CommandTarget.Elsewhere && !toStatus)
    val focus = remember { FocusRequester() }
    val payloadFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { (if (start.focusPayload) payloadFocus else focus).requestFocus() } }
    // An answer is about what was sent: any change makes it stale.
    fun changed() {
        answer = null
        edits++
    }
    fun setPayload(text: String) {
        payloadField = TextFieldValue(text, TextRange(text.length))
        jsonFlagged = false
        changed()
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(FORMATS, json, label = { stringResource(if (it) R.string.mqtt_view_json else R.string.mqtt_view_text) }, onPick = {
                settled = true
                json = it
                jsonFlagged = false
                changed()
            })
            Field(
                label = stringResource(R.string.mqtt_card_value),
                value = payloadField,
                onValueChange = {
                    // A move of the cursor or the selection is no edit.
                    if (it.text != payloadField.text) {
                        jsonFlagged = false
                        changed()
                    }
                    payloadField = it
                },
                mono = true,
                singleLine = false,
                minLines = 3,
                focusRequester = payloadFocus,
                problem = jsonProblem?.takeIf { jsonCheck?.problem != JsonProblem.Empty || jsonFlagged },
            )
            if (json) {
                SecondaryButton(stringResource(R.string.mqtt_publish_format_json), null, enabled = true, Modifier.fillMaxWidth()) {
                    val element = jsonCheck?.element
                    val formatted = element?.let { formatJson(it) }
                    when {
                        // Not JSON: the problem under the field, and said again, so the tap is answered when it already shows.
                        element == null -> {
                            jsonFlagged = true
                            jsonProblem?.let { context.notice(it, NoticeKind.WARN) }
                        }
                        // Formatted it would be too large to send as JSON: the value stays as it is.
                        formatted == null -> context.notice(context.getString(R.string.mqtt_publish_format_too_large, MAX_JSON_BYTES / 1024), NoticeKind.WARN)
                        formatted == payload -> context.notice(context.getString(R.string.mqtt_publish_formatted_already))
                        else -> setPayload(formatted)
                    }
                }
            }
        }
        val recent = published?.firstOrNull { it.topic == topic.text }?.payloads.orEmpty()
        if (recent.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.mqtt_publish_recent), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val use = stringResource(R.string.mqtt_publish_recent_use)
                    val empty = stringResource(R.string.mqtt_empty_value)
                    recent.forEach { sent ->
                        RecentChip(if (sent.isEmpty()) empty else oneLineText(sent, RECENT_CHARS), use) { setPayload(sent) }
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = retain, role = Role.Switch, onValueChange = {
                    settled = true
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
        // An empty retained message is how MQTT removes a retained value: say so before it is sent.
        if (retain && payload.isEmpty()) {
            Text(stringResource(R.string.mqtt_publish_retain_empty), fontSize = 13.sp, lineHeight = 18.sp, color = colors.warning)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.mqtt_publish_qos), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
            Segmented(QOS_LEVELS, qos, label = { it.toString() }, onPick = {
                settled = true
                qos = it
                changed()
            })
        }
        target?.let { matched ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (matched is CommandTarget.Elsewhere) {
                    Segmented(TARGETS, toStatus, label = {
                        stringResource(if (it) R.string.mqtt_rule_status_topic else R.string.mqtt_rule_command_topic)
                    }, onPick = {
                        toStatus = it
                        changed()
                    })
                }
                Text(
                    if (matched is CommandTarget.Elsewhere) {
                        stringResource(R.string.mqtt_rule_goes_to, matched.rule.label, sendTo)
                    } else {
                        stringResource(R.string.mqtt_rule_itself, matched.rule.label)
                    },
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = colors.muted,
                )
            }
        }
        // A retained command is the broker's to give the device again: say so before it is sent.
        if (toCommand && retain) {
            Text(stringResource(R.string.mqtt_rule_retained_command), fontSize = 13.sp, lineHeight = 18.sp, color = colors.warning)
        }
        val open = state is MqttState.Open
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The answer is on the button itself until something changes, so nothing below moves.
            val shown = answer?.takeIf { !sending }
            MainButton(
                text = shown?.let { answerText(it) } ?: stringResource(R.string.mqtt_publish_send),
                busy = sending,
                enabled = open,
                error = shown is PublishAnswer.Refused || shown is PublishAnswer.Failed,
                announce = true,
                onClick = {
                    val text = topic.text
                    // The command topic a rule makes of it is checked too (it may come out too long).
                    val found = topicProblem(text) ?: topicProblem(sendTo)
                    problem = found
                    // As JSON, a value that is not JSON is not sent: its problem shows under the field.
                    val notJson = jsonCheck?.problem != null
                    if (notJson) {
                        jsonFlagged = true
                        jsonProblem?.let { context.notice(it, NoticeKind.WARN) }
                    }
                    if (found != null || notJson) return@MainButton
                    sending = true
                    answer = null
                    val sentAt = edits
                    onPublish(PublishRequest(sendTo, payload, qos, retain, json, field = text)) {
                        sending = false
                        // An edit made while sending makes the answer stale: drop it.
                        if (edits == sentAt) answer = it
                    }
                },
            )
            // While the session is not open, why Send is off.
            if (!open) {
                Text(
                    blockedText(state),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = colors.muted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** A payload sent before, on one line; a tap puts it in the value field. */
@Composable
private fun RecentChip(text: String, onClickLabel: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .height(36.dp)
            .widthIn(max = 240.dp)
            .clip(shape)
            .border(1.dp, colors.outline, shape)
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun jsonProblemText(problem: JsonProblem): String = when (problem) {
    JsonProblem.Empty -> stringResource(R.string.mqtt_publish_json_empty)
    JsonProblem.TooLarge -> stringResource(R.string.mqtt_publish_json_too_large, MAX_JSON_BYTES / 1024)
    JsonProblem.TooDeep -> stringResource(R.string.mqtt_publish_json_too_deep, MAX_JSON_DEPTH)
    is JsonProblem.Invalid ->
        if (problem.line != null && problem.column != null) {
            stringResource(R.string.mqtt_publish_json_invalid_at, problem.line, problem.column)
        } else {
            stringResource(R.string.mqtt_publish_json_invalid)
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
