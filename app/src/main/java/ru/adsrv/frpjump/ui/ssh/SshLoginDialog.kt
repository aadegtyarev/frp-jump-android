package ru.adsrv.frpjump.ui.ssh

import ru.adsrv.frpjump.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.SecretField
import ru.adsrv.frpjump.ui.Segmented
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * User name (remembered per device and port), then this phone's key or a
 * password. A password is kept only if "Remember" is ticked; an empty
 * field with a remembered password uses that one. [passwordOnly] asks for
 * a password just once ("Use key login"): no method, nothing to remember,
 * [note] says what for, [problem] what went wrong the last time.
 */
@Composable
fun SshLoginDialog(
    target: String,
    initial: SshLogin,
    hasRememberedPassword: (user: String) -> Boolean,
    onConnect: (user: String, method: SshAuthMethod, password: String, remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    passwordOnly: Boolean = false,
    note: String? = null,
    problem: String? = null,
    confirmLabel: String? = null,
) {
    var user by rememberSaveable { mutableStateOf(initial.user) }
    var method by rememberSaveable { mutableStateOf(if (passwordOnly) SshAuthMethod.PASSWORD else initial.method) }
    var password by remember { mutableStateOf("") }
    val remembered = method == SshAuthMethod.PASSWORD && hasRememberedPassword(user)
    // Ticked unless the owner unticks it: the password is sealed on the phone, and typing it every time is what was asked not to be.
    var remember by rememberSaveable { mutableStateOf(true) }
    val ready = user.isNotBlank() && (method == SshAuthMethod.KEY || password.isNotEmpty() || remembered)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: stringResource(R.string.ssh_login_title, target), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(
                    label = stringResource(R.string.ssh_user),
                    value = user,
                    onValueChange = { user = it.trim() },
                    keyboard = KeyboardType.Ascii,
                )
                if (!passwordOnly) {
                    Segmented(
                        options = listOf(SshAuthMethod.KEY, SshAuthMethod.PASSWORD),
                        selected = method,
                        label = { stringResource(if (it == SshAuthMethod.KEY) R.string.ssh_method_key else R.string.ssh_method_password) },
                        onPick = { method = it },
                    )
                }
                note?.let { Text(it, fontSize = 13.sp, color = AppTheme.colors.muted) }
                if (method == SshAuthMethod.KEY) {
                    Text(
                        stringResource(R.string.ssh_login_key_hint, stringResource(R.string.ssh_public_key)),
                        fontSize = 13.sp,
                        color = AppTheme.colors.muted,
                    )
                } else {
                    SecretField(
                        password,
                        { password = it },
                        stringResource(if (remembered) R.string.ssh_password_remembered else R.string.ssh_method_password),
                    )
                    if (!passwordOnly) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = remember, onCheckedChange = { remember = it })
                            Text(stringResource(R.string.ssh_remember_password))
                        }
                    }
                }
                problem?.let { Text(it, fontSize = 13.sp, color = AppTheme.colors.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { onConnect(user, method, password, remember) }) { Text(confirmLabel ?: stringResource(R.string.connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
