package ru.adsrv.frpjump.ui.settings

import ru.adsrv.frpjump.ui.AppAlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.connections.kindWord

/**
 * The owner's question before a changing call. The four answers are pinned at the bottom of the card and are always visible. Above them
 * is one section that scrolls (it opens at its top): title, tool, target names, the command in its own capped scroll box with its line
 * count, and the warning. Marks say when more of the section is above or below. The command is shown with every non-printing or disguising
 * character as a highlighted visible escape ([displayCommandSpans]), always laid out left to right, with a note when it has right-to-left
 * letters. In the compact landscape layout ([wide]) the answers sit beside the section. "For session" is offered only when the target is
 * known by name; "Always" asks for a second confirmation first (it turns the question off for the whole app).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatConfirmationCard(confirmation: PendingConfirmation, onAnswer: (ConfirmationDecision) -> Unit, wide: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val info = confirmation.info
    val target = if (info == null) stringResource(R.string.confirm_unknown_target)
        else stringResource(R.string.confirm_target, info.profileName, info.connection.device, info.connection.label { kindWord(context, it) })
    val tool = stringResource(confirmation.tool.title())
    val allow = stringResource(R.string.confirm_allow)
    val session = stringResource(R.string.confirm_session)
    val always = stringResource(R.string.confirm_always)
    val decline = stringResource(R.string.confirm_decline)
    val sessionHint = stringResource(R.string.confirm_session_hint)
    val escapeStyle = SpanStyle(background = AppTheme.colors.accentContainer, color = AppTheme.colors.onAccentContainer, fontWeight = FontWeight.Bold)
    val shownCommand = remember(confirmation.id, escapeStyle) {
        buildAnnotatedString { for (span in displayCommandSpans(confirmation.command, forRender = true)) if (span.escape) withStyle(escapeStyle) { append(span.text) } else append(span.text) }
    }
    val describedCommand = remember(confirmation.id) { displayCommand(confirmation.command) }
    val lines = remember(confirmation.id) { commandLineCount(confirmation.command) }
    val rtl = remember(confirmation.id) { containsStrongRtl(confirmation.command) }
    val sectionScroll = rememberScrollState()
    val commandScroll = rememberScrollState()
    val commandLabel = stringResource(R.string.confirm_command)
    var askAlways by remember(confirmation.id) { mutableStateOf(false) }
    val canSession = info != null && confirmation.target != null
    // The command is always left to right: a line that starts with a right-to-left letter must not reverse the order of its commands.
    val ltr = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr)
    // The command box shows four lines (three in the two-pane layout) at the user's font size, then scrolls.
    val lineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
    // A tap answers through the pure mapping; Always has no answer until its dialog is confirmed.
    fun answer(tap: CardTap) { val decision = decisionFor(tap, alwaysConfirmed = false); if (decision != null) onAnswer(decision) else askAlways = true }

    // The sizes of the two blocks the decision below needs; measured the same way in both layouts, so the decision cannot flip-flop.
    var headerPx by remember { mutableIntStateOf(0) }
    var answersPx by remember { mutableIntStateOf(0) }

    @Composable fun Header() {
        Column(Modifier.onSizeChanged { headerPx = it.height }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.confirm_title), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            Text(tool, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("chat_confirm_tool"))
            // Two lines at most, ellipsis after that; TalkBack reads the whole name.
            Text(target, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { contentDescription = target }.testTag("chat_confirm_target"))
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
            Box(Modifier.heightIn(max = lineHeight * (if (wide) 3 else 4)).verticalScroll(commandScroll).testTag("chat_confirm_command_box")) {
                SelectionContainer {
                    Text(shownCommand, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$commandLabel: $describedCommand" }.testTag("chat_confirm_command"))
                }
            }
        }
    }
    @Composable fun Warning() = Text(stringResource(R.string.confirm_warning), color = AppTheme.colors.error, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("chat_confirm_warning"))
    @Composable fun Answers() {
        Column(Modifier.onSizeChanged { answersPx = it.height }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { answer(CardTap.ALLOW) }, modifier = Modifier.testTag("chat_confirm_allow")) { Text(allow) }
                if (canSession) OutlinedButton(onClick = { answer(CardTap.SESSION) },
                    modifier = Modifier.semantics { contentDescription = "$session. $sessionHint" }.testTag("chat_confirm_session")) { Text(session) }
                OutlinedButton(onClick = { answer(CardTap.ALWAYS) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.colors.error),
                    modifier = Modifier.testTag("chat_confirm_always")) { Text(always) }
                OutlinedButton(onClick = { answer(CardTap.DECLINE) }, modifier = Modifier.testTag("chat_confirm_decline")) { Text(decline) }
            }
            if (canSession) Hint(session, sessionHint, "chat_confirm_session_hint")
        }
    }
    /** The scrolling section: title, tool, target, command, warning; with [withAnswers] the answers follow (the short-window layout). */
    @Composable fun Section(modifier: Modifier, withAnswers: Boolean = false) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(sectionScroll).testTag("chat_confirm_section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Header(); CommandBlock(); Warning(); if (withAnswers) Answers()
            }
            if (sectionScroll.canScrollBackward) Text(stringResource(R.string.confirm_more_above), style = ltr, color = AppTheme.colors.accent, modifier = Modifier.testTag("chat_confirm_section_above"))
            if (sectionScroll.canScrollForward) Text(stringResource(R.string.confirm_more), style = ltr, color = AppTheme.colors.accent, modifier = Modifier.testTag("chat_confirm_section_more"))
        }
    }
    OutlinedCard(modifier.fillMaxWidth().testTag("chat_confirm_card"), border = BorderStroke(1.dp, AppTheme.colors.accent)) {
        if (wide) Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Section(Modifier.weight(1f).heightIn(max = 260.dp))
            Column(Modifier.weight(0.8f), verticalArrangement = Arrangement.spacedBy(8.dp)) { Answers() }
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

@Composable
private fun Hint(label: String, text: String, tag: String) {
    Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$label: ") }; append(text) },
        style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted, modifier = Modifier.testTag(tag))
}

