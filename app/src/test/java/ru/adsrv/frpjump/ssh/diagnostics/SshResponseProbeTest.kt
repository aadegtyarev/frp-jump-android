package ru.adsrv.frpjump.ssh.diagnostics

import net.schmizz.sshj.connection.ConnectionException
import org.junit.Assert.*
import org.junit.Test

class SshResponseProbeTest {
    private var at = 0L
    private val metrics = TerminalMetrics(1000) { at }.apply { setObserving(true); connectionOpened() }
    private val probe = SshResponseProbe(metrics) { at }
    private var sends = 0
    private var current = true
    private class Request(var done: Boolean = false, val answer: (Request) -> ProbeReply) : PendingSshProbe {
        override val complete get() = done
        override fun await(timeoutMs: Long): ProbeReply { assertEquals(3000L, timeoutMs); return answer(this) }
    }
    private fun advance(ms: Long) { at += ms * 1_000_000 }
    private fun sample(request: Request) = probe.sampleBlocking(SshProbeSender { sends++; request }) { current }
    private fun reply() = Request { advance(25); it.done = true; ProbeReply.REPLIED }

    @Test fun `successful response is measured and attempts are throttled`() {
        sample(reply())
        assertEquals(25.0, metrics.snapshot().response.lastMs!!, 0.001)
        sample(reply())
        assertEquals(1, sends)
        advance(20000)
        sample(reply())
        assertEquals(2, sends)
    }

    @Test fun `timeout blocks more sends until pending reply completes`() {
        val stalled = Request { advance(3000); ProbeReply.TIMEOUT }
        sample(stalled)
        repeat(5) { advance(20000); sample(reply()) }
        assertEquals(1, sends)
        assertEquals(1L, metrics.snapshot().probeTimeouts)
        assertEquals(0L, metrics.snapshot().response.count)
        stalled.done = true
        sample(reply())
        assertEquals(2, sends)
        assertEquals(1L, metrics.snapshot().response.count)
        assertEquals(25.0, metrics.snapshot().response.lastMs!!, 0.001)
    }

    @Test fun `background prevents sends and discards an in-flight response`() {
        metrics.setObserving(false)
        sample(reply())
        assertEquals(0, sends)
        metrics.setObserving(true)
        sample(Request { metrics.setObserving(false); advance(20); it.done = true; ProbeReply.REPLIED })
        assertEquals(0L, metrics.snapshot().response.count)
    }

    @Test fun `resume before completion still cannot revive the old observation`() {
        sample(Request {
            metrics.setObserving(false); metrics.setObserving(true)
            advance(20); it.done = true; ProbeReply.REPLIED
        })
        assertEquals(0L, metrics.snapshot().response.count)
    }

    @Test fun `new connection invalidates old pending request without altering it`() {
        val old = Request { ProbeReply.TIMEOUT }
        sample(old)
        metrics.connectionClosed(); metrics.connectionOpened()
        sample(reply())
        assertEquals(2, sends)
        assertFalse(old.complete)
        assertEquals(1L, metrics.snapshot().response.count)
    }

    @Test fun `client identity and disconnect guard apply before send and after reply`() {
        current = false
        sample(reply())
        assertEquals(0, sends)
        current = true
        sample(Request { current = false; it.done = true; ProbeReply.REPLIED })
        assertEquals(0L, metrics.snapshot().response.count)
    }

    @Test fun `send exception does not disconnect or retry immediately`() {
        probe.sampleBlocking(SshProbeSender { sends++; throw IllegalStateException() }) { true }
        sample(reply())
        assertEquals(1, sends)
        assertTrue(metrics.snapshot().connected)
        assertEquals(1L, metrics.snapshot().probeFailures)
    }

    @Test fun `unknown await failure with pending request is not retried`() {
        sample(Request { throw IllegalStateException() })
        advance(20000)
        sample(reply())
        assertEquals(1, sends)
        assertEquals(1L, metrics.snapshot().probeFailures)
        assertNull(metrics.snapshot().response.meanMs)
    }

    @Test fun `only explicit pinned SSHJ request rejection counts as a negative reply`() {
        val name = "global req for keepalive@openssh.com"
        assertTrue(SshjResponseProbe.isNegativeReply(ConnectionException("Global request [$name] failed"), name))
        assertFalse(SshjResponseProbe.isNegativeReply(ConnectionException("connection closed"), name))
        assertFalse(SshjResponseProbe.isNegativeReply(ConnectionException("Global request [another request] failed"), name))
        assertFalse(SshjResponseProbe.isNegativeReply(ConnectionException(IllegalStateException("Global request [$name] failed")), name))
    }
}
