package ru.adsrv.frpjump.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.settings.AssistantOwnerInstructions
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.FormScreen

/** The row in Settings › LLM assistant; its hint says only whether instructions are set, never the text. */
@Composable
internal fun AssistantOwnerInstructionsEntry(saved: String, onClick: () -> Unit) {
    SettingRow(tile = { GlyphTile("✎") }, label = stringResource(R.string.assistant_owner_title),
        hint = stringResource(if (saved.isBlank()) R.string.assistant_owner_not_set else R.string.assistant_owner_set),
        modifier = Modifier.testTag("assistant_owner_entry"), onClick = onClick) { Chevron() }
}

/**
 * The owner's own instructions: a multiline field with a counter, Save (writes and leaves) and Reset (clears the saved text at once).
 * Input is normalized as typed (no control characters but a line break, at most [AssistantOwnerInstructions.MAX_CHARS]).
 * [onSave] completes with null on success or a message; the screen never reads the text for anything else.
 */
@Composable
internal fun AssistantOwnerInstructionsScreen(saved: String, onSave: (String, (String?) -> Unit) -> Unit, onBack: () -> Unit) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(saved, TextRange(saved.length))) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }
    fun write(text: String, leave: Boolean) {
        if (busy) return
        busy = true; failure = null
        val complete: (String?) -> Unit = { error ->
            if (active) {
                busy = false; failure = error
                if (error == null) { if (leave) onBack() else value = TextFieldValue("") }
            }
        }
        try { onSave(text, complete) } catch (error: Exception) { complete(error.message ?: error.javaClass.simpleName) }
    }
    BackHandler(enabled = !busy, onBack = onBack)
    FormScreen(
        title = stringResource(R.string.assistant_owner_title), action = stringResource(R.string.save), busy = busy,
        actionEnabled = value.text != saved, error = failure, onBack = { if (!busy) onBack() },
        onAction = { write(value.text, leave = true) },
    ) {
        Text(stringResource(R.string.assistant_owner_hint), color = AppTheme.colors.muted)
        Field(stringResource(R.string.assistant_owner_field), value,
            { next ->
                val text = AssistantOwnerInstructions.normalize(next.text)
                value = if (text == next.text) next else TextFieldValue(text, TextRange(minOf(next.selection.end, text.length)))
                failure = null
            },
            Modifier.testTag("assistant_owner_field"), singleLine = false, minLines = 6,
            counter = {
                Text(stringResource(R.string.assistant_owner_counter, value.text.length, AssistantOwnerInstructions.MAX_CHARS),
                    modifier = Modifier.testTag("assistant_owner_counter"))
            })
        TextButton(onClick = { write("", leave = false) }, enabled = !busy && (value.text.isNotEmpty() || saved.isNotEmpty()),
            modifier = Modifier.testTag("assistant_owner_reset")) { Text(stringResource(R.string.assistant_owner_reset)) }
    }
}
