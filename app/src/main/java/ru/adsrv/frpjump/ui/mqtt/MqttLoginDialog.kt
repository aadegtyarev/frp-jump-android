package ru.adsrv.frpjump.ui.mqtt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.SecretField

/**
 * The broker's login, as SSH's dialog asks it: user and password, kept
 * (sealed, per connection) only with "Remember" once the broker takes it.
 * [user] is filled in from the login refused; [wrong] says it was refused.
 */
@Composable
fun MqttLoginDialog(
    target: String,
    user: String?,
    wrong: Boolean,
    onConnect: (user: String, password: String, remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(user.orEmpty()) }
    var password by remember { mutableStateOf("") }
    // Ticked unless the owner unticks it, as for SSH.
    var remember by rememberSaveable { mutableStateOf(true) }
    val autofill = LocalAutofillManager.current
    // Removed by its screen (the session closed): the fill session ends with it. After commit() a no-op.
    DisposableEffect(autofill) { onDispose { autofill?.cancel() } }
    val connect = {
        if (password.isNotEmpty()) autofill?.commit() else autofill?.cancel()
        onConnect(name, password, remember)
    }
    val dismiss = {
        autofill?.cancel()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.mqtt_login_title, target), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(
                    label = stringResource(R.string.ssh_user),
                    value = name,
                    onValueChange = { name = it.trim() },
                    keyboard = KeyboardType.Ascii,
                    contentType = ContentType.Username,
                )
                SecretField(
                    password,
                    { password = it },
                    stringResource(R.string.ssh_method_password),
                    contentType = ContentType.Password,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text(stringResource(R.string.ssh_remember_password))
                }
                if (wrong) Text(stringResource(R.string.mqtt_login_wrong), fontSize = 13.sp, color = AppTheme.colors.error)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = connect) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
