package ru.adsrv.frpjump.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** No real sleeps: the settle wait is a gate the test opens. */
class WindowChangeSenderTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val sends = mutableListOf<Pair<Int, Int>>()
    private val waits = mutableListOf<CompletableDeferred<Unit>>()
    private val sender = WindowChangeSender(scope, send = { c, r -> sends += c to r; true },
        context = Dispatchers.Unconfined, wait = { ms ->
            assertEquals(100L, ms)
            CompletableDeferred<Unit>().also { waits += it }.await()
        })

    @After fun stop() { job.cancel() }

    private fun settle() { waits.lastOrNull()?.complete(Unit) }

    @Test fun manyChangesWithinTheWindowSendOnlyTheLast() {
        for (rows in 10..18) sender.request(80, rows)
        assertTrue(sends.isEmpty())
        settle()
        assertEquals(listOf(80 to 18), sends)
    }

    @Test fun changesApartSendOneEach() {
        sender.request(80, 20); settle()
        sender.request(80, 12); settle()
        assertEquals(listOf(80 to 20, 80 to 12), sends)
    }

    @Test fun sameSizeAsSentSendsNothing() {
        sender.sent(80, 24)
        sender.request(80, 24); settle()
        sender.request(80, 12); sender.request(80, 24); settle()
        assertTrue(sends.isEmpty())
        sender.request(80, 12); settle()
        sender.request(80, 12); settle()
        assertEquals(listOf(80 to 12), sends)
    }

    @Test fun closeDuringTheWaitSendsNothing() {
        sender.request(80, 12)
        sender.close()
        settle()
        sender.request(80, 13); settle()
        assertTrue(sends.isEmpty())
    }

    @Test fun changeAfterTheSendFiresAgain() {
        sender.request(80, 12); settle()
        sender.request(80, 13); settle()
        sender.request(81, 13); settle()
        assertEquals(listOf(80 to 12, 80 to 13, 81 to 13), sends)
    }

    @Test fun failedSendIsRetriedByTheNextRequestForTheSameSize() {
        var ok = false
        val s = WindowChangeSender(scope, send = { c, r -> sends += c to r; ok },
            context = Dispatchers.Unconfined, wait = { CompletableDeferred<Unit>().also { waits += it }.await() })
        s.request(80, 12); settle()
        ok = true
        s.request(80, 12); settle()
        assertEquals(listOf(80 to 12, 80 to 12), sends)
    }
}
