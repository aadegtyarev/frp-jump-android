package ru.adsrv.frpjump.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** A batch of downloads or shares: two at a time, the rest waiting, each cancelable while it waits. */
class TransferSlotsTest {
    @Test
    fun `no more than two run at once, and all of them run`() = runBlocking {
        val slots = TransferSlots()
        val now = AtomicInteger()
        val most = AtomicInteger()
        val started = AtomicInteger()
        val jobs = (1..6).map {
            launch(Dispatchers.Default) {
                slots.run(onStart = { started.incrementAndGet() }) {
                    most.accumulateAndGet(now.incrementAndGet()) { a, b -> maxOf(a, b) }
                    Thread.sleep(20)
                    now.decrementAndGet()
                }
            }
        }
        withTimeout(10_000) { jobs.joinAll() }
        assertEquals(2, most.get())
        assertEquals(6, started.get())
    }

    @Test
    fun `a waiting one is cancelled before it starts and frees nothing it did not take`() = runBlocking {
        val slots = TransferSlots()
        val release = CompletableDeferred<Unit>()
        val running = (1..2).map { launch { slots.run(onStart = {}) { release.await() } } }
        var waitingStarted = false
        val waiting = launch { slots.run(onStart = { waitingStarted = true }) {} }
        repeat(5) { yield() }
        waiting.cancel()
        waiting.join()
        assertFalse(waitingStarted)
        release.complete(Unit)
        running.joinAll()
        // Both slots are free again.
        var after = 0
        listOf(launch { slots.run(onStart = { after++ }) {} }, launch { slots.run(onStart = { after++ }) {} }).joinAll()
        assertEquals(2, after)
        assertTrue(waiting.isCancelled)
    }
}
