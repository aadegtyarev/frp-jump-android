package ru.adsrv.frpjump.agent

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs blocking [block] on Dispatchers.IO. If the caller is cancelled while [block] runs, calls [onCancel] promptly in an
 * IO context (never on a non-IO caller thread such as Main). Returns or throws only after [block] has actually returned.
 * [onCancel] runs at most once, never starts after [block] returned, and its failures are swallowed. If the caller was
 * cancelled, the result of [block] is discarded and CancellationException is thrown (an exception from [block] is only a
 * consequence of the cancellation and is attached as suppressed on a best-effort basis). No thread is interrupted: [onCancel] alone must make a blocking call return.
 */
suspend fun <T> blockingCancellable(onCancel: () -> Unit, block: () -> T): T = coroutineScope {
    currentCoroutineContext().ensureActive()
    val state = AtomicInteger(RUNNING)
    val signal = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally {
            if (state.compareAndSet(RUNNING, CANCELLED)) withContext(NonCancellable + Dispatchers.IO) { runCatching { onCancel() } }
        }
    }
    try {
        val result = try {
            withContext(Dispatchers.IO) {
                // From here on onCancel can no longer start: it never runs after the block has returned.
                try { block() } finally { state.compareAndSet(RUNNING, FINISHED) }
            }
        } catch (failure: Throwable) {
            if (failure !is CancellationException && !isActive) throw CancellationException("Cancelled during blocking call").also { it.addSuppressed(failure) }
            throw failure
        }
        ensureActive()
        result
    } finally { state.compareAndSet(RUNNING, FINISHED); signal.cancel() }
}

private const val RUNNING = 0
private const val FINISHED = 1
private const val CANCELLED = 2

/**
 * Ambient start check for owned work; absent for ordinary UI work. [check] runs under the owner's short guard,
 * on the calling thread, and must neither block nor suspend.
 */
class SideEffectAdmission(val check: (category: String) -> Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SideEffectAdmission>
}

/**
 * Throws CancellationException when the caller is cancelled or the ambient admission refuses [category]; does nothing
 * outside an owned run. Call immediately before starting a network side effect.
 */
suspend fun admitSideEffect(category: String) {
    currentCoroutineContext().ensureActive()
    val admission = currentCoroutineContext()[SideEffectAdmission] ?: return
    if (!admission.check(category)) throw CancellationException("Not admitted: $category")
}
