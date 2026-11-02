package ru.adsrv.frpjump.ssh.diagnostics

/** Local ephemeral tab identity, not terminal contents or authentication material. */
data class TerminalSessionReport(val sessionId: Int, val metrics: TerminalMetricsSnapshot)

data class TunnelTerminalSummary(
    val connectedSessions: Int,
    val receiveBytesPerSecond: Double?,
    val sendBytesPerSecond: Double?,
    val responseMeanMs: Double?,
    val responseMaxMs: Double?,
)

/** Never present a partial sum or one healthy tab's RTT as a complete tunnel measurement. */
fun summarizeTerminalReports(reports: List<TerminalSessionReport>): TunnelTerminalSummary {
    val live = reports.map { it.metrics }.filter { it.connected }
    val ratesReady = live.isNotEmpty() && live.all { it.observed && it.receiveBytesPerSecond != null && it.sendBytesPerSecond != null }
    val responseReady = live.isNotEmpty() && live.all { it.responseFresh && it.response.count > 0 }
    val samples = live.sumOf { it.response.count }
    return TunnelTerminalSummary(
        connectedSessions = live.size,
        receiveBytesPerSecond = if (ratesReady) live.sumOf { it.receiveBytesPerSecond!! } else null,
        sendBytesPerSecond = if (ratesReady) live.sumOf { it.sendBytesPerSecond!! } else null,
        responseMeanMs = if (responseReady) live.sumOf { it.response.meanMs!! * it.response.count } / samples else null,
        responseMaxMs = if (responseReady) live.maxOf { it.response.maxMs!! } else null,
    )
}
