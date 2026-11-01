package ru.adsrv.frpjump.ui.ssh

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ssh.PastePlan
import ru.adsrv.frpjump.ssh.joinLines
import ru.adsrv.frpjump.ui.AppSheet
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.MainButton

/**
 * Several lines to paste and no bracketed paste on the remote side: each line
 * would run as it arrives. Paste them so, join them into one line (trimmed,
 * blank ones dropped, a space between), or leave it.
 */
@Composable
fun PasteSheet(ask: PastePlan.Ask, onPaste: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    AppSheet(
        title = pluralStringResource(R.plurals.terminal_paste_lines_title, ask.lines, ask.lines),
        subtitle = stringResource(R.string.terminal_paste_lines_text),
        onDismiss = onDismiss,
    ) {
        Column {
            MainButton(
                text = stringResource(R.string.terminal_paste_run),
                busy = false,
                onClick = {
                    onPaste(ask.text)
                    onDismiss()
                },
            )
            TextButton(
                onClick = {
                    onPaste(joinLines(ask.text))
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.terminal_paste_join), fontSize = 16.sp) }
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.muted),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.cancel), fontSize = 16.sp) }
        }
    }
}
