package ru.adsrv.frpjump.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, onForgotten: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // A picked key file waits here for its passphrase; never saved across recreation.
    var pickedKey by remember { mutableStateOf<String?>(null) }
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pickedKey = readSmallText(context, uri)
        if (uri != null && pickedKey == null) Toast.makeText(context, "Cannot read that file.", Toast.LENGTH_SHORT).show()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ListItem(headlineContent = { Text("Server") }, supportingContent = { Text(state.serverUrl) })
            ListItem(headlineContent = { Text("This phone") }, supportingContent = { Text(state.deviceName) })
            ListItem(
                headlineContent = { Text("SSH public key") },
                supportingContent = {
                    Column {
                        Text(
                            "Add this line to ~/.ssh/authorized_keys on a device to log in with this phone's key.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        SelectionContainer {
                            Text(
                                state.sshPublicKey ?: state.sshPublicKeyError ?: "…",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                },
            )
            state.sshPublicKey?.let { key ->
                Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(key)) }) { Text("Copy") }
                    TextButton(onClick = { shareText(context, key) }) { Text("Share") }
                }
            }
            ListItem(
                headlineContent = { Text("Imported private key") },
                supportingContent = {
                    Column {
                        Text(
                            state.importedKey?.let { "Tried before this phone's own key:" }
                                ?: "Optional: an OpenSSH private key file you already use, tried before this phone's key.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        state.importedKey?.let {
                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        state.importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                },
            )
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = {
                    vm.clearImportError()
                    pickKey.launch(arrayOf("*/*"))
                }) { Text(if (state.importedKey == null) "Import private key" else "Replace") }
                if (state.importedKey != null) TextButton(onClick = vm::removeImportedKey) { Text("Remove") }
            }
            OutlinedButton(
                onClick = { confirm = true },
                enabled = !state.busy,
                modifier = Modifier.padding(16.dp),
            ) {
                Text("Forget this phone", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    pickedKey?.let { key ->
        PassphraseDialog(
            onImport = { passphrase ->
                pickedKey = null
                vm.importKey(key, passphrase)
            },
            onDismiss = { pickedKey = null },
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Forget this phone?") },
            text = { Text("\"${state.deviceName}\" is deleted on the server and its credentials are wiped here.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.forget(onForgotten)
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PassphraseDialog(onImport: (String) -> Unit, onDismiss: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Key passphrase") },
        text = {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase (empty if none)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = { TextButton(onClick = { onImport(passphrase) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A key file is a few KiB; anything larger is not one. */
private fun readSmallText(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readNBytesCompat(MAX_KEY_FILE + 1)
        if (bytes.size > MAX_KEY_FILE) null else String(bytes, Charsets.UTF_8)
    }
} catch (e: Exception) {
    null
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "SSH public key"))
}

private const val MAX_KEY_FILE = 64 * 1024
