package ru.adsrv.frpjump.ui.ssh

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FullscreenExitGlyph
import ru.adsrv.frpjump.ui.FullscreenGlyph

/** A real IME editor: its composing text never passes through TerminalInput. */
@Composable
fun TerminalTextComposer(
    sessionId: Int,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focus: FocusRequester,
    canInsert: Boolean,
    onInsert: () -> Unit,
    onExpand: () -> Unit,
    expanded: Boolean = false,
    onKeys: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(sessionId, expanded, landscape) {
        focus.requestFocus()
        keyboard?.show()
    }
    val enabled = canInsert && value.text.isNotBlank()
    if (landscape && !expanded) {
        // Share one row with the actions: stacking chrome would hide the whole terminal above the IME.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ComposerField(value, onValueChange, focus, Modifier.weight(1f).heightIn(min = 48.dp, max = 80.dp))
            ComposerActions(false, enabled, onKeys, onExpand, onInsert, Modifier.widthIn(min = 180.dp, max = 320.dp))
        }
    } else {
        Column(if (expanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            if (expanded) Text(stringResource(R.string.terminal_text_draft), color = AppTheme.colors.termMuted, fontSize = 13.sp)
            ComposerActions(expanded, enabled, onKeys, onExpand, onInsert, Modifier.fillMaxWidth())
            ComposerField(
                value, onValueChange, focus,
                if (expanded) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 160.dp),
            )
        }
    }
}

@Composable
private fun ComposerActions(
    expanded: Boolean,
    enabled: Boolean,
    onKeys: () -> Unit,
    onExpand: () -> Unit,
    onInsert: () -> Unit,
    modifier: Modifier,
) {
    val colors = AppTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onKeys, modifier = Modifier.testTag("terminal_mode_keys")) {
            Text(stringResource(R.string.terminal_input_keys), color = colors.onTerm)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onExpand, modifier = Modifier.testTag("terminal_text_expand")) {
            Icon(
                if (expanded) FullscreenExitGlyph else FullscreenGlyph,
                contentDescription = stringResource(if (expanded) R.string.terminal_text_collapse else R.string.terminal_text_expand),
                tint = colors.onTerm,
            )
        }
        TextButton(onClick = onInsert, enabled = enabled, modifier = Modifier.testTag("terminal_text_insert")) {
            Text(stringResource(R.string.terminal_text_insert), color = if (enabled) colors.onTerm else colors.termMuted)
        }
    }
}

@Composable
private fun ComposerField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, focus: FocusRequester, modifier: Modifier) {
    val colors = AppTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.focusRequester(focus).testTag("terminal_text_editor"),
        placeholder = { Text(stringResource(R.string.terminal_text_draft)) },
        textStyle = TextStyle(fontSize = 16.sp, color = colors.onTerm),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = true, keyboardType = KeyboardType.Text, imeAction = ImeAction.Default),
        singleLine = false,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.onTerm,
            unfocusedTextColor = colors.onTerm,
            cursorColor = colors.onTerm,
            focusedBorderColor = colors.termMuted,
            unfocusedBorderColor = colors.termLine,
            focusedPlaceholderColor = colors.termMuted,
            unfocusedPlaceholderColor = colors.termMuted,
        ),
    )
}

/** The same draft and selection, not a second editor buffer or a saved-state value. */
@Composable
fun FullscreenTerminalText(onCollapse: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onCollapse,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(onBack = onCollapse)
        Column(
            Modifier.fillMaxSize().background(AppTheme.colors.termBar).safeDrawingPadding().imePadding().padding(12.dp).testTag("terminal_text_fullscreen"),
        ) { content() }
    }
}
