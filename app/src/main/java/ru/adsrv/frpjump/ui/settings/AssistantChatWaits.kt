package ru.adsrv.frpjump.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.ActiveWait
import ru.adsrv.frpjump.assistant.AssistantWaitTools
import ru.adsrv.frpjump.assistant.WaitEvent
import ru.adsrv.frpjump.assistant.WaitKind
import ru.adsrv.frpjump.ssh.isolateName
import ru.adsrv.frpjump.ui.AppTheme

/** What a wait waits for, in the owner's words (a tab's name or label, a tunnel's name; each name between its marks, bidi-isolated). */
@Composable
internal fun waitWhatText(kind: WaitKind, subject: String?, text: String?, minutes: Int?): String {
    val name = isolateName(subject.orEmpty())
    return when (kind) {
        WaitKind.TAB_CONNECTED -> stringResource(R.string.wait_tab_connected, name)
        WaitKind.TAB_CLOSED -> stringResource(R.string.wait_tab_closed, name)
        WaitKind.TAB_TEXT -> stringResource(R.string.wait_tab_text, name, text.orEmpty())
        WaitKind.TAB_QUIET -> stringResource(R.string.wait_tab_quiet, name)
        WaitKind.TUNNEL_ON -> stringResource(R.string.wait_tunnel_on, name)
        WaitKind.TUNNEL_OFF -> stringResource(R.string.wait_tunnel_off, name)
        WaitKind.TIME -> stringResource(R.string.wait_time, minutes ?: 0)
    }
}

/** One line per wait that runs: "Waiting: tab "logs" connects · until 10:45", with Cancel (an accessible action of its own). */
@Composable
internal fun ChatWaitLines(waits: List<ActiveWait>, onCancel: (Long) -> Unit) {
    if (waits.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("chat_waits"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        waits.forEach { wait ->
            val what = waitWhatText(wait.spec.kind, wait.subject ?: wait.spec.tabId?.toString(), wait.spec.text, wait.spec.minutes)
            val line = stringResource(R.string.chat_wait_line, what, AssistantWaitTools.clockText(wait.untilMs))
            val cancel = stringResource(R.string.chat_wait_cancel)
            Row(Modifier.fillMaxWidth().testTag("chat_wait_${wait.id}"), verticalAlignment = Alignment.CenterVertically) {
                Text(line, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted, modifier = Modifier.weight(1f))
                TextButton(onClick = { onCancel(wait.id) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp).semantics { contentDescription = "$cancel: $line" }
                    .testTag("chat_wait_cancel_${wait.id}")) { Text(cancel) }
            }
        }
    }
}

/** The events of waits that started or joined a turn, as muted lines of their own (never drawn as the owner's words). */
@Composable
internal fun ChatEventLines(events: List<WaitEvent>) {
    if (events.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("chat_events"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        events.forEach { e ->
            val name = isolateName(e.subject ?: e.tabId?.toString().orEmpty())
            val text = if (e.timedOut) stringResource(R.string.event_timed_out, waitWhatText(e.kind, e.subject ?: e.tabId?.toString(), e.text, e.minutes)) else when (e.kind) {
                WaitKind.TAB_CONNECTED -> stringResource(R.string.event_tab_connected, name)
                WaitKind.TAB_CLOSED -> stringResource(R.string.event_tab_closed, name)
                WaitKind.TAB_TEXT -> stringResource(R.string.event_tab_text, name, e.text.orEmpty())
                WaitKind.TAB_QUIET -> stringResource(R.string.event_tab_quiet, name)
                WaitKind.TUNNEL_ON -> stringResource(R.string.event_tunnel_on, name)
                WaitKind.TUNNEL_OFF -> stringResource(R.string.event_tunnel_off, name)
                WaitKind.TIME -> stringResource(R.string.event_time, e.minutes ?: 0)
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The line that says event replies stopped at the owner's limit until the owner writes. */
@Composable
internal fun ChatEventCap(count: Int) {
    Text(stringResource(R.string.chat_event_cap, count), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted,
        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).testTag("chat_event_cap"))
}
