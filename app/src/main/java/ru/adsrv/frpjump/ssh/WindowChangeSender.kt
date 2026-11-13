package ru.adsrv.frpjump.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Sends the terminal's size to the server (SSH `window-change`) once it has
 * settled: while the keyboard or a panel animates the view resizes on every
 * frame, and each intermediate size would make tmux redraw.
 *
 * [request] is cheap and callable from any thread. A request waits [settleMillis];
 * a newer one restarts the wait, so only the last size goes out, and not at all
 * when it equals the size already sent. [close] drops a pending send for good.
 * [send] runs on [context] and returns whether the size reached the server.
 */
internal class WindowChangeSender(
    private val scope: CoroutineScope,
    private val send: (columns: Int, rows: Int) -> Boolean,
    private val settleMillis: Long = SETTLE_MILLIS,
    private val context: CoroutineContext = Dispatchers.IO,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Any()
    private var closed = false
    private var sent: Pair<Int, Int>? = null
    private var pending: Job? = null

    /** The server already has this size (the pty-req at open): not sent again. */
    fun sent(columns: Int, rows: Int) = synchronized(lock) { sent = columns to rows }

    fun request(columns: Int, rows: Int) {
        val size = columns to rows
        synchronized(lock) {
            if (closed) return
            pending?.cancel()
            pending = null
            if (size == sent) return
            pending = scope.launch(context) {
                wait(settleMillis)
                synchronized(lock) { if (closed || size == sent) return@launch }
                if (send(columns, rows)) synchronized(lock) { if (!closed) sent = size }
            }
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        pending?.cancel()
        pending = null
    }

    companion object {
        const val SETTLE_MILLIS = 100L
    }
}