/**
 * The compact mark shown while confirmation requests are off. Tap or long press explains it and offers turning requests back on;
 * there is no banner or strip. Sits between the title and the Tools action of the chat top bar. "Turn on" keeps the dialog open
 * until the setting is saved (the mark then disappears); if saving fails, [turnOnFailed] shows the error next to the button.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ConfirmationsOffIndicator(onTurnOn: () -> Unit, onTools: (() -> Unit)?, turnOnFailed: Boolean = false, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.confirmations_off_description)
    val details = stringResource(R.string.confirmations_details)
    Box(modifier.size(48.dp).combinedClickable(role = Role.Button, onClickLabel = details, onClick = { open = true }, onLongClickLabel = details, onLongClick = { open = true })
        .semantics { contentDescription = description }.testTag("chat_confirmations_off"), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = AppTheme.colors.error)
    }
    if (open) AppAlertDialog(
        onDismissRequest = { open = false },
        text = {
            // The error comes first (it is what just changed) and the text scrolls, so neither is cut off in a short window at large font.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (turnOnFailed) Text(stringResource(R.string.confirmations_turn_on_failed), color = AppTheme.colors.error, modifier = Modifier.testTag("chat_confirmations_turn_on_failed"))
                Text(stringResource(R.string.confirmations_off_explanation), modifier = Modifier.testTag("chat_confirmations_off_text"))
            }
        },
        confirmButton = { TextButton(onClick = onTurnOn, modifier = Modifier.testTag("chat_confirmations_turn_on")) { Text(stringResource(R.string.confirmations_turn_on)) } },
        dismissButton = {
            Row {
                if (onTools != null) TextButton(onClick = { open = false; onTools() }, modifier = Modifier.testTag("chat_confirmations_tools")) { Text(stringResource(R.string.assistant_tools)) }
                TextButton(onClick = { open = false }, modifier = Modifier.testTag("chat_confirmations_close")) { Text(stringResource(R.string.confirmations_close)) }
            }
        },
    )
}
