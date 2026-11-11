package ru.adsrv.frpjump.agent

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Every wait is a real latch with a failure bound; no sleeps are used as synchronisation and no thread is interrupted. */
class BlockingCancellableTest {
    /** Negative observation: a caller that wrongly finishes before its blocking call returns would do so well inside this window. */
    private suspend fun assertHeld(job: Job) = assertNull("the caller must still wait for the blocking call", withTimeoutOrNull(300) { job.join() })
    private fun hold(latch: CountDownLatch) = check(latch.await(5, TimeUnit.SECONDS)) { "test latch not released" }
    private fun callerDispatcher(name: String) = Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = true } }.asCoroutineDispatcher()

    @Test fun `cancel calls onCancel promptly on an IO thread and returns only after the block returned`() = runBlocking {
        val caller = callerDispatcher("blocking-caller")
        try {
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val cancelled = CountDownLatch(1)
            val callerThread = AtomicReference<Thread>(); val cancelThread = AtomicReference<Thread>()
            val job = launch(caller) {
                callerThread.set(Thread.currentThread())
                blockingCancellable({ cancelThread.set(Thread.currentThread()); cancelled.countDown() }) { entered.countDown(); hold(release) }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            job.cancel()
            assertTrue("onCancel must run promptly", cancelled.await(3, TimeUnit.SECONDS))
            assertNotSame(callerThread.get(), cancelThread.get())
            assertTrue(cancelThread.get().name, cancelThread.get().name.startsWith("DefaultDispatcher-worker"))
            assertHeld(job)
            release.countDown()
            withTimeout(3_000) { job.join() }
            assertTrue(job.isCancelled)
        } finally { caller.close() }
    }

    @Test fun `normal completion never calls onCancel and returns the result`() = runBlocking {
        val cancels = AtomicInteger()
        assertEquals("result", blockingCancellable({ cancels.incrementAndGet() }) { "result" })
        assertEquals(0, cancels.get())
    }

    @Test fun `exception in block propagates unwrapped and onCancel is not called`() = runBlocking {
        val cancels = AtomicInteger()
        val failure = IOException("synthetic")
        try { blockingCancellable({ cancels.incrementAndGet() }) { throw failure }; fail() } catch (thrown: IOException) { assertEquals("synthetic", thrown.message) }
        assertEquals(0, cancels.get())
    }

    @Test fun `already cancelled coroutine does not start the block`() = runBlocking {
        val started = AtomicInteger(); val cancels = AtomicInteger()
        val thrown = AtomicReference<Throwable>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().job.cancel()
            try { blockingCancellable({ cancels.incrementAndGet() }) { started.incrementAndGet() } } catch (failure: Throwable) { thrown.set(failure); throw failure }
        }
        job.join()
        assertTrue(thrown.get() is CancellationException); assertEquals(0, started.get()); assertEquals(0, cancels.get())
    }

    /** Cancels [target] when a coroutine on Dispatchers.IO starts on this thread: exactly when the UNDISPATCHED signal begins, after the leading ensureActive(). */
    private class CancelOnIoStart(val target: AtomicReference<Job?>) : ThreadContextElement<Unit> {
        companion object Key : CoroutineContext.Key<CancelOnIoStart>
        override val key: CoroutineContext.Key<*> get() = Key
        private val armed = AtomicBoolean(true)
        override fun updateThreadContext(context: CoroutineContext) {
            if (context[ContinuationInterceptor] === Dispatchers.IO && armed.getAndSet(false)) target.get()?.cancel()
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {}
    }

    @Test fun `onCancel never runs on a non IO caller thread even when cancel lands before the signal suspends`() = runBlocking {
        val caller = callerDispatcher("blocking-caller-window")
        try {
            repeat(50) {
                val target = AtomicReference<Job?>(); val cancelThread = AtomicReference<String?>(); val started = AtomicInteger()
                val job = launch(caller + CancelOnIoStart(target), start = CoroutineStart.LAZY) {
                    blockingCancellable({ cancelThread.set(Thread.currentThread().name) }) { started.incrementAndGet() }
                }
                target.set(job); job.start(); job.join()
                assertEquals(0, started.get())
                val name = cancelThread.get()
                assertNotNull("onCancel must still run for a cancel in this window", name)
                assertTrue("onCancel must run on an IO worker, not on the caller: $name", name!!.startsWith("DefaultDispatcher-worker"))
            }
        } finally { caller.close() }
    }

    @Test fun `onCancel never runs on the caller thread even when the scope is already cancelled`() = runBlocking {
        val caller = callerDispatcher("blocking-caller-cancelled")
        try {
            val cancelThread = AtomicReference<String?>(); val started = AtomicInteger()
            val job = launch(caller) {
                currentCoroutineContext().job.cancel()
                blockingCancellable({ cancelThread.set(Thread.currentThread().name) }) { started.incrementAndGet() }
            }
            job.join()
            assertEquals(0, started.get())
            assertNull("an already cancelled caller needs no onCancel", cancelThread.get())
        } finally { caller.close() }
    }

    @Test fun `exception thrown by block after cancellation surfaces as CancellationException`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val thrown = AtomicReference<Throwable>()
        val job = launch(Dispatchers.Default) {
            try { blockingCancellable({ release.countDown() }) { entered.countDown(); hold(release); throw IOException("Canceled") } }
            catch (failure: Throwable) { thrown.set(failure); throw failure }
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        job.cancel(); withTimeout(3_000) { job.join() }
        assertTrue(thrown.get().toString(), thrown.get() is CancellationException)
    }

    @Test fun `result of a block that returns after cancellation is discarded`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val thrown = AtomicReference<Throwable>(); val returned = AtomicReference<String>()
        val job = launch(Dispatchers.Default) {
            try { returned.set(blockingCancellable({ cancelled.countDown() }) { entered.countDown(); hold(release); "late result" }) }
            catch (failure: Throwable) { thrown.set(failure); throw failure }
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        job.cancel(); assertTrue(cancelled.await(3, TimeUnit.SECONDS)); release.countDown(); withTimeout(3_000) { job.join() }
        assertNull(returned.get()); assertTrue(thrown.get() is CancellationException)
    }

    @Test fun `onCancel failure is swallowed`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val failed = CountDownLatch(1)
        val thrown = AtomicReference<Throwable>()
        val job = launch(Dispatchers.Default) {
            try { blockingCancellable({ failed.countDown(); throw IllegalStateException("private-detail") }) { entered.countDown(); hold(release) } }
            catch (failure: Throwable) { thrown.set(failure); throw failure }
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        job.cancel(); assertTrue(failed.await(3, TimeUnit.SECONDS)); release.countDown(); withTimeout(3_000) { job.join() }
        assertTrue(thrown.get().toString(), thrown.get() is CancellationException)
    }

    @Test fun `onCancel runs at most once`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val cancels = AtomicInteger(); val first = CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            blockingCancellable({ cancels.incrementAndGet(); first.countDown() }) { entered.countDown(); hold(release) }
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        job.cancel(); job.cancel(); assertTrue(first.await(3, TimeUnit.SECONDS)); job.cancel()
        release.countDown(); withTimeout(3_000) { job.join() }
        assertEquals(1, cancels.get())
        // A timeout and a later explicit cancel are the same single cancellation.
        val timeoutCancels = AtomicInteger(); val gate = CountDownLatch(1)
        try { withTimeout(50) { blockingCancellable({ timeoutCancels.incrementAndGet(); gate.countDown() }) { hold(gate) } }; fail() } catch (_: TimeoutCancellationException) { }
        assertEquals(1, timeoutCancels.get())
    }

    @Test fun `cancel racing completion neither throws nor loses a needed onCancel`() = runBlocking {
        // Immediate block: whichever wins, the only allowed outcomes are a result or CancellationException, and onCancel runs at most once.
        repeat(500) { iteration ->
            val cancels = AtomicInteger(); val thrown = AtomicReference<Throwable?>()
            val job = launch(Dispatchers.Default) {
                try { blockingCancellable({ cancels.incrementAndGet() }) { iteration } } catch (failure: Throwable) { thrown.set(failure); throw failure }
            }
            if (iteration % 2 == 0) job.cancel() else yield().also { job.cancel() }
            job.join()
            thrown.get()?.let { assertTrue("$iteration $it", it is CancellationException) }
            assertTrue("$iteration", cancels.get() <= 1)
        }
        // A block that is still running at cancel always gets its onCancel.
        repeat(100) { iteration ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val cancels = AtomicInteger(); val cancelled = CountDownLatch(1)
            val job = launch(Dispatchers.Default) { blockingCancellable({ cancels.incrementAndGet(); cancelled.countDown() }) { entered.countDown(); hold(release) } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            job.cancel(); assertTrue("$iteration", cancelled.await(3, TimeUnit.SECONDS)); release.countDown(); job.join()
            assertEquals(1, cancels.get())
        }
    }

    @Test fun `works without ambient admission`() = runBlocking {
        assertNull(currentCoroutineContext()[SideEffectAdmission])
        assertEquals(42, blockingCancellable({ }) { 42 })
        admitSideEffect("anything") // No element: nothing is checked.
    }

    @Test fun `uncooperative block holds the caller until it returns`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val signalled = CountDownLatch(1)
        val job = launch(Dispatchers.Default) { blockingCancellable({ signalled.countDown() /* does not make the block return */ }) { entered.countDown(); hold(release) } }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        job.cancel(); assertTrue(signalled.await(3, TimeUnit.SECONDS))
        assertHeld(job)
        release.countDown(); withTimeout(3_000) { job.join() }
        assertTrue(job.isCancelled)
    }
}
