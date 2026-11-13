package ru.adsrv.frpjump.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
 * The owner's own instructions: a multiline field with a counter, Save (writes and leaves; an empty field saved means no
 * instructions) and Reset (clears only the field, so it can be undone by Back, which discards every unsaved edit).
 * Input is limited as typed: what is inserted loses control characters and invisible direction/tag characters, and is cut so the whole
 * stays within [AssistantOwnerInstructions.MAX_CHARS] while the text around it is kept.
 * [onSave] completes with null on success or a message; the screen never reads the text for anything else.
 */
@Composable
internal fun AssistantOwnerInstructionsScreen(saved: String, onSave: (String, (String?) -> Unit) -> Unit, onBack: () -> Unit) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(saved, TextRange(saved.length))) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }
    fun save() {
        if (busy) return
        busy = true; failure = null
        val complete: (String?) -> Unit = { error ->
            if (active) {
                busy = false; failure = error
                if (error == null) onBack()
            }
        }
        try { onSave(value.text, complete) } catch (error: Exception) { complete(error.message ?: error.javaClass.simpleName) }
    }
    BackHandler { if (!busy) onBack() }
    val length = value.text.length
    val spokenCount = stringResource(R.string.assistant_owner_counter_spoken, length, AssistantOwnerInstructions.MAX_CHARS)
    FormScreen(
        title = stringResource(R.string.assistant_owner_title), action = stringResource(R.string.save), busy = busy,
        actionEnabled = value.text != saved, error = failure, onBack = { if (!busy) onBack() },
        onAction = ::save,
    ) {
        Text(stringResource(R.string.assistant_owner_hint), color = AppTheme.colors.muted)
        Field(stringResource(R.string.assistant_owner_field), value,
            { next ->
                val edit = AssistantOwnerInstructions.limitEdit(value.text, next.text, next.selection.end)
                // As typed (the keyboard's composition kept), unless it had to change.
                value = if (edit.text == next.text) next else TextFieldValue(edit.text, TextRange(edit.cursor))
                failure = null
            },
            Modifier.testTag("assistant_owner_field"), singleLine = false, minLines = 6,
            counter = {
                Text(stringResource(R.string.assistant_owner_counter, length, AssistantOwnerInstructions.MAX_CHARS),
                    // At the cap: a key that adds nothing more is seen to do so.
                    color = if (length >= AssistantOwnerInstructions.MAX_CHARS) AppTheme.colors.error else Color.Unspecified,
                    modifier = Modifier.testTag("assistant_owner_counter").clearAndSetSemantics { contentDescription = spokenCount })
            })
        TextButton(onClick = { value = TextFieldValue(""); failure = null }, enabled = !busy && value.text.isNotEmpty(),
            modifier = Modifier.testTag("assistant_owner_reset")) { Text(stringResource(R.string.assistant_owner_reset)) }
    }
}
