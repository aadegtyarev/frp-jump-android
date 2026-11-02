package ru.adsrv.frpjump.ssh.diagnostics

import org.junit.Assert.*
import org.junit.Test

class TerminalReportsTest {
    private var at = 0L
    private fun terminal(rx: Int, tx: Int): TerminalMetrics = TerminalMetrics(1000) { at }.apply {
        setObserving(true)
        val epoch = connectionOpened()
        received(epoch, rx); written(epoch, tx)
    }
    private fun reports(vararg metrics: TerminalMetrics) = metrics.mapIndexed { index, it -> TerminalSessionReport(index + 1, it.snapshot()) }

    @Test fun `rates sum all live terminal channels and never other traffic`() {
        val first = terminal(1000, 500)
        val second = terminal(2000, 1000)
        at = 1_000_000_000
        val summary = summarizeTerminalReports(reports(first, second))
        assertEquals(2, summary.connectedSessions)
        assertEquals(3000.0, summary.receiveBytesPerSecond!!, 0.001)
        assertEquals(1500.0, summary.sendBytesPerSecond!!, 0.001)
    }

    @Test fun `an unavailable rate prevents misleading partial totals`() {
        val first = terminal(1000, 500)
        val second = terminal(2000, 1000)
        second.setObserving(false)
        at = 1_000_000_000
        val summary = summarizeTerminalReports(reports(first, second))
        assertNull(summary.receiveBytesPerSecond)
        assertNull(summary.sendBytesPerSecond)
    }

    @Test fun `response mean is sample weighted and max spans live sessions`() {
        val first = terminal(0, 0)
        val second = terminal(0, 0)
        first.probeReplied(first.token()!!, 10_000_000)
        first.probeReplied(first.token()!!, 30_000_000)
        second.probeReplied(second.token()!!, 100_000_000)
        val summary = summarizeTerminalReports(reports(first, second))
        assertEquals(140.0 / 3, summary.responseMeanMs!!, 0.001)
        assertEquals(100.0, summary.responseMaxMs!!, 0.001)
    }

    @Test fun `missing or stale response cannot be hidden by a healthy tab`() {
        val first = terminal(0, 0)
        val second = terminal(0, 0)
        first.probeReplied(first.token()!!, 10_000_000)
        assertNull(summarizeTerminalReports(reports(first, second)).responseMeanMs)
        second.probeReplied(second.token()!!, 30_000_000)
        at = 46_000_000_000
        first.probeReplied(first.token()!!, 10_000_000)
        assertNull(summarizeTerminalReports(reports(first, second)).responseMeanMs)
    }

    @Test fun `ended sessions retain details but do not inflate current summary`() {
        val ended = terminal(100000, 100000)
        val live = terminal(1000, 500)
        ended.connectionClosed()
        at = 1_000_000_000
        val details = reports(ended, live)
        val summary = summarizeTerminalReports(details)
        assertEquals(2, details.size)
        assertEquals(100000L, details.first().metrics.receivedBytes)
        assertEquals(1, summary.connectedSessions)
        assertEquals(1000.0, summary.receiveBytesPerSecond!!, 0.001)
    }

    @Test fun `no sessions is unavailable rather than a successful zero measurement`() {
        val summary = summarizeTerminalReports(emptyList())
        assertEquals(0, summary.connectedSessions)
        assertNull(summary.receiveBytesPerSecond)
        assertNull(summary.responseMeanMs)
    }
}
