package ru.adsrv.frpjump.ssh

import android.util.Log
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The clock and timers of the reconnect loops. Injectable so tests drive time by hand. */
interface ReconnectClock {
    /** Milliseconds on a monotonic clock. */
    fun now(): Long

    /** Runs [action] after [delayMs] on the clock's thread; the returned function cancels it. */
    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
}

/** [ReconnectClock] on one daemon thread shared by all sessions. */
class ExecutorReconnectClock : ReconnectClock {
    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "ssh-reconnect").apply { isDaemon = true } }.apply {
        removeOnCancelPolicy = true
    }

    override fun now(): Long = System.nanoTime() / 1_000_000

    override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val future = executor.schedule({
            try {
                action()
            } catch (e: Exception) {
                Log.e(TAG, "reconnect step failed", e)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }

    private companion object {
        const val TAG = "SshReconnect"
    }
}

/**
 * The pause after the [failures]-th failed attempt in a row (1-based):
 * 2, 4, 8, 15 s, then 30 s on. The first attempt goes at once.
 */
fun reconnectPauseMs(failures: Int): Long = RECONNECT_PAUSES_MS.getOrElse(failures - 1) { RECONNECT_PAUSES_MS.last() }

private val RECONNECT_PAUSES_MS = listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L)

/** How long a dropped session keeps trying before it ends. */
const val RECONNECT_GIVE_UP_MS = 10 * 60_000L

/**
 * When a dropped SSH session tries again: at once, then after the
 * [reconnectPauseMs] pauses; at once when the network comes back or the
 * owner taps "Now"; gives up [RECONNECT_GIVE_UP_MS] after the drop.
 *
 * It only decides the timing. [attempt] (number of the try, from 1) starts
 * one connect and must answer with [succeeded], [failed] (worth another
 * try) or [stop] (login refused, host key, closed by the owner); [giveUp]
 * is called once when the time is up. Thread-safe: callbacks come from IO
 * threads, timers from [clock]'s thread, "Now" from the main thread.
 */
class SshReconnect(
    private val clock: ReconnectClock,
    private val attempt: (Int) -> Unit,
    private val giveUp: () -> Unit,
) {
    private var active = false
    private var held = false
    private var inFlight = false
    private var tries = 0
    private var failures = 0
    private var startedAt = 0L
    private var cancelTimer: (() -> Unit)? = null

    /** Whether a loop is running (between a drop and its success, stop or give-up). */
    @get:Synchronized
    val running: Boolean get() = active

    /** The connection dropped: start trying. Ignored while a loop already runs. */
    @Synchronized
    fun start() {
        if (active) return
        active = true
        tries = 0
        failures = 0
        startedAt = clock.now()
        tryNow()
    }

    /** "Now": try at once unless a try is under way. */
    @Synchronized
    fun now() {
        if (active) tryNow()
    }

    /** The default network came back or changed: the next pause is the shortest again, and a try goes at once. */
    @Synchronized
    fun networkReturned() {
        if (!active) return
        failures = 0
        tryNow()
    }

    /**
     * [on]: the tab is closed with Undo on screen (or its tunnel is
     * disconnected so): no tries until it is back. Off: back by Undo, a
     * loop still running tries at once with the short pauses again.
     */
    @Synchronized
    fun hold(on: Boolean) {
        if (held == on) return
        held = on
        if (on) {
            cancelTimer?.invoke()
            cancelTimer = null
        } else if (active) {
            failures = 0
            tryNow()
        }
    }

    @Synchronized
    fun succeeded() {
        end()
    }

    @Synchronized
    fun failed() {
        if (!active) return
        inFlight = false
        failures++
        // Held: Undo (or the close) decides; nothing is scheduled.
        if (held) return
        val left = startedAt + RECONNECT_GIVE_UP_MS - clock.now()
        if (left <= 0) {
            timeUp()
            return
        }
        cancelTimer = clock.schedule(minOf(reconnectPauseMs(failures), left)) { onTimer() }
    }

    /** No more tries: closed by the owner or the tunnel, login refused, host key to decide. */
    @Synchronized
    fun stop() {
        end()
    }

    @Synchronized
    private fun onTimer() {
        cancelTimer = null
        if (active) tryNow()
    }

    private fun tryNow() {
        if (inFlight || held) return
        cancelTimer?.invoke()
        cancelTimer = null
        if (clock.now() - startedAt >= RECONNECT_GIVE_UP_MS) {
            timeUp()
            return
        }
        inFlight = true
        attempt(++tries)
    }

    private fun timeUp() {
        end()
        giveUp()
    }

    private fun end() {
        active = false
        inFlight = false
        tries = 0
        failures = 0
        cancelTimer?.invoke()
        cancelTimer = null
    }
}

/**
 * Whether a shell that ended on its own (not closed by the owner) dropped
 * with the connection, and so is tried again: a read error, or the
 * connection closed without an exit status or signal. `exit`, a killed
 * shell or a logout is a normal end.
 */
fun shellDropped(readError: Boolean, exitStatus: Int?, exitSignal: Boolean): Boolean =
    readError || (exitStatus == null && !exitSignal)
