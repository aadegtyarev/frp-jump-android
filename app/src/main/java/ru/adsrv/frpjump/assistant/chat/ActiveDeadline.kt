package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/** The Send ran longer than its limit of active time. Distinct from a provider/tool timeout. */
internal class ActiveDeadlineExpired : CancellationException("Active time limit reached")

/**
 * A deadline over ACTIVE time: [pause]d intervals (waiting for the owner) are not counted. [guard] runs a body and cancels it
 * with [ActiveDeadlineExpired] once more than [limitMs] of unpaused time has passed since [guard] started.
 * [now] is injectable so tests use virtual time; [guard] uses the caller's `delay`, like `withTimeout`.
 * Pause and resume are idempotent and thread-safe.
 */
internal class ActiveDeadline(private val limitMs: Long, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val lock = Any()
    private var used = 0L
    private var since: Long? = null
    private val paused = MutableStateFlow(false)

    fun pause() = synchronized(lock) {
        since?.let { used += now() - it; since = null }
        paused.value = true
    }
    fun resume() = synchronized(lock) {
        if (since == null) since = now()
        paused.value = false
    }
    /** Runs [block] with the clock paused, and resumes it afterwards (also on cancellation). */
    suspend fun <T> paused(block: suspend () -> T): T { pause(); try { return block() } finally { resume() } }

    private fun remaining(): Long = synchronized(lock) { limitMs - (used + (since?.let { now() - it } ?: 0L)) }

    suspend fun <T> guard(body: suspend CoroutineScope.() -> T): T {
        val expired = AtomicBoolean(false)
        resume()
        try {
            return coroutineScope {
                val watchdog = launch {
                    while (true) {
                        paused.first { !it }
                        val left = remaining()
                        if (left > 0) { delay(left); continue }
                        if (paused.value) continue
                        expired.set(true)
                        this@coroutineScope.cancel(ActiveDeadlineExpired())
                        return@launch
                    }
                }
                try { body() } finally { watchdog.cancel() }
            }
        } catch (cancelled: CancellationException) {
            if (expired.get() && currentCoroutineContext().isActive) throw ActiveDeadlineExpired()
            throw cancelled
        }
    }
}
