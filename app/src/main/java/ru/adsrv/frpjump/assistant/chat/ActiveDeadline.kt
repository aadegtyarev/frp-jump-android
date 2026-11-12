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
 *
 * Contract:
 * - Single use: one [ActiveDeadline] guards one body; a second [guard] call throws [IllegalStateException].
 * - Pauses nest: the clock stops at the first [pause] and restarts when every [pause] has a [resume]; an unmatched [resume] is ignored.
 *   Prefer [paused], which always resumes.
 * - Stop wins a race: if the caller is already cancelled when expiry unwinds the body, the caller's own cancellation is rethrown, not
 *   [ActiveDeadlineExpired], so a stopped Send is never reported as a timeout.
 * - [now] is injectable so tests use virtual time; [guard] uses the caller's `delay`, like `withTimeout`.
 * - Pause/resume update the StateFlow while holding [lock]. A collector resumed inline (Unconfined) would run under the lock on the
 *   same thread; the watchdog only re-enters [remaining], which is reentrant, so this cannot deadlock.
 */
internal class ActiveDeadline(private val limitMs: Long, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val lock = Any()
    private var used = 0L
    private var since: Long? = null
    private var depth = 0
    private var started = false
    private val paused = MutableStateFlow(false)

    fun pause() = synchronized(lock) {
        depth++
        if (depth == 1) { since?.let { used += now() - it; since = null }; paused.value = true }
    }
    fun resume() = synchronized(lock) {
        if (depth == 0) return@synchronized
        depth--
        if (depth == 0) { if (started) since = now(); paused.value = false }
    }
    /** Runs [block] with the clock paused, and resumes it afterwards (also on cancellation). */
    suspend fun <T> paused(block: suspend () -> T): T { pause(); try { return block() } finally { resume() } }

    private fun remaining(): Long = synchronized(lock) { limitMs - (used + (since?.let { now() - it } ?: 0L)) }

    suspend fun <T> guard(body: suspend CoroutineScope.() -> T): T {
        val expired = AtomicBoolean(false)
        synchronized(lock) {
            check(!started) { "ActiveDeadline is single-use" }
            started = true
            if (depth == 0) since = now()
        }
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
            val active = currentCoroutineContext().isActive
            if (expired.get() && active) throw ActiveDeadlineExpired()
            // The caller was cancelled too (Stop): it wins, and the internal expiry marker must not leak as a timeout.
            if (cancelled is ActiveDeadlineExpired && !active) throw CancellationException("Cancelled by the caller", cancelled)
            throw cancelled
        }
    }
}
