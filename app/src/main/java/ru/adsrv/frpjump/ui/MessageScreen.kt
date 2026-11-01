package ru.adsrv.frpjump.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A full-screen explanation with one way forward, in the look of the forms: a title, a card, the main button. */
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
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 20.dp))
        notice?.let {
            Surface(color = colors.accentContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Text(it, fontSize = 14.sp, color = colors.onAccentContainer, modifier = Modifier.padding(14.dp))
            }
        }
        Surface(color = colors.card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Text(text, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(16.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAction,
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.strong,
                    contentColor = colors.onStrong,
                    disabledContainerColor = colors.strong,
                    disabledContentColor = colors.onStrong,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = colors.onStrong, strokeWidth = 2.dp)
                } else {
                    Text(action, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            error?.let { Text(it, color = colors.error, fontSize = 14.sp) }
            secondary?.let {
                TextButton(onClick = onSecondary, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(it) }
            }
        }
    }
}
