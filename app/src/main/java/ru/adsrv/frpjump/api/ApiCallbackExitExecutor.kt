package ru.adsrv.frpjump.api

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred

/** Same callback-exit pattern as the assistant transports. An owned API call waits
 * until its entire OkHttp AsyncCall.run exits, not just until its callback resumes it.
 * Ordinary calls on this executor register nothing and retain ordinary behavior.
 */
internal class ApiCallbackExitExecutor : ThreadPoolExecutor(
    0, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue(),
    { task -> Thread(task, "frpjump-api-http").apply { isDaemon = false } },
) {
    private val completion = ThreadLocal<MutableList<CompletableDeferred<Unit>>>()
    private val worker = ThreadLocal<Boolean>()
    override fun beforeExecute(thread: Thread, task: Runnable) {
        super.beforeExecute(thread, task)
        worker.set(true)
    }
    fun register(exited: CompletableDeferred<Unit>): Boolean {
        if (worker.get() != true) return false
        val pending = completion.get() ?: mutableListOf<CompletableDeferred<Unit>>().also { completion.set(it) }
        pending.add(exited)
        return true
    }
    override fun afterExecute(task: Runnable, failure: Throwable?) {
        val exited = completion.get()
        completion.remove()
        worker.remove()
        try { super.afterExecute(task, failure) } finally { exited?.forEach { it.complete(Unit) } }
    }
}

/** Request-scoped admission of the actual nonblocking enqueue, never credential or body IO. */
typealias ApiRequestDispatch = (operation: String, start: () -> Unit) -> Unit
