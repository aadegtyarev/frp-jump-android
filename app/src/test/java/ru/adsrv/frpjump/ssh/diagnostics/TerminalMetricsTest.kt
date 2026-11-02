package ru.adsrv.frpjump.ssh.diagnostics

import org.junit.Assert.*
import org.junit.Test

class TerminalMetricsTest {
    private var at = 0L
    private val metrics = TerminalMetrics(1000) { at }
    private fun ms(value: Long) { at += value * 1_000_000 }
    private fun open(): Long { metrics.setObserving(true); return metrics.connectionOpened() }

    @Test fun `unobserved and initial rates are unavailable not zero`() {
        val epoch = metrics.connectionOpened()
        metrics.received(epoch, 200)
        ms(2000)
        assertNull(metrics.snapshot().receiveBytesPerSecond)
        metrics.setObserving(true)
        assertNull(metrics.snapshot().receiveBytesPerSecond)
        ms(1000)
        assertEquals(0.0, metrics.snapshot().receiveBytesPerSecond!!, 0.001)
    }

    @Test fun `rates use actual interval and repeated snapshots do not reset baseline`() {
        val epoch = open()
        metrics.received(epoch, 1000)
        metrics.written(epoch, 500)
        ms(2000)
        val first = metrics.snapshot()
        assertEquals(500.0, first.receiveBytesPerSecond!!, 0.001)
        assertEquals(250.0, first.sendBytesPerSecond!!, 0.001)
        assertEquals(2000L, first.rateIntervalMs)
        metrics.received(epoch, 100)
        assertEquals(500.0, metrics.snapshot().receiveBytesPerSecond!!, 0.001)
        ms(1000)
        assertEquals(100.0, metrics.snapshot().receiveBytesPerSecond!!, 0.001)
    }

    @Test fun `background traffic is counted but not mistaken for current rate on resume`() {
        val epoch = open()
        metrics.setObserving(false)
        metrics.received(epoch, 10000)
        ms(10000)
        metrics.setObserving(true)
        assertNull(metrics.snapshot().receiveBytesPerSecond)
        ms(1000)
        val snapshot = metrics.snapshot()
        assertEquals(10000L, snapshot.receivedBytes)
        assertEquals(0.0, snapshot.receiveBytesPerSecond!!, 0.001)
    }

    @Test fun `replaced connections cannot contribute bytes or reply samples`() {
        val old = open()
        val token = metrics.token()!!
        metrics.received(old, 1234)
        metrics.connectionClosed()
        val fresh = metrics.connectionOpened()
        metrics.received(old, 500)
        metrics.written(old, 100)
        metrics.probeReplied(token, 10_000_000)
        assertNotEquals(old, fresh)
        val snapshot = metrics.snapshot()
        assertEquals(0L, snapshot.receivedBytes)
        assertEquals(0L, snapshot.sentBytes)
        assertEquals(0L, snapshot.response.count)
        assertFalse(snapshot.responseFresh)
    }

    @Test fun `backgrounding invalidates a probe even if the app resumes before its reply`() {
        open()
        val old = metrics.token()!!
        metrics.setObserving(false)
        metrics.setObserving(true)
        metrics.probeReplied(old, 10_000_000)
        assertEquals(0L, metrics.snapshot().response.count)
        metrics.probeReplied(metrics.token()!!, 20_000_000)
        assertEquals(1L, metrics.snapshot().response.count)
    }

    @Test fun `response summary expires freshness before retaining only historical last value`() {
        open()
        val token = metrics.token()!!
        metrics.probeReplied(token, 10_000_000)
        ms(1000)
        metrics.probeReplied(token, 30_000_000)
        var snapshot = metrics.snapshot()
        assertEquals(20.0, snapshot.response.meanMs!!, 0.001)
        assertEquals(30.0, snapshot.response.maxMs!!, 0.001)
        assertTrue(snapshot.responseFresh)
        ms(46000)
        assertFalse(metrics.snapshot().responseFresh)
        ms(75000)
        snapshot = metrics.snapshot()
        assertEquals(0L, snapshot.response.count)
        assertNull(snapshot.response.meanMs)
        assertEquals(30.0, snapshot.response.lastMs!!, 0.001)
        assertFalse(snapshot.responseFresh)
    }

