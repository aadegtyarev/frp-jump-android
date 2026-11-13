package ru.adsrv.frpjump.ui.ssh

import ru.adsrv.frpjump.ui.AppDialog
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FullscreenExitGlyph
import ru.adsrv.frpjump.ui.FullscreenGlyph
import ru.adsrv.frpjump.ui.ImageGlyph
import ru.adsrv.frpjump.ui.NoticeLayer

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
    onInsertAndEnter: () -> Unit,
    /** The tab is open and has no image on its way: the image button is on. */
    canInsertImage: Boolean = false,
    /** Opens the photo picker; the path then goes straight to the terminal, the draft is not touched. */
    onInsertImage: () -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(sessionId, expanded, landscape) {
        focus.requestFocus()
        keyboard?.show()
    }
    val enabled = canInsert && value.text.isNotBlank()
    val frame = Modifier.border(1.dp, AppTheme.colors.termLine, RoundedCornerShape(12.dp))
    if (landscape && !expanded) {
        // Share one row with the actions: stacking chrome would hide the whole terminal above the IME.
        Row(Modifier.fillMaxWidth().then(frame), verticalAlignment = Alignment.CenterVertically) {
            ComposerField(value, onValueChange, focus, Modifier.weight(1f).heightIn(min = 48.dp, max = 80.dp))
            ComposerActions(false, enabled, onKeys, onExpand, onInsert, onInsertAndEnter, canInsertImage, onInsertImage, Modifier.widthIn(min = 180.dp, max = 320.dp))
        }
    } else {
        Column((if (expanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).then(frame)) {
            ComposerField(
                value, onValueChange, focus,
                if (expanded) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 160.dp),
            )
            ComposerActions(expanded, enabled, onKeys, onExpand, onInsert, onInsertAndEnter, canInsertImage, onInsertImage, Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerActions(
    expanded: Boolean,
    enabled: Boolean,
    onKeys: () -> Unit,
    onExpand: () -> Unit,
    onInsert: () -> Unit,
    onInsertAndEnter: () -> Unit,
    canInsertImage: Boolean,
    onInsertImage: () -> Unit,
    modifier: Modifier,
) {
    val colors = AppTheme.colors
    val keysLabel = stringResource(R.string.terminal_return_keys)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onKeys, modifier = Modifier.testTag("terminal_mode_keys").semantics { contentDescription = keysLabel }) {
            Text(">_", color = colors.onTerm)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onInsertImage, enabled = canInsertImage, modifier = Modifier.testTag("terminal_text_image")) {
            val tint = if (canInsertImage) colors.onTerm else colors.termMuted
            Icon(ImageGlyph, contentDescription = stringResource(R.string.terminal_image_insert), tint = tint)
        }
        IconButton(onClick = onExpand, modifier = Modifier.testTag("terminal_text_expand")) {
            Icon(
                if (expanded) FullscreenExitGlyph else FullscreenGlyph,
                contentDescription = stringResource(if (expanded) R.string.terminal_text_collapse else R.string.terminal_text_expand),
                tint = colors.onTerm,
            )
        }
        val insertLabel = stringResource(R.string.terminal_text_insert)
        Box(
            Modifier.testTag("terminal_text_insert").heightIn(min = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .combinedClickable(
                    enabled = enabled, role = Role.Button,
                    onClickLabel = insertLabel, onClick = onInsert,
                    onLongClickLabel = stringResource(R.string.terminal_text_insert_enter),
                    onLongClick = onInsertAndEnter,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val tint = if (enabled) colors.onTerm else colors.termMuted
                Text(insertLabel, color = tint, fontSize = 14.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                Canvas(Modifier.align(Alignment.Top).padding(start = 3.dp).size(11.dp)) {
                    val u = size.width / 10f
                    drawLine(tint, Offset(8*u, u), Offset(8*u, 6*u), 1.2f*u)
                    drawLine(tint, Offset(8*u, 6*u), Offset(u, 6*u), 1.2f*u)
                    drawLine(tint, Offset(u, 6*u), Offset(4*u, 3*u), 1.2f*u)
                    drawLine(tint, Offset(u, 6*u), Offset(4*u, 9*u), 1.2f*u)
                }
            }
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
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedPlaceholderColor = colors.termMuted,
            unfocusedPlaceholderColor = colors.termMuted,
        ),
    )
}

/** The same draft and selection, not a second editor buffer or a saved-state value. */
@Composable
fun FullscreenTerminalText(onCollapse: () -> Unit, content: @Composable () -> Unit) {
    AppDialog(
        onDismissRequest = onCollapse,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(onBack = onCollapse)
        // The dialog is its own window: the activity's notice host is under it, so it carries one (image failures and "inserted").
        NoticeLayer {
            Column(
                Modifier.fillMaxSize().background(AppTheme.colors.termBar).safeDrawingPadding().imePadding().padding(12.dp).testTag("terminal_text_fullscreen"),
            ) { content() }
        }
    }
}
