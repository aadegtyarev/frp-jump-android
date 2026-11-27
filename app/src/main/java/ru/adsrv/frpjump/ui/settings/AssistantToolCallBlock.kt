package ru.adsrv.frpjump.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.ui.AppTheme

/** The quiet status line of a running turn (waiting, stopping, summary): small and grey, with the progress mark unless it waits for the owner. */
@Composable
internal fun ChatRunStatus(text: Int, spinning: Boolean) {
    Row(Modifier.fillMaxWidth().testTag("chat_run_status"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (spinning) QuietProgress() else QuietDot()
        Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
    }
}

/** The small progress mark; the system's "remove animations" setting stops it (Compose animations follow the animator duration scale). */
@Composable
private fun QuietProgress() = CircularProgressIndicator(Modifier.size(10.dp), color = AppTheme.colors.muted, strokeWidth = 1.5.dp)

@Composable
private fun QuietDot() = Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
    Box(Modifier.size(4.dp).background(AppTheme.colors.faint, CircleShape))
}