    @Test fun `failed and timed out probes never become zero latency samples`() {
        open()
        val token = metrics.token()!!
        metrics.probeFailed(token, timeout = true)
        metrics.probeFailed(token, timeout = false)
        metrics.probeReplied(token, -1)
        val snapshot = metrics.snapshot()
        assertEquals(1L, snapshot.probeTimeouts)
        assertEquals(1L, snapshot.probeFailures)
        assertEquals(0L, snapshot.response.count)
        assertNull(snapshot.response.meanMs)
    }

    @Test fun `only one input marker is pending and markers are rate limited`() {
        val epoch = open()
        val first = metrics.inputMarker(epoch)!!
        assertNull(metrics.inputMarker(epoch))
        ms(20)
        metrics.inputProcessed(first)
        assertEquals(20.0, metrics.snapshot().incomingProcessing.lastMs!!, 0.001)
        assertNull(metrics.inputMarker(epoch))
        ms(80)
        assertNotNull(metrics.inputMarker(epoch))
    }

    @Test fun `old marker cannot clear a new marker after background transition`() {
        val epoch = open()
        val old = metrics.inputMarker(epoch)!!
        metrics.setObserving(false)
        metrics.setObserving(true)
        val fresh = metrics.inputMarker(epoch)!!
        metrics.inputProcessed(old)
        assertNull(metrics.inputMarker(epoch))
        ms(5)
        metrics.inputProcessed(fresh)
        assertEquals(1L, metrics.snapshot().incomingProcessing.count)
    }

    @Test fun `input-to-send records earliest owner input until accepted write`() {
        val epoch = open()
        metrics.ownerInput()
        ms(10)
        metrics.ownerInput()
        ms(40)
        metrics.written(epoch, 5)
        ms(10)
        metrics.written(epoch, 3)
        val snapshot = metrics.snapshot()
        assertEquals(1L, snapshot.inputToSend.count)
        assertEquals(50.0, snapshot.inputToSend.lastMs!!, 0.001)
        assertEquals(8L, snapshot.sentBytes)
    }

    @Test fun `guard draw and buffer observations remain numeric and disabled observers do nothing`() {
        open()
        metrics.beforeWriteFinished(metrics.token(), 5_000_000_000L)
        repeat(10000) { metrics.drawFinished(8_000_000) }
        metrics.bufferState(743, false)
        var snapshot = metrics.snapshot()
        assertEquals(5000.0, snapshot.beforeWrite.lastMs!!, 0.001)
        assertEquals(10000L, snapshot.drawCpu.count)
        assertEquals(8.0, snapshot.drawCpu.meanMs!!, 0.001)
        assertEquals(1000, snapshot.historyLimit)
        assertEquals(743, snapshot.historyUsed)
        assertEquals(false, snapshot.alternateScreen)
        metrics.setObserving(false)
        metrics.drawFinished(99_000_000)
        snapshot = metrics.snapshot()
        assertEquals(10000L, snapshot.drawCpu.count)
        ms(121000)
        assertEquals(0L, metrics.snapshot().drawCpu.count)
    }

    @Test fun `disconnect removes current rates but preserves labelled historical totals`() {
        val epoch = open()
        metrics.received(epoch, 1024)
        ms(1000)
        assertNotNull(metrics.snapshot().receiveBytesPerSecond)
        metrics.connectionClosed()
        metrics.received(epoch, 99)
        val snapshot = metrics.snapshot()
        assertFalse(snapshot.connected)
        assertTrue(snapshot.everConnected)
        assertNull(snapshot.receiveBytesPerSecond)
        assertEquals(1024L, snapshot.receivedBytes)
    }
}
