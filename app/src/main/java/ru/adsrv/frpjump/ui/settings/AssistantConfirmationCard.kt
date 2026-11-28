package ru.adsrv.frpjump.ui.settings

import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.ui.AppAlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.chat.CardTap
import ru.adsrv.frpjump.assistant.chat.ConfirmationDecision
import ru.adsrv.frpjump.assistant.chat.decisionFor
import ru.adsrv.frpjump.assistant.chat.PendingConfirmation
import ru.adsrv.frpjump.assistant.chat.commandLineCount
import ru.adsrv.frpjump.assistant.chat.containsStrongRtl
import ru.adsrv.frpjump.assistant.chat.displayCommand
import ru.adsrv.frpjump.assistant.chat.displayCommandSpans
import ru.adsrv.frpjump.assistant.chat.displayTyped
import ru.adsrv.frpjump.assistant.chat.displayTypedSpans
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.connections.kindWord

/**
 * The owner's question before a changing call. The answers are pinned at the bottom of the card and are always visible (a card that can remember has five: Allow and Decline, then under "Allow and remember" For session, For this connection and Always). Above them
 * is one section that scrolls (it opens at its top): title, tool, target names, the command in its own capped scroll box with its line
 * count, and the warning. Marks say when more of the section is above or below. The command is shown with every non-printing or disguising
 * character as a highlighted visible escape ([displayCommandSpans]), always laid out left to right, with a note when it has right-to-left
 * letters. In the compact landscape layout ([wide]) the answers sit beside the section. "For session" is offered only when the target is
 * known by name; "Always" asks for a second confirmation first (it turns the question off for the whole app).
 *
 * A call that types into a terminal tab ([PendingConfirmation.typing]) has the same card with the tab in the place of the connection (and
 * the program in its foreground, when known), the text typed (every Enter shown as a mark, the whole text in the same scrolling box) and
 * the keys pressed after it.
 */
/**
 * The one hint under the remember answers: what For session and For this connection cover. It names the grantable tools in the grant (both, typing
 * only, or commands); with none known, the card's own kind decides. It is the same grant the card said it covers when it was raised.
 */
