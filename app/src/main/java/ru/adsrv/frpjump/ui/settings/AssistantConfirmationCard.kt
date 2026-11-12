package ru.adsrv.frpjump.ui.settings

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
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.chat.ConfirmationDecision
import ru.adsrv.frpjump.assistant.chat.PendingConfirmation
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.connections.kindWord

/**
 * The owner's question before a changing call: tool, target names, the full command (monospace, selectable, scrollable),
 * the warning and four answers that wrap at large font. Without a known target ([PendingConfirmation.target] null) the
 * call is undescribed: "For session" is not offered, because there is nothing to bind it to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatConfirmationCard(confirmation: PendingConfirmation, onAnswer: (ConfirmationDecision) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val info = confirmation.info
    val target = if (info == null) stringResource(R.string.confirm_unknown_target)
        else stringResource(R.string.confirm_target, info.profileName, info.connection.device, info.connection.label { kindWord(context, it) })
    val allow = stringResource(R.string.confirm_allow)
    val session = stringResource(R.string.confirm_session)
    val always = stringResource(R.string.confirm_always)
    val decline = stringResource(R.string.confirm_decline)
    val sessionHint = stringResource(R.string.confirm_session_hint)
    val alwaysHint = stringResource(R.string.confirm_always_hint, stringResource(R.string.settings), stringResource(R.string.assistant_title), stringResource(R.string.assistant_tools))
    OutlinedCard(modifier.fillMaxWidth().testTag("chat_confirm_card"), border = BorderStroke(1.dp, AppTheme.colors.accent)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.confirm_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            Text(stringResource(confirmation.tool.title()), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted,
                modifier = Modifier.testTag("chat_confirm_tool"))
            Text(target, modifier = Modifier.testTag("chat_confirm_target"))
            val commandLabel = stringResource(R.string.confirm_command)
            Box(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()).testTag("chat_confirm_command_box")) {
                SelectionContainer {
                    Text(confirmation.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$commandLabel: ${confirmation.command}" }.testTag("chat_confirm_command"))
                }
            }
            Text(stringResource(R.string.confirm_warning), color = AppTheme.colors.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("chat_confirm_warning"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAnswer(ConfirmationDecision.ONCE) }, modifier = Modifier.testTag("chat_confirm_allow")) { Text(allow) }
                if (confirmation.target != null) OutlinedButton(onClick = { onAnswer(ConfirmationDecision.SESSION) },
                    modifier = Modifier.semantics { contentDescription = "$session. $sessionHint" }.testTag("chat_confirm_session")) { Text(session) }
                OutlinedButton(onClick = { onAnswer(ConfirmationDecision.ALWAYS) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.colors.error),
                    modifier = Modifier.semantics { contentDescription = "$always. $alwaysHint" }.testTag("chat_confirm_always")) { Text(always) }
                OutlinedButton(onClick = { onAnswer(ConfirmationDecision.DENY) }, modifier = Modifier.testTag("chat_confirm_decline")) { Text(decline) }
            }
            if (confirmation.target != null) Hint(session, sessionHint, "chat_confirm_session_hint")
            Hint(always, alwaysHint, "chat_confirm_always_hint")
        }
    }
}

@Composable
private fun Hint(label: String, text: String, tag: String) {
    Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$label: ") }; append(text) },
        style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted, modifier = Modifier.testTag(tag))
}

/**
 * The compact mark shown while confirmation requests are off. Tap or long press explains it and offers turning requests back on;
 * there is no banner or strip. Sits between the title and the Tools action of the chat top bar.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ConfirmationsOffIndicator(onTurnOn: () -> Unit, onTools: (() -> Unit)?, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.confirmations_off_description)
    Box(modifier.size(48.dp).combinedClickable(role = Role.Button, onClickLabel = description, onClick = { open = true }, onLongClickLabel = description, onLongClick = { open = true })
        .semantics { contentDescription = description }.testTag("chat_confirmations_off"), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = AppTheme.colors.error)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        text = { Text(stringResource(R.string.confirmations_off_explanation), modifier = Modifier.testTag("chat_confirmations_off_text")) },
        confirmButton = { TextButton(onClick = { open = false; onTurnOn() }, modifier = Modifier.testTag("chat_confirmations_turn_on")) { Text(stringResource(R.string.confirmations_turn_on)) } },
        dismissButton = {
            Row {
                if (onTools != null) TextButton(onClick = { open = false; onTools() }, modifier = Modifier.testTag("chat_confirmations_tools")) { Text(stringResource(R.string.assistant_tools)) }
                TextButton(onClick = { open = false }, modifier = Modifier.testTag("chat_confirmations_close")) { Text(stringResource(R.string.confirmations_close)) }
            }
        },
    )
}
