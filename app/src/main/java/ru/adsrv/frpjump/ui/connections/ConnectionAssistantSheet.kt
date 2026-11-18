package ru.adsrv.frpjump.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ssh.isolateName
import ru.adsrv.frpjump.ui.AppSheet
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.shortDate

/**
 * What the assistant may do here without a question, as the owner chose it: one line per lifetime that exists, the password promise, and "Ask again"
 * (what Forget was: both lifetimes at once). The caller composes it only while [mark] says a grant exists, so it leaves with the grant.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionAssistantSheet(name: String, mark: AssistantMark, onAskAgain: () -> Unit, onClose: () -> Unit) {
    val colors = AppTheme.colors
    AppSheet(title = stringResource(R.string.connection_assistant_sheet_title, isolateName(name)), onDismiss = onClose) {
        if (mark.session) {
            Text(
                stringResource(if (mark.sessionBoth) R.string.connection_assistant_sheet_session_both else R.string.connection_assistant_sheet_session),
                fontSize = 15.sp,
                modifier = Modifier.testTag("connection_assistant_sheet_session"),
            )
        }
        mark.rememberedAt?.let { at ->
            Text(
                stringResource(
                    if (mark.rememberedBoth) R.string.connection_assistant_sheet_connection_both else R.string.connection_assistant_sheet_connection,
                    // An abbreviated month ends in a dot, and the sentence goes on with its own.
                    shortDate(java.time.Instant.ofEpochMilli(at)).trimEnd('.'),
                ),
                fontSize = 15.sp,
                modifier = Modifier.testTag("connection_assistant_sheet_connection"),
            )
        }
        Text(stringResource(R.string.connection_assistant_sheet_passwords), fontSize = 15.sp, color = colors.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SheetButton(stringResource(R.string.connection_assistant_ask_again), primary = true, onClick = onAskAgain, modifier = Modifier.testTag("connection_assistant_ask_again"))
            SheetButton(stringResource(R.string.close), primary = false, onClick = onClose, modifier = Modifier.testTag("connection_assistant_close"))
        }
    }
}

/** A button 32 dp tall (more as the font grows); Material's surface gives it the 48 dp touch target. */
@Composable
private fun SheetButton(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (primary) colors.accent else Color.Transparent,
        contentColor = if (primary) colors.onAccent else colors.accent,
        border = if (primary) null else BorderStroke(1.dp, colors.outline),
        modifier = modifier,
    ) {
        Box(Modifier.heightIn(min = 32.dp).padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}
