package ru.adsrv.frpjump.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
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
        mark.sessionTools?.let { tools ->
            Text(
                stringResource(sessionLineRes(coverOf(tools))),
                fontSize = 15.sp,
                modifier = Modifier.testTag("connection_assistant_sheet_session"),
            )
        }
        mark.rememberedAt?.let { at ->
            Text(
                // An abbreviated month ends in a dot, and the sentence goes on with its own.
                stringResource(connectionLineRes(coverOf(mark.rememberedTools)), shortDate(java.time.Instant.ofEpochMilli(at)).trimEnd('.')),
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

/**
 * A button 32 dp tall as drawn (more as the font grows), with a touch target of 44 dp at least around it: the click is on the outer box, so the
 * surface inside keeps its own size (Material's own minimum size would draw the button 48 dp tall).
 */
@Composable
private fun SheetButton(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Box(
        modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = if (primary) colors.accent else Color.Transparent,
            contentColor = if (primary) colors.onAccent else colors.accent,
            border = if (primary) null else BorderStroke(1.dp, colors.outline),
        ) {
            Box(Modifier.heightIn(min = 32.dp).padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
    }
}

/** The sheet's line of a "For session" grant, by what it covers. */
internal fun sessionLineRes(cover: GrantCover): Int = when (cover) {
    GrantCover.COMMANDS -> R.string.connection_assistant_sheet_session
    GrantCover.INPUT -> R.string.connection_assistant_sheet_session_input
    GrantCover.BOTH -> R.string.connection_assistant_sheet_session_both
    GrantCover.HTTP -> R.string.connection_assistant_sheet_session_http
    GrantCover.HTTP_UPLOAD -> R.string.connection_assistant_sheet_session_upload
    GrantCover.HTTP_BOTH -> R.string.connection_assistant_sheet_session_http_both
    GrantCover.MQTT -> R.string.connection_assistant_sheet_session_mqtt
}

/** The sheet's line of a remembered "For this connection" permission, by what it covers. */
internal fun connectionLineRes(cover: GrantCover): Int = when (cover) {
    GrantCover.COMMANDS -> R.string.connection_assistant_sheet_connection
    GrantCover.INPUT -> R.string.connection_assistant_sheet_connection_input
    GrantCover.BOTH -> R.string.connection_assistant_sheet_connection_both
    GrantCover.HTTP -> R.string.connection_assistant_sheet_connection_http
    GrantCover.HTTP_UPLOAD -> R.string.connection_assistant_sheet_connection_upload
    GrantCover.HTTP_BOTH -> R.string.connection_assistant_sheet_connection_http_both
    GrantCover.MQTT -> R.string.connection_assistant_sheet_connection_mqtt
}
