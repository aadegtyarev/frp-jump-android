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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    /** To focus the field when it shows. */
    focusRequester: FocusRequester? = null,
) {
    val colors = AppTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            isError = problem != null,
            placeholder = placeholder?.let { { Text(it, color = colors.muted) } },
            supportingText = (problem ?: note)?.let { { Text(it) } },
            textStyle = if (mono) TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp) else TextStyle(fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = colors.outline,
                focusedBorderColor = colors.accent,
                unfocusedContainerColor = colors.card,
                focusedContainerColor = colors.card,
                disabledContainerColor = colors.tile,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        )
    }
}

/** Two or three choices side by side, as the mockup's Web / SSH / TCP switch. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: @Composable (T) -> String, enabled: Boolean = true, onPick: (T) -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.tile, RoundedCornerShape(14.dp))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(10.dp)) else Modifier)
                    .background(if (on) colors.card else colors.tile, RoundedCornerShape(10.dp))
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = { onPick(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = (if (on) colors.accent else colors.muted).let { if (enabled || on) it else it.copy(alpha = 0.5f) },
                )
            }
        }
    }
}

/** The main button of a sheet: the accent, a spinner on it while [busy]. */
@Composable
fun MainButton(text: String, busy: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = AppTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = if (busy) colors.accent else colors.tile,
            disabledContentColor = if (busy) colors.onAccent else colors.muted,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), color = colors.onAccent, strokeWidth = 2.dp)
        } else {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A sheet as the connection sheet: a bold title, an optional line under it, the content scrolling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(title: String, subtitle: String? = null, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.card,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                subtitle?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted) }
            }
            content()
        }
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
