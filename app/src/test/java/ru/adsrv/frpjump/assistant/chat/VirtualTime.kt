package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.*
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext

/**
 * A single-threaded dispatcher with a virtual clock for tests: `delay` and `withTimeout` advance [now] instantly, so five
 * minutes cost nothing. [run] fails when the work is stuck with nothing scheduled.
 */
@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class VirtualTime : CoroutineDispatcher(), Delay {
    private class Task(val at: Long, val seq: Long, val action: Runnable) { @Volatile var cancelled = false }
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))
    private var seq = 0L
    var now = 0L
        private set

    private fun add(delay: Long, action: Runnable): Task = Task(now + delay, seq++, action).also { queue.add(it) }
    override fun dispatch(context: CoroutineContext, block: Runnable) { add(0, block) }
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val task = add(timeMillis) { with(continuation) { resumeUndispatched(Unit) } }
        continuation.invokeOnCancellation { task.cancelled = true }
    }
    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val task = add(timeMillis, block)
        return DisposableHandle { task.cancelled = true }
    }

    fun <T> run(block: suspend CoroutineScope.() -> T): T {
        val job = CoroutineScope(this + Job()).async(block = block)
        while (!job.isCompleted) {
            val next = queue.poll() ?: error("virtual-time run is stuck: nothing scheduled")
            if (next.cancelled) continue
            now = maxOf(now, next.at)
            next.action.run()
        }
        return job.getCompleted()
    }
}
