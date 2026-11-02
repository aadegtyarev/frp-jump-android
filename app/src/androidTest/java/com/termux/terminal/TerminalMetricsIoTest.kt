package com.termux.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real queues/handlers, synthetic bytes, no SSH or enrolled profile. */
@RunWith(AndroidJUnit4::class)
class TerminalMetricsIoTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var terminal: RemoteTerminal
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync { block() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("timed out waiting for terminal worker", condition())
    }
    private fun client() = Proxy.newProxyInstance(
        TerminalSessionClient::class.java.classLoader, arrayOf(TerminalSessionClient::class.java),
    ) { _, method, _ -> when (method.name) { "hashCode" -> 0; "equals" -> false; "toString" -> "MetricsTestClient"; else -> null } } as TerminalSessionClient

    @Test fun queuedInputCannotMoveToAReplacementOutput() {
        val oldInput = PipedInputStream()
        val oldPeer = PipedOutputStream(oldInput)
        val newInput = PipedInputStream()
        val newPeer = PipedOutputStream(newInput)
        val oldOutput = ByteArrayOutputStream()
        val newOutput = ByteArrayOutputStream()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            main {
                terminal = RemoteTerminal(client())
                terminal.beforeWrite = { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                terminal.pump(oldInput, oldOutput) { }
                terminal.session.write("stale\r")
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            main {
                terminal.session.write("backlog\r")
                terminal.pause()
                terminal.pump(newInput, newOutput) { }
            }
            release.countDown()
            main { terminal.session.write("fresh\r") }
            await { newOutput.toString("UTF-8").contains("fresh") }
            assertEquals("fresh\r", newOutput.toString("UTF-8"))
        } finally {
            release.countDown()
            oldPeer.close(); newPeer.close(); oldInput.close(); newInput.close()
            if (::terminal.isInitialized) main { terminal.finish() }
        }
    }

    @Test fun idleWriterWakesOnNewConnectionInputAndTerminatesOnClose() {
        val inputs = listOf(PipedInputStream(), PipedInputStream())
        val peers = inputs.map(::PipedOutputStream)
        val outputs = listOf(ByteArrayOutputStream(), ByteArrayOutputStream())
        val writer = AtomicReference<Thread>()
        try {
            main {
                terminal = RemoteTerminal(client())
                terminal.beforeWrite = { _, _ -> writer.set(Thread.currentThread()) }
                terminal.pump(inputs[0], outputs[0]) { }
                terminal.session.write("a")
            }
            await { outputs[0].size() == 1 && writer.get()?.state == Thread.State.WAITING }
            writer.get().interrupt()
            main {
                terminal.pause()
                terminal.pump(inputs[1], outputs[1]) { }
                terminal.session.write("b")
            }
            await { outputs[1].size() == 1 }
            assertEquals("a", outputs[0].toString("UTF-8"))
            assertEquals("b", outputs[1].toString("UTF-8"))
            main { terminal.finish() }
            await { writer.get()?.state == Thread.State.TERMINATED }
        } finally {
            peers.forEach { it.close() }; inputs.forEach { it.close() }
            if (::terminal.isInitialized) main { terminal.finish() }
        }
    }

    @Test fun aLargePasteCannotBeSplitByConnectionHandover() = largePasteHandover(false)

    @Test fun aLargePasteWithEnterCannotBeSplitByConnectionHandover() = largePasteHandover(true)

    private fun largePasteHandover(enter: Boolean) {
        val inputs = listOf(PipedInputStream(), PipedInputStream())
        val peers = inputs.map(::PipedOutputStream)
        val outputs = listOf(ByteArrayOutputStream(), ByteArrayOutputStream())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pasted = CountDownLatch(1)
        val switching = CountDownLatch(1)
        val switched = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val accepted = AtomicBoolean(false)
        var producer: Thread? = null
        var handover: Thread? = null
        try {
            main {
                terminal = RemoteTerminal(client())
                terminal.beforeWrite = { _, _ ->
                    if (first.getAndSet(false)) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                }
                terminal.pump(inputs[0], outputs[0]) { }
            }
            producer = Thread {
                main {
                    val captured = terminal.captureInputTarget()!!
                    accepted.set(if (enter) terminal.paste("x".repeat(16000), captured, true) else terminal.paste("x".repeat(16000)))
                }
                pasted.countDown()
            }.apply { isDaemon = true; start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            handover = Thread {
                switching.countDown()
                terminal.pause()
                terminal.pump(inputs[1], outputs[1]) { }
                switched.countDown()
            }.apply { isDaemon = true; start() }
            assertTrue(switching.await(5, TimeUnit.SECONDS))
            assertFalse("handover must wait for the paste enqueue", switched.await(50, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(pasted.await(5, TimeUnit.SECONDS))
            assertTrue(switched.await(5, TimeUnit.SECONDS))
            assertTrue(accepted.get())
            main { terminal.session.write("fresh") }
            await { outputs[1].toString("UTF-8").contains("fresh") }
            assertEquals("fresh", outputs[1].toString("UTF-8"))
            assertTrue(outputs[0].size() > 0)
            val old = outputs[0].toString("UTF-8")
            assertTrue(old.removeSuffix("\r").all { it == 'x' })
            assertTrue(old.count { it == '\r' } <= if (enter) 1 else 0)
        } finally {
            release.countDown()
            // Also unblock a failed test's producer if the worker died.
            if (::terminal.isInitialized) terminal.session.mTerminalToProcessIOQueue.close()
            producer?.join(5000); handover?.join(5000)
            peers.forEach { it.close() }; inputs.forEach { it.close() }
            if (::terminal.isInitialized) main { terminal.finish() }
        }
    }

    @Test fun capturedPasteAddsExactlyOneEnterAfterBracketedPayloadOnlyWhenRequested() {
        val input = PipedInputStream()
        val peer = PipedOutputStream(input)
        val output = ByteArrayOutputStream()
        try {
            main {
                terminal = RemoteTerminal(client())
                terminal.pump(input, output) { }
                val captured = terminal.captureInputTarget()!!
                assertFalse(terminal.paste("", captured, true))
                assertFalse(terminal.paste(" \u001b\u0080\u0008", captured, true))
                assertTrue(terminal.paste("plain", captured, false))
            }
            await { output.size() == 5 }
            assertEquals("plain", output.toString("UTF-8"))
            output.reset()
            main {
                val mode = "\u001b[?2004h".toByteArray()
                terminal.session.emulator.append(mode, mode.size)
                assertTrue(terminal.paste("wrapped", terminal.captureInputTarget()!!, true))
            }
            await { output.toString("UTF-8").endsWith("\r") }
            assertEquals("\u001b[200~wrapped\u001b[201~\r", output.toString("UTF-8"))
        } finally {
            peer.close(); input.close()
            if (::terminal.isInitialized) main { terminal.finish() }
        }
    }

    @Test fun capturedTargetCannotBeUsedByAnotherTerminalEvenWithSharedOutput() {
        val inputs = listOf(PipedInputStream(), PipedInputStream())
        val peers = inputs.map(::PipedOutputStream)
        val output = ByteArrayOutputStream()
        var other: RemoteTerminal? = null
        try {
            main {
                terminal = RemoteTerminal(client())
                val second = RemoteTerminal(client()).also { other = it }
                terminal.pump(inputs[0], output) { }
                second.pump(inputs[1], output) { }
                assertFalse(second.paste("wrong terminal", terminal.captureInputTarget()!!, true))
                assertTrue(second.paste("right", second.captureInputTarget()!!, true))
            }
            await { output.size() >= 6 }
            assertEquals("right\r", output.toString("UTF-8"))
        } finally {
            peers.forEach { it.close() }; inputs.forEach { it.close() }
            main { other?.finish(); if (::terminal.isInitialized) terminal.finish() }
        }
    }

    @Test fun capturedTargetCannotSurviveReconnectEvenWhenOutputObjectIsReused() {
        val inputs = listOf(PipedInputStream(), PipedInputStream())
        val peers = inputs.map(::PipedOutputStream)
        val output = ByteArrayOutputStream()
        try {
            main {
                terminal = RemoteTerminal(client())
                terminal.pump(inputs[0], output) { }
                val old = terminal.captureInputTarget()!!
                terminal.pause()
                terminal.pump(inputs[1], output) { }
                assertFalse(terminal.paste("must not execute", old, true))
                assertTrue(terminal.paste("new", terminal.captureInputTarget()!!, true))
            }
            await { output.size() >= 4 }
            assertEquals("new\r", output.toString("UTF-8"))
            main {
                val target = terminal.captureInputTarget()!!
                terminal.finish()
                assertNull(terminal.captureInputTarget())
                assertFalse(terminal.paste("closed", target, true))
            }
        } finally {
            peers.forEach { it.close() }; inputs.forEach { it.close() }
            if (::terminal.isInitialized) main { terminal.finish() }
        }
    }

    @Test fun recordsTrafficAndWriterWaitWithoutChangingBytesOrCountingLocalNotes() {
        val incoming = PipedInputStream()
        val peer = PipedOutputStream(incoming)
        val output = ByteArrayOutputStream()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            main {
                terminal = RemoteTerminal(client())
                terminal.metrics.setObserving(true)
                terminal.beforeWrite = { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                terminal.pump(incoming, output) { }
            }
            val text = "remote test\r\n".toByteArray()
            peer.write(text); peer.flush()
            await { terminal.metrics.snapshot().incomingProcessing.count > 0 }
            assertEquals(text.size.toLong(), terminal.metrics.snapshot().receivedBytes)
            main {
                terminal.metrics.ownerInput()
                terminal.session.write("hello".toByteArray(), 0, 5)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            Thread.sleep(25)
            release.countDown()
            await { terminal.metrics.snapshot().sentBytes == 5L }
            val sent = terminal.metrics.snapshot()
            assertTrue(sent.beforeWrite.lastMs!! >= 20)
            assertTrue(sent.inputToSend.lastMs!! >= sent.beforeWrite.lastMs!!)
            assertEquals("hello", output.toString("UTF-8"))
            val incomingSamples = sent.incomingProcessing.count
            terminal.note("local note")
            await {
                var rendered = false
                main { rendered = terminal.session.emulator.screen.transcriptText.contains("local note") }
                rendered
            }
            assertEquals(text.size.toLong(), terminal.metrics.snapshot().receivedBytes)
            assertEquals(incomingSamples, terminal.metrics.snapshot().incomingProcessing.count)
            terminal.metrics.setObserving(false)
            peer.write(text); peer.flush()
            await { terminal.metrics.snapshot().receivedBytes == 2L * text.size }
            instrumentation.waitForIdleSync()
            assertEquals(incomingSamples, terminal.metrics.snapshot().incomingProcessing.count)
        } finally {
            release.countDown()
            peer.close(); incoming.close()
            if (::terminal.isInitialized) main { terminal.finish() }
        }
    }
}
