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
import kotlinx.coroutines.yield

/**
 * Runs blocking [block] on Dispatchers.IO. If the caller is cancelled while [block] runs, calls [onCancel] promptly on an
 * IO worker (never on a non-IO caller thread such as Main). Returns or throws only after [block] has actually returned.
 * [onCancel] runs at most once and its failures are swallowed; once [block] has returned (or is about to), the decision to
 * call it is closed, but a call already decided may still be running while [block] returns, and the caller waits for it.
 * If the caller was cancelled, the result of [block] is discarded and CancellationException is thrown; an exception from
 * [block] is then only a consequence of the cancellation and is dropped. No thread is interrupted: [onCancel] alone must
 * make a blocking call return.
 */
suspend fun <T> blockingCancellable(onCancel: () -> Unit, block: () -> T): T = coroutineScope {
    currentCoroutineContext().ensureActive()
    val state = AtomicInteger(RUNNING)
    val signal = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally {
            if (state.compareAndSet(RUNNING, CANCELLED)) withContext(NonCancellable + Dispatchers.IO) {
                // The signal may still be running on the caller thread (UNDISPATCHED): force a real hop to an IO worker.
                yield()
                runCatching { onCancel() }
            }
        }
    }
    try {
        val result = try {
            withContext(Dispatchers.IO) {
                // From here on onCancel can no longer start: it never runs after the block has returned.
                try { block() } finally { state.compareAndSet(RUNNING, FINISHED) }
            }
        } catch (failure: Throwable) {
            if (failure !is CancellationException && !isActive) throw CancellationException("Cancelled during blocking call").also { it.addSuppressed(failure) /* best effort: the cancellation cause usually wins */ }
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
