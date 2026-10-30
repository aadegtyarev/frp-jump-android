package ru.adsrv.frpjump.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A full-screen explanation with one way forward. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(
    title: String,
    text: String,
    action: String,
    /** A one-time message from the screen before, above the explanation. */
    notice: String? = null,
    busy: Boolean = false,
    /** Why the last [action] failed, shown below it. */
    error: String? = null,
    /** A second way, e.g. to another profile; none when null. */
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    onAction: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onAction, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(action)
            }
            secondary?.let { TextButton(onClick = onSecondary, enabled = !busy) { Text(it) } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
