package ru.adsrv.frpjump.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class TransferSnapshotsTest {
    @Test
    fun `cancel is immediate and a deferred join leaves newer work alone`() = runBlocking {
        val jobs = TransferJobs()
        val old = jobs.track(launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) { awaitCancellation() })
        val stopping = jobs.cancelCurrent()
        assertTrue(old.isCancelled)
        val current = jobs.track(launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) { awaitCancellation() })
        try {
            stopping.joinAll()
            assertTrue(current.isActive)
            assertEquals(listOf(old), stopping)
        } finally {
            jobs.cancelAndJoin()
        }
    }

    @Test
    fun `later close also waits for a previously cancelled job still cleaning up`() = runBlocking {
        val jobs = TransferJobs()
        val cleaning = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val old = jobs.track(launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleaning.complete(Unit)
                    allowCleanup.await()
                }
            }
        })
        val leaving = jobs.cancelCurrent()
        cleaning.await()
        val current = jobs.track(launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) { awaitCancellation() })
        try {
            val closing = jobs.cancelCurrent()
            assertEquals(setOf(old, current), closing.toSet())
            assertEquals(listOf(old), leaving)
            assertFalse(old.isCompleted)
            assertTrue(current.isCancelled)
            allowCleanup.complete(Unit)
            closing.joinAll()
            assertTrue(old.isCompleted)
            assertTrue(jobs.cancelCurrent().isEmpty())
        } finally {
            allowCleanup.complete(Unit)
            jobs.cancelAndJoin()
        }
    }

    @Test
    fun `an empty leave snapshot never acquires work created later`() = runBlocking {
        val jobs = TransferJobs()
        val empty = jobs.cancelCurrent()
        val current = jobs.track(launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) { awaitCancellation() })
        try {
            empty.joinAll()
            assertTrue(current.isActive)
        } finally {
            jobs.cancelAndJoin()
        }
    }

    @Test
    fun `a job that completes while starting is no longer tracked`() = runBlocking {
        val jobs = TransferJobs()
        val done = jobs.track(launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {})
        done.join()
        assertTrue(jobs.cancelCurrent().isEmpty())
    }
}
