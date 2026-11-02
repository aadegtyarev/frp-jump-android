package ru.adsrv.frpjump.ssh.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ProbeVisibilityTest {
    @Test fun `an observer started after EOF terminates without emitting visibility`() = runBlocking {
        val values = mutableListOf<Boolean>()
        val job = launch { probeVisibilityWhileCurrent(MutableStateFlow(true), MutableStateFlow(1)) { false }.collect { values += it } }
        withTimeout(3000) { job.join() }
        assertTrue(job.isCompleted)
        assertTrue(values.isEmpty())
    }

    @Test fun `closing state terminates observer even without a visibility change`() = runBlocking {
        val states = MutableStateFlow(0)
        val first = CompletableDeferred<Unit>()
        val values = mutableListOf<Boolean>()
        val job = launch {
            probeVisibilityWhileCurrent(MutableStateFlow(true), states) { states.value == 0 }.collect {
                values += it
                first.complete(Unit)
            }
        }
        withTimeout(3000) { first.await() }
        states.value = 1
        withTimeout(3000) { job.join() }
        assertTrue(job.isCompleted)
        assertEquals(listOf(true), values)
    }

    @Test fun `background and resume are emitted while the same connection remains current`() = runBlocking {
        val visible = MutableStateFlow(true)
        val states = MutableStateFlow(0)
        val values = Channel<Boolean>(3)
        val job = launch { probeVisibilityWhileCurrent(visible, states) { states.value == 0 }.collect { values.send(it) } }
        try {
            assertTrue(withTimeout(3000) { values.receive() })
            visible.value = false
            assertFalse(withTimeout(3000) { values.receive() })
            visible.value = true
            assertTrue(withTimeout(3000) { values.receive() })
        } finally { job.cancelAndJoin(); values.close() }
    }
}
