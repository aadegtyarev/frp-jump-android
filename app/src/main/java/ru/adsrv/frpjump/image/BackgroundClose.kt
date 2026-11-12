package ru.adsrv.frpjump.image

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Closes things on a thread of its own. Closing an sshj channel waits for the peer's CLOSE, up to sshj's own 30 s, so it
 * must never run on a caller that is cancelling, on a watchdog's single thread (it would delay every other tab's
 * watchdog), or in the path that ends a job and releases its tab. Each close is its own task and ends by sshj's limits.
 */
internal object BackgroundClose {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "image-close").apply { isDaemon = true } }

    /** Closes [c] without waiting for it; a failure is ignored (the thing is going away either way). */
    fun close(c: Closeable) = run(Runnable { runCatching { c.close() } })

    /** Runs [task] on a thread of its own; a pool that cannot take it is a thing already shutting down. */
    fun run(task: Runnable) {
        try {
            pool.execute(task)
        } catch (e: RejectedExecutionException) {
            // Only at shutdown of the process.
        }
    }
}
