package ru.adsrv.frpjump.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Why a list is not (fully) live, above it (Files, the MQTT explorer):
 * [text] and an optional [detail], in the error colours when [error], else
 * the warning ones; a spinner first while [busy]; [action] (Retry, Log in)
 * at the end when there is something that may fix it.
 */
@Composable
fun StatusBanner(
    text: String,
    detail: String? = null,
    error: Boolean = false,
    busy: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(if (error) colors.errorContainer else colors.warningContainer, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ink = if (error) colors.onErrorContainer else colors.onWarningContainer
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = ink, strokeWidth = 2.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, fontSize = 14.sp, color = ink)
            detail?.let { Text(it, fontSize = 12.sp, color = ink) }
        }
        action?.let { TextButton(onClick = onAction) { Text(it, color = ink) } }
    }
}
