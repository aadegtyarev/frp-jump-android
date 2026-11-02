package ru.adsrv.frpjump.ssh.diagnostics

import java.util.ArrayDeque

private const val SECOND = 1_000_000_000L
private const val WINDOW = 120 * SECOND
const val METRIC_FRESH_NS = 45 * SECOND

/** Values are numeric only. Last may be historical even when the recent window is empty. */
data class TimingSummary(
    val count: Long = 0,
    val meanMs: Double? = null,
    val maxMs: Double? = null,
    val lastMs: Double? = null,
    val lastAtNs: Long? = null,
)

/** One-second buckets keep draw/IO observations bounded independently of their event rate. */
internal class TimingWindow {
    private data class Bucket(val start: Long, var count: Long, var sum: Double, var max: Long)
    private val buckets = ArrayDeque<Bucket>()
    private var lastNs: Long? = null
    private var lastAt: Long? = null

    fun clear() { buckets.clear(); lastNs = null; lastAt = null }

    fun add(at: Long, duration: Long) {
        if (duration < 0) return
        prune(at)
        val bucket = buckets.peekLast()
        if (bucket == null || at - bucket.start >= SECOND) {
            buckets.addLast(Bucket(at, 1, duration.toDouble(), duration))
        } else {
            bucket.count++
            bucket.sum += duration
            bucket.max = maxOf(bucket.max, duration)
        }
        while (buckets.size > 121) buckets.removeFirst()
        lastNs = duration
        lastAt = at
    }

    fun summary(at: Long): TimingSummary {
        prune(at)
        val count = buckets.sumOf { it.count }
        return TimingSummary(
            count = count,
            meanMs = if (count == 0L) null else buckets.sumOf { it.sum } / count / 1_000_000.0,
            maxMs = buckets.maxOfOrNull { it.max }?.div(1_000_000.0),
            lastMs = lastNs?.div(1_000_000.0),
            lastAtNs = lastAt,
        )
    }

    private fun prune(at: Long) {
        while (buckets.isNotEmpty() && at - buckets.first.start > WINDOW) buckets.removeFirst()
    }
}

data class ObservationToken(val connection: Long, val observation: Long)
data class InputMarker(val token: ObservationToken, val id: Long, val queuedAtNs: Long)

data class TerminalMetricsSnapshot(
    val atNs: Long,
    val connected: Boolean,
    val observed: Boolean,
    val everConnected: Boolean,
    val receivedBytes: Long,
    val sentBytes: Long,
    val receiveBytesPerSecond: Double?,
    val sendBytesPerSecond: Double?,
    val rateIntervalMs: Long?,
    val response: TimingSummary,
    val responseFresh: Boolean,
    val probeTimeouts: Long,
    val probeFailures: Long,
    val inputToSend: TimingSummary,
    val beforeWrite: TimingSummary,
    val incomingProcessing: TimingSummary,
    val drawCpu: TimingSummary,
    val historyLimit: Int,
    val historyUsed: Int?,
    val alternateScreen: Boolean?,
    val connectionGeneration: Long,
)

/** Thread-safe, O(1) event recording. Snapshots are sampled by the owner, never emitted per event. */
class TerminalMetrics(val historyLimit: Int, private val now: () -> Long = System::nanoTime) {
    private var generation = 0L
    private var observation = 0L
    private var connected = false
    private var observing = false
    private var everConnected = false
    private var rx = 0L
    private var tx = 0L
    private var baselineAt = 0L
    private var baselineRx = 0L
    private var baselineTx = 0L
    private var rxRate: Double? = null
    private var txRate: Double? = null
    private var rateInterval: Long? = null
    private var ownerAt: Long? = null
    private var marker: InputMarker? = null
    private var markerId = 0L
    private var lastMarkerAt: Long? = null
    private var timeouts = 0L
    private var failures = 0L
    private var historyUsed: Int? = null
    private var alternate: Boolean? = null
    private val response = TimingWindow()
    private val inputToSend = TimingWindow()
    private val beforeWrite = TimingWindow()
    private val incoming = TimingWindow()
    private val draw = TimingWindow()

