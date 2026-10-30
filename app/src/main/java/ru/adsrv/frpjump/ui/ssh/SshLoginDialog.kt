package ru.adsrv.frpjump.ui.ssh

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.SshLogin

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
        title = { Text("SSH to $target") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it.trim() },
                    label = { Text("User") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = method == SshAuthMethod.KEY, onClick = { method = SshAuthMethod.KEY }, label = { Text("Key") })
                    FilterChip(
                        selected = method == SshAuthMethod.PASSWORD,
                        onClick = { method = SshAuthMethod.PASSWORD },
                        label = { Text("Password") },
                    )
                }
                if (method == SshAuthMethod.KEY) {
                    Text(
                        "Uses this phone's key (Settings → SSH public key); the device must have it in ~/.ssh/authorized_keys.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (remembered) "Password (remembered)" else "Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = remember, onCheckedChange = { remember = it })
                        Text("Remember on this phone")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { onConnect(user, method, password, remember) }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
