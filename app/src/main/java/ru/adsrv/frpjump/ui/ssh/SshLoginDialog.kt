package ru.adsrv.frpjump.ui.ssh

import ru.adsrv.frpjump.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.SshLogin
import ru.adsrv.frpjump.ui.SecretField

/**
 * User name (remembered per device and port), then this phone's key or a
 * password. A password is kept only if "Remember" is ticked; an empty
 * field with a remembered password uses that one.
 */
@Composable
fun SshLoginDialog(
    target: String,
    initial: SshLogin,
    hasRememberedPassword: (user: String) -> Boolean,
    onConnect: (user: String, method: SshAuthMethod, password: String, remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var user by rememberSaveable { mutableStateOf(initial.user) }
    var method by rememberSaveable { mutableStateOf(initial.method) }
    var password by remember { mutableStateOf("") }
    val remembered = method == SshAuthMethod.PASSWORD && hasRememberedPassword(user)
    var remember by rememberSaveable(user) { mutableStateOf(hasRememberedPassword(user)) }
    val ready = user.isNotBlank() && (method == SshAuthMethod.KEY || password.isNotEmpty() || remembered)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_login_title, target)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it.trim() },
                    label = { Text(stringResource(R.string.ssh_user)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = method == SshAuthMethod.KEY, onClick = { method = SshAuthMethod.KEY }, label = { Text(stringResource(R.string.ssh_method_key)) })
                    FilterChip(
                        selected = method == SshAuthMethod.PASSWORD,
                        onClick = { method = SshAuthMethod.PASSWORD },
                        label = { Text(stringResource(R.string.ssh_method_password)) },
                    )
                }
                if (method == SshAuthMethod.KEY) {
                    Text(
                        stringResource(R.string.ssh_login_key_hint, stringResource(R.string.ssh_public_key)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    SecretField(
                        password,
                        { password = it },
                        stringResource(if (remembered) R.string.ssh_password_remembered else R.string.ssh_method_password),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = remember, onCheckedChange = { remember = it })
                        Text(stringResource(R.string.ssh_remember_password))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { onConnect(user, method, password, remember) }) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