    @Synchronized fun connectionOpened(): Long {
        generation++
        connected = true
        everConnected = true
        rx = 0; tx = 0; timeouts = 0; failures = 0
        ownerAt = null; marker = null; lastMarkerAt = null
        response.clear(); inputToSend.clear(); beforeWrite.clear(); incoming.clear(); draw.clear()
        resetRates()
        return generation
    }

    @Synchronized fun connectionClosed() {
        generation++
        connected = false
        marker = null; ownerAt = null
        resetRates()
    }

    @Synchronized fun setObserving(enabled: Boolean) {
        if (observing == enabled) return
        observing = enabled
        observation++
        ownerAt = null; marker = null; lastMarkerAt = null
        resetRates()
    }

    @Synchronized fun isObserving(): Boolean = observing
    @Synchronized fun currentGeneration(): Long = generation
    @Synchronized fun token(): ObservationToken? = if (connected && observing) ObservationToken(generation, observation) else null
    private fun accepts(token: ObservationToken): Boolean = connected && observing && token.connection == generation && token.observation == observation

    @Synchronized fun received(epoch: Long, count: Int) {
        if (connected && epoch == generation && count > 0) rx += count
    }

    @Synchronized fun ownerInput() {
        if (connected && observing && ownerAt == null) ownerAt = now()
    }

    @Synchronized fun written(epoch: Long, count: Int) {
        if (!connected || epoch != generation || count <= 0) return
        tx += count
        val at = now()
        if (observing) ownerAt?.let { inputToSend.add(at, at - it) }
        ownerAt = null
    }

    @Synchronized fun beforeWriteFinished(token: ObservationToken?, durationNs: Long) {
        if (token != null && accepts(token)) beforeWrite.add(now(), durationNs)
    }

    @Synchronized fun inputMarker(epoch: Long): InputMarker? {
        if (!connected || !observing || epoch != generation || marker != null) return null
        val at = now()
        if (lastMarkerAt?.let { at - it < SECOND / 10 } == true) return null
        return InputMarker(ObservationToken(generation, observation), ++markerId, at).also { marker = it; lastMarkerAt = at }
    }

    @Synchronized fun inputProcessed(completed: InputMarker) {
        if (marker != completed || !accepts(completed.token)) return
        marker = null
        val at = now()
        incoming.add(at, at - completed.queuedAtNs)
    }

    @Synchronized fun drawFinished(durationNs: Long) {
        if (observing) draw.add(now(), durationNs)
    }

    @Synchronized fun probeReplied(token: ObservationToken, durationNs: Long) {
        if (accepts(token)) response.add(now(), durationNs)
    }

    @Synchronized fun probeFailed(token: ObservationToken, timeout: Boolean) {
        if (accepts(token)) { if (timeout) timeouts++ else failures++ }
    }

    @Synchronized fun bufferState(used: Int, alternateScreen: Boolean) {
        historyUsed = used.coerceAtLeast(0)
        alternate = alternateScreen
    }

    @Synchronized fun snapshot(): TerminalMetricsSnapshot {
        val at = now()
        if (connected && observing && at - baselineAt >= SECOND) {
            val elapsed = at - baselineAt
            rxRate = (rx - baselineRx) * 1_000_000_000.0 / elapsed
            txRate = (tx - baselineTx) * 1_000_000_000.0 / elapsed
            rateInterval = elapsed / 1_000_000
            baselineAt = at; baselineRx = rx; baselineTx = tx
        }
        val responseSummary = response.summary(at)
        return TerminalMetricsSnapshot(
            at, connected, observing, everConnected, rx, tx,
            rxRate.takeIf { connected && observing }, txRate.takeIf { connected && observing }, rateInterval.takeIf { connected && observing },
            responseSummary, connected && observing && responseSummary.lastAtNs?.let { at - it <= METRIC_FRESH_NS } == true,
            timeouts, failures, inputToSend.summary(at), beforeWrite.summary(at), incoming.summary(at), draw.summary(at),
            historyLimit, historyUsed, alternate, generation,
        )
    }

    private fun resetRates() {
        baselineAt = now(); baselineRx = rx; baselineTx = tx
        rxRate = null; txRate = null; rateInterval = null
    }
}