internal fun rememberHintRes(sessionTools: Set<AssistantToolId>, typing: Boolean, mqtt: Boolean = false, http: Boolean = false): Int = when {
    http || AssistantToolId.HTTP_POST in sessionTools -> R.string.confirm_http_remember_hint
    mqtt -> R.string.confirm_mqtt_remember_hint
    AssistantToolId.MQTT_PUBLISH in sessionTools -> R.string.confirm_mqtt_remember_hint
    sessionTools.size > 1 -> R.string.confirm_remember_hint_both
    sessionTools.singleOrNull() == AssistantToolId.TERMINAL_INPUT -> R.string.confirm_remember_hint_input
    sessionTools.singleOrNull() != null -> R.string.confirm_remember_hint
    typing -> R.string.confirm_remember_hint_input
    else -> R.string.confirm_remember_hint
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatConfirmationCard(confirmation: PendingConfirmation, onAnswer: (ConfirmationDecision) -> Unit, wide: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val info = confirmation.info
    val typing = confirmation.typing
    val stop = confirmation.stop
    val close = confirmation.close
    val skill = confirmation.skill
    val mqtt = confirmation.mqtt
    val http = confirmation.http
    val target = if (http != null) {
        stringResource(R.string.confirm_http_target, http.profile, ru.adsrv.frpjump.ssh.isolateName(http.name))
    } else if (mqtt != null) {
        stringResource(R.string.confirm_stop_target, mqtt.profile, ru.adsrv.frpjump.ssh.isolateName(mqtt.name), mqtt.device, mqtt.port)
    } else if (skill != null) {
        // The skill's name, bidi-isolated.
        ru.adsrv.frpjump.ssh.isolateName(skill.name)
    } else if (close != null) {
        // The session as a tab's identity (profile, user and device, number), a name after it, bidi-isolated, never alone.
        if (close.profile != null && close.user != null && close.device != null && close.number != null)
            if (close.name != null) stringResource(R.string.chat_detail_tab_name_named, ru.adsrv.frpjump.ssh.isolateName(close.name), close.profile, close.user, close.device, close.number)
            else stringResource(R.string.chat_detail_tab_named, close.profile, close.user, close.device, close.number)
        else stringResource(R.string.chat_detail_tab, close.tabId)
    } else if (stop != null) {
        // Identity first (profile, device and port), the tunnel's name between its marks, bidi-isolated.
        stringResource(R.string.confirm_stop_target, stop.profile, ru.adsrv.frpjump.ssh.isolateName(stop.name), stop.device, stop.port)
    } else if (typing != null) {
        if (typing.profile != null && typing.user != null && typing.device != null && typing.number != null)
            // A name is shown after the tab's profile, user and device (identity first), bidi-isolated, never alone.
            if (typing.name != null) stringResource(R.string.chat_detail_tab_name_named, ru.adsrv.frpjump.ssh.isolateName(typing.name), typing.profile, typing.user, typing.device, typing.number)
            else stringResource(R.string.chat_detail_tab_named, typing.profile, typing.user, typing.device, typing.number)
        else stringResource(R.string.chat_detail_tab, typing.tabId)
    } else if (info == null) stringResource(R.string.confirm_unknown_target)
        else stringResource(R.string.confirm_target, info.profileName, info.connection.device, info.connection.label { kindWord(context, it) })
    val toolTitle = stringResource(if (typing != null || close != null || skill != null) confirmation.tool.receiptTitle() else confirmation.tool.title())
    // "Class · tool", e.g. "Changes in connections · SSH command".
    val tool = confirmation.tool.actionClass.cardName()?.let { stringResource(R.string.chat_confirm_tool, stringResource(it), toolTitle) } ?: toolTitle
    // The two-pane layout keeps it on one line with the tool first, so the ellipsis cuts the class and never the tool; TalkBack reads "class · tool".
    val wideTool = confirmation.tool.actionClass.cardName()?.let { stringResource(R.string.chat_confirm_tool, toolTitle, stringResource(it)) } ?: toolTitle
    val allow = stringResource(R.string.confirm_allow)
    val session = stringResource(R.string.confirm_session)
    val connectionAnswer = stringResource(R.string.confirm_connection)
    val always = stringResource(R.string.confirm_always)
    val decline = stringResource(R.string.confirm_decline)
    // What For session covers: the grantable tools of the class that were in effect when the card was raised (both: commands and typing).
    val rememberHint = stringResource(rememberHintRes(confirmation.sessionTools, typing != null, mqtt != null, http != null))
    val escapeStyle = SpanStyle(background = AppTheme.colors.accentContainer, color = AppTheme.colors.onAccentContainer, fontWeight = FontWeight.Bold)
    val shownCommand = remember(confirmation.id, escapeStyle) {
        val spans = if (typing != null) displayTypedSpans(confirmation.command, forRender = true) else displayCommandSpans(confirmation.command, forRender = true)
        buildAnnotatedString { for (span in spans) if (span.escape) withStyle(escapeStyle) { append(span.text) } else append(span.text) }
    }
    val describedCommand = remember(confirmation.id) { if (typing != null) displayTyped(confirmation.command) else displayCommand(confirmation.command) }
    val lines = remember(confirmation.id) {
        // Typed text: any kind of line break is one Enter, and a final one is shown by its own mark, not counted as a line.
        commandLineCount(if (typing != null) confirmation.command.replace("\r\n", "\n").replace('\r', '\n') else confirmation.command)
    }
    val rtl = remember(confirmation.id) { containsStrongRtl(confirmation.command) }
    val sectionScroll = rememberScrollState()
    val commandScroll = rememberScrollState()
    val commandLabel = stringResource(if (http != null) R.string.confirm_http_body else if (mqtt != null) R.string.confirm_mqtt_payload else if (skill != null) R.string.confirm_skill_text else if (typing != null) R.string.confirm_text else R.string.confirm_command)
    var askAlways by remember(confirmation.id) { mutableStateOf(false) }
    // No "For session" for a stop: its hint speaks of commands on a tunnel.
    val canSession = info != null && confirmation.target != null && confirmation.tool.grantable && stop == null && close == null && skill == null
    // The command is always left to right: a line that starts with a right-to-left letter must not reverse the order of its commands.
    val ltr = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr)
    // The command box shows four lines (three in the two-pane layout) at the user's font size, then scrolls.
    val lineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
    // A tap answers through the pure mapping; Always has no answer until its dialog is confirmed.
    fun answer(tap: CardTap) { val decision = decisionFor(tap, alwaysConfirmed = false); if (decision != null) onAnswer(decision) else askAlways = true }

    // The sizes of the two blocks the decision below needs; measured the same way in both layouts, so the decision cannot flip-flop.
    var headerPx by remember { mutableIntStateOf(0) }
    var answersPx by remember { mutableIntStateOf(0) }
    // The wide layout's own measure of the answers beside the section (taken only there, so the choice below cannot flip-flop between layouts).
    var rowAnswersPx by remember { mutableIntStateOf(0) }

    @Composable fun Header() {
        Column(Modifier.onSizeChanged { headerPx = it.height }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // A command names the connection it runs in ("Run in <name>?"): the name bidi-isolated, tied to "in" by a no-break space; a card without a known
            // connection keeps "Run this command?". The other cards keep their titles.
            val title = if (http != null) stringResource(R.string.confirm_http_title) else if (mqtt != null) stringResource(R.string.confirm_mqtt_title) else if (skill != null) stringResource(skillTitle(skill)) else if (close != null) stringResource(R.string.confirm_close_title)
                else if (stop != null) stringResource(R.string.confirm_stop_title) else if (typing != null) stringResource(R.string.confirm_input_title)
                else if (info != null) stringResource(R.string.confirm_title_in, ru.adsrv.frpjump.ssh.isolateName(info.connection.label { kindWord(context, it) }))
                else stringResource(R.string.confirm_title)
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }.testTag("chat_confirm_title"))
            // Portrait: two lines, "class · tool" must not lose the tool at a large font. Two-pane: one line, "tool · class", the class is what gets cut.
            if (mqtt == null && http == null && wide) Text(wideTool, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = tool }.testTag("chat_confirm_tool"))
            else if (mqtt == null && http == null) Text(tool, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("chat_confirm_tool"))
            if (typing != null) {
                // The tab and the program as lines of their own, each with its label; the whole value stays readable (the section scrolls).
                FieldLine(stringResource(R.string.confirm_tab), target, "chat_confirm_target")
                typing.program?.let { FieldLine(stringResource(R.string.confirm_program), it, "chat_confirm_program") }
            } else if (mqtt != null || http != null) {
                Text(target, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted,
                    modifier = Modifier.semantics { contentDescription = target }.testTag("chat_confirm_target"))
            } else {
                // Two lines at most, ellipsis after that; TalkBack reads the whole name.
                Text(target, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { contentDescription = target }.testTag("chat_confirm_target"))
            }
            if (http != null) {
                FieldLine(stringResource(R.string.confirm_http_endpoint), "${if (http.https) "https" else "http"}://${http.device}:${http.port}", "chat_confirm_http_endpoint")
                FieldLine(stringResource(R.string.confirm_http_path), skillText(http.path, escapeStyle), "chat_confirm_http_path")
                FieldLine(stringResource(R.string.confirm_http_type), http.contentType, "chat_confirm_http_type")
            }
            if (mqtt != null) {
                FieldLine(stringResource(R.string.confirm_mqtt_topic), skillText(mqtt.topic, escapeStyle), "chat_confirm_mqtt_topic")
                FieldLine(stringResource(R.string.confirm_mqtt_qos), mqtt.qos.toString(), "chat_confirm_mqtt_qos")
                FieldLine(stringResource(R.string.confirm_mqtt_retain), stringResource(if (mqtt.retain) R.string.confirm_mqtt_yes else R.string.confirm_mqtt_no), "chat_confirm_mqtt_retain")
                if (mqtt.retain && confirmation.command.isEmpty()) Text(stringResource(R.string.confirm_mqtt_clear), color = AppTheme.colors.error, modifier = Modifier.testTag("chat_confirm_mqtt_clear"))
            }
            if (skill != null) {
                // The whole skill as plain text: its description and what it requires here, its text in the box below.
                // The description enters the system prompt on every Send, so it is shown like the command: every invisible character as a visible escape.
                FieldLine(stringResource(R.string.confirm_skill_description), skillText(skill.description, escapeStyle), "chat_confirm_skill_description")
                FieldLine(stringResource(R.string.confirm_skill_requires), switchTitles(skill.requires), "chat_confirm_skill_requires")
                if (skill.oldDescription != null) {
                    FieldLine(stringResource(R.string.confirm_skill_old_description), skillText(skill.oldDescription, escapeStyle), "chat_confirm_skill_old_description")
                    FieldLine(stringResource(R.string.confirm_skill_old_requires), switchTitles(skill.oldRequires.orEmpty()), "chat_confirm_skill_old_requires")
                }
            }
        }
    }
    @Composable fun CommandBlock() {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val more = commandScroll.canScrollForward
            if (lines > 1 || more || rtl) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (lines > 1) Text(stringResource(R.string.confirm_lines, lines), style = ltr, color = AppTheme.colors.muted, modifier = Modifier.testTag("chat_confirm_lines"))
                if (more) Text(stringResource(R.string.confirm_more), style = ltr, color = AppTheme.colors.accent, modifier = Modifier.testTag("chat_confirm_more"))
                if (rtl) Text(stringResource(R.string.confirm_rtl_note), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.error, modifier = Modifier.testTag("chat_confirm_rtl"))
            }
            if (typing != null || skill != null || mqtt != null || http != null) Text(commandLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("chat_confirm_text_label"))
            if ((mqtt != null || http != null) && confirmation.command.isEmpty()) Text(stringResource(if (http != null) R.string.confirm_http_empty else R.string.confirm_mqtt_empty), modifier = Modifier.testTag(if (http != null) "chat_confirm_http_empty" else "chat_confirm_mqtt_empty"))
            if ((mqtt == null && http == null) || confirmation.command.isNotEmpty()) Box(Modifier.heightIn(max = lineHeight * (if (skill != null) (if (wide) 6 else 10) else if (wide) 3 else 4)).verticalScroll(commandScroll).testTag("chat_confirm_command_box")) {
                SelectionContainer {
                    Text(shownCommand, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$commandLabel: $describedCommand" }.testTag("chat_confirm_command"))
                }
            }
        }
    }
    // Keys-only typing has no text: no empty box.
    val showCommand = stop == null && close == null && (skill == null || skill.kind != ru.adsrv.frpjump.assistant.SkillCard.Kind.DELETE) && (typing == null || confirmation.command.isNotEmpty())
    // A stop says how many tabs close, only when some do.
    @Composable fun Warning() {
        if (http != null) {
            Text(stringResource(R.string.confirm_http_warning), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.error, modifier = Modifier.testTag("chat_confirm_warning"))
        } else if (mqtt != null) {
            Text(stringResource(R.string.confirm_mqtt_warning), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.error, modifier = Modifier.testTag("chat_confirm_warning"))
        } else if (skill != null) {
            // The card shows the whole skill: no further warning.
        } else if (close != null) {
            Text(stringResource(R.string.confirm_close_warning), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("chat_confirm_close_warning"))
        } else if (stop != null) {
            if (stop.tabs > 0) Text(stringResource(R.string.confirm_stop_tabs, stop.tabs), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("chat_confirm_stop_tabs"))
        } else Text(stringResource(if (typing != null) R.string.confirm_input_warning else R.string.confirm_warning), color = AppTheme.colors.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("chat_confirm_warning"))
    }
    @Composable fun Answers(onHeight: (Int) -> Unit = { answersPx = it }) {
        // Every button shows 32 dp and keeps a touch target of 48 dp (the Material minimum), so the rows below do not add space of their own.
        Column(Modifier.onSizeChanged { onHeight(it.height) }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton(allow, CardButtonKind.FILLED, { answer(CardTap.ALLOW) }, Modifier.testTag("chat_confirm_allow"))
                CardButton(decline, CardButtonKind.OUTLINED, { answer(CardTap.DECLINE) }, Modifier.testTag("chat_confirm_decline"))
                // A card that cannot be remembered keeps Always (it asks a second time), quietly, in the same row.
                if (!canSession) CardButton(always, CardButtonKind.TEXT, { answer(CardTap.ALWAYS) }, Modifier.testTag("chat_confirm_always"), AppTheme.colors.error)
            }
            if (canSession) {
                // What is remembered, under its own label: quiet text buttons that wrap as one row, Always last in the error colour; then one hint for all.
                Text(stringResource(R.string.confirm_remember), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted,
                    modifier = Modifier.padding(top = 4.dp).testTag("chat_confirm_remember"))
                // The first text button's own padding is pulled back so its letters line up with the label above.
                FlowRow(Modifier.offset(x = (-10).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CardButton(session, CardButtonKind.TEXT, { answer(CardTap.SESSION) }, Modifier.testTag("chat_confirm_session"))
                    CardButton(connectionAnswer, CardButtonKind.TEXT, { answer(CardTap.CONNECTION) }, Modifier.testTag("chat_confirm_connection"))
                    CardButton(always, CardButtonKind.TEXT, { answer(CardTap.ALWAYS) }, Modifier.testTag("chat_confirm_always"), AppTheme.colors.error)
                }
                Text(rememberHint, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted, modifier = Modifier.testTag("chat_confirm_remember_hint"))
            }
        }
    }
    /** The scrolling section: title, tool, target, command, warning; with [withAnswers] the answers follow (the short-window layout). */
    @Composable fun Section(modifier: Modifier, withAnswers: Boolean = false) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(sectionScroll).testTag("chat_confirm_section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Header(); if (showCommand) CommandBlock()
                skill?.oldBody?.let { old -> OldSkillText(skillText(old, escapeStyle), confirmation.id) }
                typing?.keys?.takeIf { it.isNotEmpty() }?.let { FieldLine(stringResource(R.string.confirm_keys), it.joinToString(", "), "chat_confirm_keys") }
                Warning()
                if (withAnswers) Answers()
            }
            if (sectionScroll.canScrollBackward) Text(stringResource(R.string.confirm_more_above), style = ltr, color = AppTheme.colors.accent, modifier = Modifier.testTag("chat_confirm_section_above"))
            if (sectionScroll.canScrollForward) Text(stringResource(R.string.confirm_more), style = ltr, color = AppTheme.colors.accent, modifier = Modifier.testTag("chat_confirm_section_more"))
        }
    }
    OutlinedCard(modifier.fillMaxWidth().testTag("chat_confirm_card"), border = BorderStroke(1.dp, AppTheme.colors.accent)) {
        if (wide) BoxWithConstraints {
            // Beside the section while the room holds the answers; in a shorter window the whole card scrolls instead (as the portrait layout does), so no answer is cut.
            val fits = !constraints.hasBoundedHeight || with(LocalDensity.current) { rowAnswersPx + 24.dp.toPx() <= constraints.maxHeight }
            if (fits) Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The row is as tall as the answers anyway (five of them wrap at a large font): the section may use that height instead of stopping at 260 dp.
                Section(Modifier.weight(1f).heightIn(max = maxOf(260.dp, with(LocalDensity.current) { rowAnswersPx.toDp() })))
                // Measured with no height limit: the row's own limit would hide that the answers do not fit (then the whole card scrolls instead).
                Column(Modifier.weight(0.8f).wrapContentHeight(Alignment.Top, unbounded = true), verticalArrangement = Arrangement.spacedBy(8.dp)) { Answers { rowAnswersPx = it } }
            } else Column(Modifier.padding(12.dp)) { Section(Modifier.weight(1f, fill = false), withAnswers = true) }
        } else BoxWithConstraints {
            // Pinned layout (answers fixed below a scrolling section) only while the room holds the answers, the header and at least the first
            // command line. In a shorter window the whole card scrolls instead, opening at its top: title, target, command, warning, answers,
            // so Allow is never reachable without the command's first line being reachable first.
            val pinned = !constraints.hasBoundedHeight || with(LocalDensity.current) {
                answersPx + headerPx + lineHeight.toPx() + 40.dp.toPx() <= constraints.maxHeight
            }
            if (pinned) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section(Modifier.weight(1f, fill = false))
                Answers()
            } else Column(Modifier.padding(12.dp)) { Section(Modifier.weight(1f, fill = false), withAnswers = true) }
        }
    }
    if (askAlways) AppAlertDialog(
        onDismissRequest = { askAlways = false },
        // A smaller title style, so a long word plus its question mark stays on one line at large font (no lone "?").
        title = { Text(stringResource(R.string.always_dialog_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("chat_always_dialog_title")) },
        text = { Text(stringResource(R.string.always_dialog_text, stringResource(R.string.settings), stringResource(R.string.assistant_title), stringResource(R.string.assistant_tools)),
            modifier = Modifier.verticalScroll(rememberScrollState()).testTag("chat_always_dialog_text")) },
        confirmButton = { TextButton(onClick = { askAlways = false; decisionFor(CardTap.ALWAYS, alwaysConfirmed = true)?.let(onAnswer) }, modifier = Modifier.testTag("chat_always_turn_off")) {
            Text(stringResource(R.string.always_dialog_confirm), color = AppTheme.colors.error) } },
        dismissButton = { TextButton(onClick = { askAlways = false }, modifier = Modifier.testTag("chat_always_cancel")) { Text(stringResource(R.string.always_dialog_cancel)) } },
    )
}

