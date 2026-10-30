package ru.adsrv.frpjump.ui.enroll

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FormCard
import ru.adsrv.frpjump.ui.FormScreen
import ru.adsrv.frpjump.ui.profiles.ProfileColorPicker
import ru.adsrv.frpjump.ui.profiles.ProfileNameField

/**
 * "New profile", as the first screen or from the profile list ([onBack]).
 * [onOtherProfiles] offers the profile list when other profiles exist
 * and there is nothing to go back to.
 */
@Composable
fun EnrollScreen(vm: EnrollViewModel, onEnrolled: () -> Unit, onBack: (() -> Unit)?, onOtherProfiles: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    FormScreen(
        title = stringResource(R.string.new_profile),
        action = stringResource(R.string.enroll_submit),
        busy = state.busy,
        onAction = { vm.submit(onEnrolled) },
        onBack = onBack,
        actionEnabled = state.text.isNotBlank(),
    ) {
        state.notice?.let { Text(it, fontSize = 14.sp, color = AppTheme.colors.ink) }
        InviteCard()
        LineCard(state, vm)
        FormCard(title = stringResource(R.string.enroll_step_name)) {
            ProfileNameField(state.profileName, vm::onProfileName, state.profileNameError, enabled = !state.busy)
            ProfileColorPicker(state.color, vm::onColor, enabled = !state.busy)
        }
        onOtherProfiles?.let {
            TextButton(onClick = it) { Text(stringResource(R.string.other_profiles)) }
        }
    }
}

@Composable
private fun InviteCard() {
    val colors = AppTheme.colors
    FormCard(title = stringResource(R.string.enroll_step_invite)) {
        Text(stringResource(R.string.enroll_step_invite_text), fontSize = 14.sp, color = colors.muted)
        Text(
            stringResource(R.string.enroll_command),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = colors.onCode,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.code, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** The pasted line, with what was understood of it or why it failed right under it. (A QR scan goes above it later.) */
@Composable
private fun LineCard(state: EnrollUiState, vm: EnrollViewModel) {
    val colors = AppTheme.colors
    FormCard(title = stringResource(R.string.enroll_step_paste)) {
        OutlinedTextField(
            value = state.text,
            onValueChange = vm::onText,
            label = { Text(stringResource(R.string.enroll_line_label)) },
            placeholder = { Text(stringResource(R.string.enroll_line_placeholder)) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            minLines = 3,
            enabled = !state.busy,
            isError = state.error != null,
            supportingText = state.error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        // Only what was read from the line: the server itself answers on "Connect phone".
        if (state.error == null) {
            state.host?.let { Text(stringResource(R.string.enroll_line_ok, it), fontSize = 13.sp, color = colors.muted) }
        }
        if (state.needsName) {
            OutlinedTextField(
                value = state.name,
                onValueChange = vm::onName,
                label = { Text(stringResource(R.string.enroll_phone_name)) },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
