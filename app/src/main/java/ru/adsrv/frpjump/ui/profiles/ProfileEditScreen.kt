package ru.adsrv.frpjump.ui.profiles

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.FormCard
import ru.adsrv.frpjump.ui.FormScreen

/** A profile's name and colour. */
@Composable
fun ProfileEditScreen(vm: ProfileEditViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    FormScreen(
        title = stringResource(R.string.name_and_color),
        action = stringResource(R.string.save),
        busy = state.saving,
        onAction = { vm.save(onBack) },
        onBack = onBack,
        error = state.error,
    ) {
        FormCard(title = null) {
            ProfileNameField(state.name, vm::onName, state.nameError, enabled = !state.saving)
            ProfileColorPicker(state.color, vm::onColor, enabled = !state.saving)
        }
    }
}

/** The profile's name, its problem right under it. */
@Composable
fun ProfileNameField(value: String, onChange: (String) -> Unit, error: String?, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.profile_name)) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
}