/** [raw] as the card shows model text: every non-printing or disguising character as a highlighted visible escape ([displayCommandSpans]). */
private fun skillText(raw: String, escape: SpanStyle): AnnotatedString = buildAnnotatedString {
    for (span in displayCommandSpans(raw, forRender = true)) if (span.escape) withStyle(escape) { append(span.text) } else append(span.text)
}

/** The owner's own words for the switches a skill requires (their titles on the Tools screen), "nothing" for none; an unknown name as it is. */
@Composable
private fun switchTitles(wires: List<String>): AnnotatedString {
    val titles = wires.map { wire -> ru.adsrv.frpjump.assistant.AssistantToolId.fromWire(wire)?.let { stringResource(it.title()) } ?: wire }
    return AnnotatedString(titles.joinToString(", ").ifEmpty { stringResource(R.string.confirm_skill_requires_none) })
}

/** "Label: value", the label bold; the value wraps in full (the card's section scrolls), and TalkBack reads both. */
@Composable
private fun FieldLine(label: String, value: String, tag: String) = FieldLine(label, AnnotatedString(value), tag)

@Composable
private fun FieldLine(label: String, value: AnnotatedString, tag: String) {
    Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$label: ") }; append(value) },
        modifier = Modifier.semantics { contentDescription = "$label: ${value.text}" }.testTag(tag))
}

