package ru.adsrv.frpjump.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.R
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A labelled field as the mockups draw them: the label above, the problem below. */
@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    mono: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    problem: String? = null,
    note: String? = null,
    singleLine: Boolean = true,
    /** Lines shown at least when not [singleLine]. */
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    /** To focus the field when it shows. */
    focusRequester: FocusRequester? = null,
    /** What a password manager may fill in here (a user name); null: nothing. */
    contentType: ContentType? = null,
) {
    val colors = AppTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            isError = problem != null,
            placeholder = placeholder?.let { { Text(it, color = colors.muted) } },
            supportingText = (problem ?: note)?.let { { Text(it) } },
            textStyle = fieldTextStyle(mono),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .then(if (contentType != null) Modifier.semantics { this.contentType = contentType } else Modifier),
        )
    }
}

/**
 * [Field] holding a [TextFieldValue], for a field that sets where the
 * cursor starts or what is selected (a prefilled path, cursor at its end; a
 * prefilled value, selected whole). [onDone], when given, is the keyboard's
 * action key (Go) of a single-line field.
 */
@Composable
fun Field(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    problem: String? = null,
    note: String? = null,
    focusRequester: FocusRequester? = null,
    singleLine: Boolean = true,
    /** Lines shown at least when not [singleLine]. */
    minLines: Int = 1,
    onDone: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            isError = problem != null,
            supportingText = (problem ?: note)?.let { { Text(it) } },
            textStyle = fieldTextStyle(mono),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboard,
                autoCorrectEnabled = false,
                imeAction = if (onDone != null && singleLine) ImeAction.Go else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onGo = { onDone?.invoke() }),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        )
    }
}

private fun fieldTextStyle(mono: Boolean): TextStyle =
    if (mono) TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp) else TextStyle(fontSize = 16.sp)

@Composable
private fun fieldColors(): TextFieldColors {
    val colors = AppTheme.colors
    return OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = colors.outline,
        focusedBorderColor = colors.accent,
        unfocusedContainerColor = colors.card,
        focusedContainerColor = colors.card,
        disabledContainerColor = colors.tile,
    )
}

/**
 * Two or three choices side by side, as the mockup's Web / SSH / TCP
 * switch; [compact] draws it small and only as wide as its choices, for a
 * secondary switch (a value's view).
 */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: @Composable (T) -> String, enabled: Boolean = true, compact: Boolean = false, onPick: (T) -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            // Options share one height: a label wrapped at a large font size makes all of them taller.
            .height(IntrinsicSize.Min)
            .background(colors.tile, RoundedCornerShape(14.dp))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier
                    .then(if (compact) Modifier.widthIn(min = 64.dp) else Modifier.weight(1f))
                    .heightIn(min = if (compact) 32.dp else 40.dp)
                    .fillMaxHeight()
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(10.dp)) else Modifier)
                    .background(if (on) colors.card else colors.tile, RoundedCornerShape(10.dp))
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = { onPick(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    fontSize = if (compact) 13.sp else 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = if (compact) Modifier.padding(horizontal = 12.dp) else Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    fontWeight = FontWeight.SemiBold,
                    color = (if (on) colors.accent else colors.muted).let { if (enabled || on) it else it.copy(alpha = 0.5f) },
                )
            }
        }
    }
}

/** The main button of a sheet: the accent, a spinner on it while [busy]; [error] in the error colours (an answer that failed shown on it). */
@Composable
fun MainButton(
    text: String,
    busy: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    error: Boolean = false,
    /** Its text is news (an answer shown on it): read out when it changes, and wrapped rather than cut. */
    announce: Boolean = false,
) {
    val colors = AppTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (error) colors.errorContainer else colors.accent,
            contentColor = if (error) colors.onErrorContainer else colors.onAccent,
            disabledContainerColor = if (busy) colors.accent else colors.tile,
            disabledContentColor = if (busy) colors.onAccent else colors.muted,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), color = colors.onAccent, strokeWidth = 2.dp)
        } else {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

/** A sheet as the connection sheet: a bold title, an optional line under it, the content scrolling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(title: String, subtitle: String? = null, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = AppTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = colors.card,
    ) {
        // A message posted while the sheet is open shows on it, not under its scrim.
        NoticeLayer {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SheetHeader(sheet, onDismiss) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        subtitle?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted) }
                    }
                }
                content()
            }
        }
    }
}

/**
 * A sheet's header: [title] (the name, wrapping as it needs) and, on the
 * right of its first line, the close button every sheet has in the same
 * place ([SheetClose]). The button is drawn over the sheet's side padding,
 * so the header is as tall as the title and nothing under it moves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHeader(
    sheet: SheetState,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    title: @Composable RowScope.() -> Unit,
) {
    Row(verticalAlignment = verticalAlignment) {
        Row(Modifier.weight(1f), verticalAlignment = verticalAlignment, horizontalArrangement = Arrangement.spacedBy(14.dp)) { title() }
        // Its icon lines up with the content's right edge; on a top-aligned title it sits on the first line.
        Box(Modifier.width(36.dp).height(if (verticalAlignment == Alignment.Top) 28.dp else 48.dp), contentAlignment = Alignment.CenterEnd) {
            SheetClose(sheet, onDismiss, enabled, Modifier.offset(x = 12.dp).wrapContentSize(unbounded = true))
        }
    }
}

/**
 * ✕ that closes a sheet: it slides away, then [onDismiss] runs, as a
 * swipe down or a tap outside would. 48 dp, read out as "Close".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetClose(sheet: SheetState, onDismiss: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    IconButton(
        onClick = {
            // Already sliding away: one dismiss is enough.
            if (sheet.targetValue != SheetValue.Hidden) {
                // Dismissed only once it is really gone, not when the slide was cut short.
                scope.launch { sheet.hide() }.invokeOnCompletion { if (!sheet.isVisible) onDismiss() }
            }
        },
        enabled = enabled,
        modifier = modifier.size(48.dp),
    ) {
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.close),
            tint = AppTheme.colors.muted.copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

/** One of a few choices in a sheet: picking one applies it and closes the sheet. */
@Composable
fun <T> ChoiceSheet(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    AppSheet(title = title, onDismiss = onDismiss) {
        Column(Modifier.selectableGroup()) {
            options.forEach { option ->
                val on = option == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .background(if (on) colors.accentContainer else colors.card, RoundedCornerShape(12.dp))
                        .selectable(selected = on, role = Role.RadioButton) {
                            onPick(option)
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label(option),
                        fontSize = 16.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) colors.onAccentContainer else colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onAccentContainer)
                }
            }
        }
        footer?.invoke()
    }
}
