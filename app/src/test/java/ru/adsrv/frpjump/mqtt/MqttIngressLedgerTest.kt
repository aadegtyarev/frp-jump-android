package ru.adsrv.frpjump.mqtt

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.settings.MqttIngressLimits
import ru.adsrv.frpjump.settings.MqttOverflow

class MqttIngressLedgerTest {
    private fun limits(bytes: Int = 4096, messages: Int = 2, overflow: MqttOverflow = MqttOverflow.DROP_INCOMING) =
        MqttIngressLimits(payloadBytes = 1024, queueBytes = bytes, queueMessages = messages, overflow = overflow)
    private fun token(admission: MqttIngressBudget.Admission) =
        (admission as MqttIngressBudget.Admission.Accepted).reservation

    @Test fun `payload ceiling and exact logical byte boundary include UTF16 topic and fixed charge`() {
        val bytes = 1024 + MqttIngressLimits.MESSAGE_CHARGE + "т😀".length * 2
        val budget = MqttIngressBudget(limits(bytes))
        val lease = budget.newLease()
        assertTrue(budget.reserve(lease, "x", 1025) is MqttIngressBudget.Admission.Rejected)
        val accepted = token(budget.reserve(lease, "т😀", 1024))
        assertEquals(MqttIngressBudget.Usage(1, bytes.toLong()), budget.usage())
        assertTrue(budget.reserve(lease, "x", 0) is MqttIngressBudget.Admission.Rejected)
        accepted.release()
        accepted.release()
        assertEquals(MqttIngressBudget.Usage(0, 0), budget.usage())
    }

    @Test fun `message count and byte ceiling reject independently`() {
        val count = MqttIngressBudget(limits(messages = 1))
        val countLease = count.newLease()
        val one = token(count.reserve(countLease, "x", 0))
        assertTrue(count.reserve(countLease, "x", 0) is MqttIngressBudget.Admission.Rejected)
        one.release()
        val bytes = MqttIngressBudget(limits(bytes = 2048, messages = 100))
        val byteLease = bytes.newLease()
        val large = token(bytes.reserve(byteLease, "x", 1024))
        assertTrue(bytes.reserve(byteLease, "x", 1024) is MqttIngressBudget.Admission.Rejected)
        large.release()
        assertEquals(MqttIngressBudget.Usage(0, 0), bytes.usage())
    }

    @Test fun `generation change retains old queued byte charges and rejects stale producers`() {
        val budget = MqttIngressBudget(limits(bytes = 2048))
        val old = budget.newLease()
        val queued = token(budget.reserve(old, "x", 1024))
        val fresh = budget.newLease()
        assertFalse(queued.isCurrent())
        assertTrue(budget.reserve(old, "x", 0) is MqttIngressBudget.Admission.Stale)
        assertTrue(budget.reject(old) is MqttIngressBudget.Admission.Stale)
        assertTrue(budget.reserve(fresh, "x", 1024) is MqttIngressBudget.Admission.Rejected)
        queued.release()
        val current = token(budget.reserve(fresh, "x", 1024))
        assertTrue(current.isCurrent())
        budget.invalidate()
        assertFalse(current.isCurrent())
        assertTrue(budget.reserve(fresh, "x", 0) is MqttIngressBudget.Admission.Stale)
        current.release()
        assertEquals(MqttIngressBudget.Usage(0, 0), budget.usage())
    }

    @Test fun `local stop is notified once and ignores a subsequent flood on that lease`() {
        val budget = MqttIngressBudget(limits(overflow = MqttOverflow.STOP_SESSION))
        val lease = budget.newLease()
        val queued = token(budget.reserve(lease, "x", 1))
        assertTrue((budget.reserve(lease, "x", 2048) as MqttIngressBudget.Admission.Rejected).notifyStop)
        repeat(1000) { assertTrue(budget.reserve(lease, "x", 2048) is MqttIngressBudget.Admission.Stale) }
        assertFalse(queued.isCurrent())
        queued.release()
        assertEquals(MqttIngressBudget.Usage(0, 0), budget.usage())
    }

    @Test fun `concurrent reservations never overspend either counter and release once`() {
        val budget = MqttIngressBudget(limits(bytes = 4096, messages = 3))
        val lease = budget.newLease()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = List(8) {
                pool.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(300) {
                        val admission = budget.reserve(lease, "synthetic", 1024)
                        if (admission is MqttIngressBudget.Admission.Accepted) {
                            val usage = budget.usage()
                            assertTrue(usage.messages in 1..3)
                            assertTrue(usage.bytes in 1L..4096L)
                            admission.reservation.release()
                            admission.reservation.release()
                        }
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(MqttIngressBudget.Usage(0, 0), budget.usage())
        } finally { start.countDown(); pool.shutdownNow() }
    }
}