private enum class CardButtonKind { FILLED, OUTLINED, TEXT }

/**
 * An answer: 32 dp tall as drawn (taller only when its text wraps at a large font), with the Material touch target (48 dp, at least 44) around it
 * from minimumInteractiveComponentSize (the same as Button). Filled is the primary answer, outlined the other one-time answer, text the quiet ones that are remembered.
 */
@Composable
private fun CardButton(text: String, kind: CardButtonKind, onClick: () -> Unit, modifier: Modifier = Modifier, textColor: androidx.compose.ui.graphics.Color? = null) {
    val filled = ButtonDefaults.buttonColors()
    val content = textColor ?: when (kind) { CardButtonKind.FILLED -> filled.contentColor; else -> AppTheme.colors.accent }
    Surface(onClick = onClick, modifier = modifier.minimumInteractiveComponentSize().semantics { role = androidx.compose.ui.semantics.Role.Button }, shape = ButtonDefaults.shape,
        color = if (kind == CardButtonKind.FILLED) filled.containerColor else androidx.compose.ui.graphics.Color.Transparent, contentColor = content,
        border = if (kind == CardButtonKind.OUTLINED) BorderStroke(1.dp, AppTheme.colors.outline) else null) {
        Box(Modifier.heightIn(min = 32.dp).padding(horizontal = if (kind == CardButtonKind.TEXT) 10.dp else 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

/**
 * "Your input is needed": the assistant asked the owner to act in one of its own sessions (a password, a choice only he should make). The identity of the
 * session (user and device, number), the reason as plain text (cleaned and capped, never markup), and Open, which shows the session as a tab.
 * The model's reason is the model's text: shown as plain text after the identity, never alone.
 */
@Composable
internal fun OwnerAskCard(ask: ru.adsrv.frpjump.assistant.OwnerAsk, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val identity = if (ask.name != null) stringResource(R.string.chat_detail_tab_name_named, ru.adsrv.frpjump.ssh.isolateName(ask.name), ask.profile, ask.user, ask.device, ask.number)
        else stringResource(R.string.chat_detail_tab_named, ask.profile, ask.user, ask.device, ask.number)
    OutlinedCard(modifier.fillMaxWidth().testTag("chat_ask_card"), border = BorderStroke(1.dp, AppTheme.colors.accent)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.assistant_ask_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }.testTag("chat_ask_title"))
            Text(identity, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("chat_ask_identity"))
            if (ask.reason.isNotEmpty()) Text(ask.reason, modifier = Modifier.testTag("chat_ask_reason"))
            Button(onClick = { onOpen(); onDismiss() }, modifier = Modifier.testTag("chat_ask_open")) { Text(stringResource(R.string.assistant_ask_open)) }
        }
    }
}

private fun skillTitle(skill: ru.adsrv.frpjump.assistant.SkillCard) = when (skill.kind) {
    ru.adsrv.frpjump.assistant.SkillCard.Kind.SAVE -> R.string.confirm_skill_save_title
    ru.adsrv.frpjump.assistant.SkillCard.Kind.REPLACE -> R.string.confirm_skill_replace_title
    ru.adsrv.frpjump.assistant.SkillCard.Kind.DELETE -> R.string.confirm_skill_delete_title
}

/** The text a replaced skill had, reachable from the card (closed at first): plain text, in its own scrolling box. */
@Composable
private fun OldSkillText(old: AnnotatedString, id: Long) {
    var shown by remember(id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = { shown = !shown }, modifier = Modifier.testTag("chat_confirm_skill_old_toggle")) {
            Text(stringResource(if (shown) R.string.confirm_skill_old_hide else R.string.confirm_skill_old_show))
        }
        if (shown) {
            Text(stringResource(R.string.confirm_skill_old_text), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Box(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).testTag("chat_confirm_skill_old_box")) {
                SelectionContainer { Text(old, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr), modifier = Modifier.fillMaxWidth().testTag("chat_confirm_skill_old")) }
            }
        }
    }
}
