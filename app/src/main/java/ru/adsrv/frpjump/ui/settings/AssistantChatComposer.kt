package ru.adsrv.frpjump.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.chat.ChatState
import ru.adsrv.frpjump.assistant.chat.fieldEnabled
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.ArrowUpGlyph
import ru.adsrv.frpjump.ui.StopGlyph

/**
 * The chat's message area (Р61): one rounded field on the card colour with the divider outline, growing with the text up to [maxLines].
 * The action sits inside it at the end: an arrow-up Send (faint and off while there is nothing to send) that becomes a filled-square Stop
 * while the assistant works ("Stopping…" and off once Stop was pressed). While a question to the owner is open the field is hidden and
 * Stop stands alone at the end. Both buttons keep their 48 dp touch target and their TalkBack labels.
 */
@Composable
internal fun ChatComposer(
    field: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, state: ChatState, asking: Boolean, canSend: Boolean,
    onSend: () -> Unit, onStop: () -> Unit, maxLines: Int, modifier: Modifier = Modifier,
) {
    if (asking) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ComposerAction(state, canSend, onSend, onStop, Modifier.border(1.dp, AppTheme.colors.divider, CircleShape).background(AppTheme.colors.card, CircleShape)) }
        return
    }
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(26.dp)
    OutlinedTextField(
        value = field, onValueChange = onValueChange, enabled = state.fieldEnabled, shape = shape,
        placeholder = { Text(stringResource(R.string.chat_message)) },
        trailingIcon = { ComposerAction(state, canSend, onSend, onStop) },
        minLines = 1, maxLines = maxLines, modifier = modifier.fillMaxWidth().testTag("chat_input"),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.card, unfocusedContainerColor = colors.card, disabledContainerColor = colors.card,
            focusedBorderColor = colors.muted, unfocusedBorderColor = colors.divider, disabledBorderColor = colors.divider,
            focusedTextColor = colors.ink, unfocusedTextColor = colors.ink, disabledTextColor = colors.ink,
            focusedPlaceholderColor = colors.faint, unfocusedPlaceholderColor = colors.faint, disabledPlaceholderColor = colors.faint,
        ),
    )
}

/** Stop while the assistant works, else Send; one 48 dp button either way. */
@Composable
private fun ComposerAction(state: ChatState, canSend: Boolean, onSend: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    if (state.busy) {
        val label = stringResource(if (state.stopping) R.string.chat_stopping else R.string.chat_stop)
        IconButton(onClick = onStop, enabled = !state.stopping, modifier = modifier.testTag("chat_stop").semantics { contentDescription = label },
            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.ink, disabledContentColor = colors.faint)) {
            Icon(StopGlyph, contentDescription = null)
        }
    } else {
        val label = stringResource(R.string.chat_send)
        IconButton(onClick = onSend, enabled = canSend, modifier = modifier.testTag("chat_send").semantics { contentDescription = label },
            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.ink, disabledContentColor = colors.faint)) {
            Icon(ArrowUpGlyph, contentDescription = null)
        }
    }
}
