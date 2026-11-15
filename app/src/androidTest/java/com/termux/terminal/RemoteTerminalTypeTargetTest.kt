package com.termux.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.RemoteTerminal.TypeOutcome
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The reconnect guard of [RemoteTerminal.type]: typing never reaches a connection other than the one its target was taken on. */
@RunWith(AndroidJUnit4::class)
class RemoteTerminalTypeTargetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var terminal: RemoteTerminal
    private val peers = mutableListOf<PipedOutputStream>()
    private val inputs = mutableListOf<PipedInputStream>()
    private val ended = mutableListOf<CountDownLatch>()

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync { block() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("timed out waiting for the terminal writer", condition())
    }
    private fun client() = Proxy.newProxyInstance(
        TerminalSessionClient::class.java.classLoader, arrayOf(TerminalSessionClient::class.java),
    ) { _, method, _ -> when (method.name) { "hashCode" -> 0; "equals" -> false; "toString" -> "TypeTargetTestClient"; else -> null } } as TerminalSessionClient

    /** A synthetic connection: pumps a fresh pipe into [terminal] and writes typed bytes to the returned stream. */
    private fun connect(): ByteArrayOutputStream {
        val input = PipedInputStream()
        val peer = PipedOutputStream(input)
        inputs += input; peers += peer
        val out = ByteArrayOutputStream()
        val end = CountDownLatch(1)
        ended += end
        main { terminal.pump(input, out) { end.countDown() } }
        return out
    }

    @Before fun setUp() = main { terminal = RemoteTerminal(client()) }

    @After fun tearDown() {
        main { terminal.finish() }
        peers.forEach { runCatching { it.close() } }
        inputs.forEach { runCatching { it.close() } }
        // Bounded: every pump thread must end once its pipe is closed.
        ended.forEach { assertTrue("pump thread did not end", it.await(5, TimeUnit.SECONDS)) }
    }

    private fun bytes(out: ByteArrayOutputStream) = synchronized(out) { out.toString("UTF-8") }

    @Test fun freshTargetTypesExactlyItsBytes() {
        val out = connect()
        val target = terminal.captureInputTarget()
        assertNotNull(target)
        assertTrue(terminal.isCurrent(target!!))
        assertEquals(TypeOutcome.QUEUED, terminal.type("ls -l".toByteArray(), target))
        await { out.size() == 5 }
        assertEquals("ls -l", bytes(out))
        assertTrue(terminal.isCurrent(target))
    }

    @Test fun pausedConnectionRejectsTheOldTarget() {
        val out = connect()
        val target = terminal.captureInputTarget()!!
        assertEquals(TypeOutcome.QUEUED, terminal.type("a".toByteArray(), target))
        await { out.size() == 1 }
        main { terminal.pause() }
        assertFalse(terminal.isCurrent(target))
        assertEquals(TypeOutcome.REJECTED, terminal.type("late".toByteArray(), target))
        assertNull(terminal.captureInputTarget())
        // Nothing queued, so there is nothing to wait for; a short settle is enough for a wrongly queued write to show.
        Thread.sleep(100)
        assertEquals("a", bytes(out))
    }

    @Test fun reconnectedConnectionRejectsTheOldTargetAndTakesANewOne() {
        val oldOut = connect()
        val oldTarget = terminal.captureInputTarget()!!
        main { terminal.pause() }
        val newOut = connect()
        assertFalse(terminal.isCurrent(oldTarget))
        assertEquals(TypeOutcome.REJECTED, terminal.type("stale".toByteArray(), oldTarget))
        val newTarget = terminal.captureInputTarget()!!
        assertTrue(terminal.isCurrent(newTarget))
        assertFalse(terminal.isCurrent(oldTarget))
        // The queue is in order: had "stale" gone in, it would reach the new stream before "fresh".
        assertEquals(TypeOutcome.QUEUED, terminal.type("fresh".toByteArray(), newTarget))
        await { newOut.size() >= 5 }
        assertEquals("fresh", bytes(newOut))
        assertEquals("", bytes(oldOut))
    }

    @Test fun finishedTerminalTakesNoTarget() {
        connect()
        val target = terminal.captureInputTarget()!!
        main { terminal.finish() }
        assertNull(terminal.captureInputTarget())
        assertFalse(terminal.isCurrent(target))
        assertEquals(TypeOutcome.REJECTED, terminal.type("x".toByteArray(), target))
    }
}
