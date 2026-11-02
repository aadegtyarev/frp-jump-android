package com.termux.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
