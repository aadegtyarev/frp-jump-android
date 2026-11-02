package ru.adsrv.frpjump.ui.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ssh.diagnostics.METRIC_FRESH_NS
import ru.adsrv.frpjump.ssh.diagnostics.TerminalSessionReport
import ru.adsrv.frpjump.ssh.diagnostics.TimingSummary
import ru.adsrv.frpjump.ssh.diagnostics.summarizeTerminalReports
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.ui.AppSheet
import ru.adsrv.frpjump.ui.AppTheme

@Composable
fun LiveTerminalDiagnostics(ref: TunnelRef) {
    val app = LocalContext.current.applicationContext as FrpJumpApp
    val snapshots by app.ssh.diagnostics.collectAsStateWithLifecycle()
    key(ref) { TerminalDiagnostics(snapshots[ref].orEmpty()) }
}

/** Only this small component observes telemetry; the terminal screen never collects the snapshots. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalDiagnostics(reports: List<TerminalSessionReport>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val summary = summarizeTerminalReports(reports)
    val colors = AppTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp)
            .testTag("terminal-diagnostics-line")
            .semantics(mergeDescendants = true) { }
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.diagnostics_open)) { open = true }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("SSH ${duration(summary.responseMeanMs)}", color = colors.muted, fontSize = 12.sp)
            Text("↓ ${bytes(summary.receiveBytesPerSecond, rate = true)}", color = colors.muted, fontSize = 12.sp)
            Text("↑ ${bytes(summary.sendBytesPerSecond, rate = true)}", color = colors.muted, fontSize = 12.sp)
        }
        Text("›", color = colors.muted, modifier = Modifier.clearAndSetSemantics { })
    }
    if (open) TerminalDiagnosticsSheet(reports) { open = false }
}

@Composable
fun TerminalDiagnosticsSheet(reports: List<TerminalSessionReport>, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    AppSheet(
        title = stringResource(R.string.diagnostics_title),
        subtitle = stringResource(R.string.diagnostics_scope),
        onDismiss = onDismiss,
    ) {
        if (reports.isEmpty()) Text(stringResource(R.string.diagnostics_no_sessions), fontSize = 14.sp)
        for (report in reports) {
            val m = report.metrics
            HorizontalDivider(color = colors.divider)
            Text(
                stringResource(R.string.diagnostics_session, report.sessionId, stringResource(if (m.connected) R.string.diagnostics_connected else R.string.diagnostics_disconnected)),
                fontWeight = FontWeight.SemiBold,
            )
            if (!m.observed) Text(stringResource(R.string.diagnostics_paused), fontSize = 13.sp, color = colors.muted)
            Detail(stringResource(R.string.diagnostics_totals), if (m.everConnected) "↓ ${bytes(m.receivedBytes.toDouble())} · ↑ ${bytes(m.sentBytes.toDouble())}" else "—")
            Detail(stringResource(R.string.diagnostics_rates), "↓ ${bytes(m.receiveBytesPerSecond, true)} · ↑ ${bytes(m.sendBytesPerSecond, true)}")
            Detail(stringResource(R.string.diagnostics_probe_failures), "${m.probeTimeouts} / ${m.probeFailures}")
            TimingDetail(stringResource(R.string.diagnostics_response), m.response, m.atNs, m.connected && m.observed)
            TimingDetail(stringResource(R.string.diagnostics_input_send), m.inputToSend, m.atNs, m.connected && m.observed)
            TimingDetail(stringResource(R.string.diagnostics_before_write), m.beforeWrite, m.atNs, m.connected && m.observed)
            TimingDetail(stringResource(R.string.diagnostics_incoming), m.incomingProcessing, m.atNs, m.connected && m.observed)
            TimingDetail(stringResource(R.string.diagnostics_draw), m.drawCpu, m.atNs, m.observed)
            Detail(
                stringResource(R.string.diagnostics_buffer),
                stringResource(R.string.diagnostics_buffer_value, m.historyLimit, m.historyUsed?.toString() ?: "—"),
            )
            Text(
                stringResource(when (m.alternateScreen) {
                    true -> R.string.diagnostics_alternate
                    false -> R.string.diagnostics_main_buffer
                    null -> R.string.diagnostics_buffer_unknown
                }),
                fontSize = 12.sp, color = colors.muted,
            )
        }
        Text(stringResource(R.string.diagnostics_limits), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 13.sp, color = AppTheme.colors.muted)
    }
}

@Composable
private fun TimingDetail(label: String, timing: TimingSummary, at: Long, current: Boolean) {
    val age = timing.lastAtNs?.let { (at - it).coerceAtLeast(0) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Detail(label, stringResource(R.string.diagnostics_timing_summary, duration(timing.meanMs), duration(timing.maxMs), timing.count))
        if (age != null) {
            Text(
                stringResource(R.string.diagnostics_timing_last, duration(timing.lastMs), age / 1_000_000_000),
                fontSize = 12.sp, color = AppTheme.colors.muted,
            )
        }
        if (!current || age == null || age > METRIC_FRESH_NS) {
            Text(stringResource(R.string.diagnostics_not_fresh), fontSize = 12.sp, color = AppTheme.colors.muted)
        }
    }
}

@Composable
private fun duration(value: Double?): String = if (value == null) "—" else
    stringResource(R.string.diagnostics_ms, diagnosticNumber(value, LocalConfiguration.current.locales[0]))

@Composable
private fun bytes(value: Double?, rate: Boolean = false): String {
    val scaled = scaleDiagnosticBytes(value) ?: return "—"
    val units = listOf(R.string.diagnostics_b, R.string.diagnostics_kib, R.string.diagnostics_mib, R.string.diagnostics_gib)
    return stringResource(
        if (rate) R.string.diagnostics_rate_value else R.string.diagnostics_bytes_value,
        diagnosticNumber(scaled.value, LocalConfiguration.current.locales[0]), stringResource(units[scaled.unit]),
    )
}
