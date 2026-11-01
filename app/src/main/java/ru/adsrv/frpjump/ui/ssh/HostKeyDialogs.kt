package ru.adsrv.frpjump.ui.ssh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ssh.PinnedHostKey

/** First connection to [target] ("device:port"): show its host key, trust it or not. Used by the terminal and the file screen. */
@Composable
fun HostKeyTrustDialog(target: String, presented: PinnedHostKey, onTrust: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.host_key_trust_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.host_key_first, target))
                KeyText(presented)
                Text(
                    stringResource(R.string.host_key_check, "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text(stringResource(R.string.cert_trust)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/** [target] shows another host key than the pinned one: blocked until the owner forgets the old key. */
@Composable
fun HostKeyChangedDialog(
    target: String,
    pinned: PinnedHostKey,
    presented: PinnedHostKey,
    onForget: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.host_key_changed_title), color = MaterialTheme.colorScheme.error) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.host_key_changed_text, target))
                Text(stringResource(R.string.host_key_trusted_before), style = MaterialTheme.typography.labelMedium)
                KeyText(pinned)
                Text(stringResource(R.string.host_key_shown_now), style = MaterialTheme.typography.labelMedium)
                KeyText(presented)
                Text(
                    stringResource(R.string.host_key_forget_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onForget) { Text(stringResource(R.string.host_key_forget)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun KeyText(key: PinnedHostKey) {
    Text("${key.type}\n${key.fingerprint}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}
