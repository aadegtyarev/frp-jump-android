package ru.adsrv.frpjump.mqtt

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The one thread MQTT sessions run their logic on, and their clock: every
 * session's state changes in [post]ed or [schedule]d actions, so none of it
 * needs a lock. Injectable so tests drive time by hand.
 */
interface MqttScheduler {
    /** Milliseconds on a monotonic clock. */
    fun now(): Long

    fun post(action: () -> Unit)

    /** Runs [action] after [delayMs]; the returned function cancels it. */
    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
}

/** [MqttScheduler] on one daemon thread shared by all sessions. */
class ExecutorScheduler : MqttScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "mqtt").apply { isDaemon = true } }.apply {
        // Cancelled snapshot checks and reconnects leave the queue at once.
        removeOnCancelPolicy = true
    }

    override fun now(): Long = System.nanoTime() / 1_000_000

    override fun post(action: () -> Unit) = executor.execute { guarded(action) }

    override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val future = executor.schedule({ guarded(action) }, delayMs, TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }

    private fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            Log.e(TAG, "MQTT session action failed", e)
        }
    }

    private companion object {
        const val TAG = "MqttScheduler"
    }
}

/**
 * Runs [action] on the scheduler's thread after everything already posted
 * there and waits for it, passing on what it throws. Store changes made
 * this way (Forget, delete) come after any write of a session closed
 * before. Never from the scheduler's own thread (it would wait forever) nor
 * the main thread.
 */
fun MqttScheduler.runAfterPending(action: () -> Unit) {
    val done = CountDownLatch(1)
    var failure: Throwable? = null
    post {
        try {
            action()
        } catch (e: Throwable) {
            failure = e
        } finally {
            done.countDown()
        }
    }
    done.await()
    failure?.let { throw it }
}
