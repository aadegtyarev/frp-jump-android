package ru.adsrv.frpjump.mqtt

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.settings.MqttIngressLimits
import ru.adsrv.frpjump.settings.MqttOverflow
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.SecretBox

/** Bounded allocation proof on the actual scheduler/fake accepted link; no sockets or credentials. */
class MqttIngressBudgetTest {
    // Finite S8a defaults: 1MiB payload, 8MiB queued/in-flight accounted bytes.
    // Deliberately use the existing public API for RED, not a missing budget class/API.
    private val mib = 1024 * 1024
    private val latest = AtomicReference<LinkEvents>()

    @Test fun `oversized incoming payload is dropped before it enters the tree`() = fixture { session, events, scheduler ->
        events.message("synthetic/oversized", ByteArray(mib + 1), false, 0)
        drain(scheduler)
        assertEquals("An incoming payload over the finite default ceiling must be rejected", 1L, session.dropped.value)
        assertNull(session.tree.node(MqttPath.parse("synthetic/oversized")))
        assertEquals(MqttState.Open(MqttVersion.V5), session.state.value)
    }

    @Test fun `queued bytes are bounded long before the existing message count cap`() = fixture { session, events, scheduler ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        scheduler.post {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Synthetic actor blocker was not released" }
        }
        try {
            assertTrue("Actor must be held before producing the bounded burst", entered.await(5, TimeUnit.SECONDS))
            // 8.5MiB body bytes total, 17 messages versus the current count-only cap of10000.
            // This is an assertion-based budget proof, not an attempt to OOM the test JVM.
            repeat(17) { events.message("synthetic/queue/$it", ByteArray(mib / 2), false, 0) }
        } finally { release.countDown() }
        drain(scheduler)
        assertTrue("A bounded queue needs a byte budget, not only a message count", session.dropped.value > 0)
        assertTrue("Overflow must leave an honest incomplete tree, not keep every queued payload", session.tree.topicCount < 17)
        assertEquals(MqttState.Open(MqttVersion.V5), session.state.value)
    }

    @Test fun `ordinary accepted messages still populate the open session`() = fixture { session, events, scheduler ->
        repeat(3) { events.message("synthetic/small/$it", byteArrayOf(it.toByte()), false, 0) }
        drain(scheduler)
        assertEquals(0L, session.dropped.value)
        assertEquals(3, session.tree.topicCount)
        assertArrayEquals(byteArrayOf(2), session.tree.node(MqttPath.parse("synthetic/small/2"))!!.value!!.payload)
    }

    @Test fun `oversize supplier is never called in either MQTT version`() {
        for (version in listOf(MqttVersion.V5, MqttVersion.V3_1_1)) fixture(version = version) { session, events, scheduler ->
            var copies = 0
            events.message("oversize", mib + 1, { copies++; error("Must not copy oversized data") }, false, 0)
            drain(scheduler)
            assertEquals(0, copies)
            assertEquals(1L, session.dropped.value)
            assertEquals(0, session.tree.topicCount)
            assertEquals(MqttState.Open(version), session.state.value)
        }
    }

    @Test fun `queue rejection precedes copying and capacity returns after processing`() = fixture(
        limits = MqttIngressLimits(payloadBytes = 1024, queueBytes = 2048, queueMessages = 100),
    ) { session, events, scheduler ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var copies = 0
        scheduler.post { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            events.message("first", 1024, { copies++; ByteArray(1024) }, false, 0)
            events.message("rejected", 1024, { copies++; ByteArray(1024) }, false, 0)
            assertEquals(1, copies)
        } finally { release.countDown() }
        drain(scheduler)
        events.message("after-release", 1024, { copies++; ByteArray(1024) }, false, 0)
        drain(scheduler)
        assertEquals(2, copies)
        assertEquals(2, session.tree.topicCount)
        assertEquals(1L, session.dropped.value)
    }

    @Test fun `supplier failure mismatch and fatal error all release their reservations`() = fixture(
        limits = MqttIngressLimits(payloadBytes = 1024, queueBytes = 2048, queueMessages = 1),
    ) { session, events, scheduler ->
        events.message("throwing", 1024, { throw IllegalStateException("Synthetic supplier fault") }, false, 0)
        events.message("wrong-size", 1024, { ByteArray(1023) }, false, 0)
        assertThrows(AssertionError::class.java) {
            events.message("fatal", 1024, { throw AssertionError("Synthetic fatal supplier fault") }, false, 0)
        }
        events.message("accepted", ByteArray(1024), false, 0)
        drain(scheduler)
        assertEquals(2L, session.dropped.value)
        assertEquals(1, session.tree.topicCount)
    }

    @Test fun `closed and replaced attempt callbacks cannot copy or affect current drops`() = fixture { session, old, scheduler ->
        session.connect()
        drain(scheduler)
        var copies = 0
        old.message("old", 1, { copies++; byteArrayOf(1) }, false, 0)
        latest.get().message("current", 1, { copies++; byteArrayOf(2) }, false, 0)
        drain(scheduler)
        assertEquals(1, copies)
        assertEquals(1, session.tree.topicCount)
        assertEquals(0L, session.dropped.value)
        session.close()
        drain(scheduler)
        latest.get().message("closed", 1, { copies++; byteArrayOf(3) }, false, 0)
        assertEquals(1, copies)
        assertEquals(0L, session.dropped.value)
    }

    @Test fun `local stop is explicit and manual retry restores a new accepting lease`() = fixture(
        limits = MqttIngressLimits(overflow = MqttOverflow.STOP_SESSION),
    ) { session, events, scheduler ->
        var copies = 0
        repeat(1000) { events.message("oversize", mib + 1, { copies++; error("Must not copy") }, false, 0) }
        drain(scheduler)
        assertEquals(0, copies)
        assertEquals(1L, session.dropped.value)
        assertEquals(MqttState.Failed(MqttFailure.LOCAL_LIMIT), session.state.value)
        session.retry()
        drain(scheduler)
        assertEquals(MqttState.Open(MqttVersion.V5), session.state.value)
        latest.get().message("after-retry", byteArrayOf(1), false, 0)
        drain(scheduler)
        assertEquals(1, session.tree.topicCount)
    }

    @Test fun `failed scheduler handover returns capacity without another control task`() {
        val base = ExecutorScheduler()
        var reject = false
        val refusing = object : MqttScheduler by base {
            override fun post(action: () -> Unit) {
                if (reject) throw java.util.concurrent.RejectedExecutionException("Synthetic handover rejection")
                base.post(action)
            }
        }
        fixture(limits = MqttIngressLimits(1024, 2048, 1), scheduler = refusing) { session, events, scheduler ->
            try {
                reject = true
                events.message("rejected-post", byteArrayOf(1), false, 0)
            } finally { reject = false }
            events.message("accepted", ByteArray(1024), false, 0)
            drain(scheduler)
            assertEquals(1L, session.dropped.value)
            assertEquals(1, session.tree.topicCount)
        }
    }

    @Test fun `failed stop handover keeps copying disabled and can be retried once scheduler recovers`() {
        val base = ExecutorScheduler()
        var reject = false
        val refusing = object : MqttScheduler by base {
            override fun post(action: () -> Unit) {
                if (reject) throw java.util.concurrent.RejectedExecutionException("Synthetic stop handover rejection")
                base.post(action)
            }
        }
        fixture(limits = MqttIngressLimits(overflow = MqttOverflow.STOP_SESSION), scheduler = refusing) { session, events, scheduler ->
            var copies = 0
            try {
                reject = true
                events.message("first-overflow", mib + 1, { copies++; error("Must not copy") }, false, 0)
            } finally { reject = false }
            events.message("rearm-stop", 1, { copies++; byteArrayOf(1) }, false, 0)
            drain(scheduler)
            assertEquals(0, copies)
            assertEquals(MqttState.Failed(MqttFailure.LOCAL_LIMIT), session.state.value)
            assertEquals(0, session.tree.topicCount)
        }
    }

    @Test fun `handler exception releases capacity in finally`() {
        val base = ExecutorScheduler()
        val failClock = java.util.concurrent.atomic.AtomicBoolean()
        val clock = object : MqttScheduler by base {
            override fun now(): Long {
                if (failClock.getAndSet(false)) throw IllegalStateException("Synthetic message handler clock fault")
                return base.now()
            }
        }
        fixture(limits = MqttIngressLimits(1024, 2048, 1), scheduler = clock) { session, events, scheduler ->
            failClock.set(true)
            events.message("handler-fault", byteArrayOf(1), false, 0)
            drain(scheduler)
            events.message("accepted", ByteArray(1024), false, 0)
            drain(scheduler)
            assertEquals(1, session.tree.topicCount)
            assertNotNull(session.tree.node(MqttPath.parse("accepted")))
        }
    }

    @Test fun `configuration is captured once and reconnect cannot silently change working limits`() {
        val source = AtomicReference(MqttIngressLimits())
        fixture(limitsSource = { source.get() }) { session, events, scheduler ->
            source.set(MqttIngressLimits(1024, 2048, 1))
            session.connect()
            drain(scheduler)
            assertEquals(MqttIngressLimits(), session.ingressLimits)
            latest.get().message("still-original-ceiling", ByteArray(2048), false, 0)
            drain(scheduler)
            assertEquals(1, session.tree.topicCount)
            assertEquals(0L, session.dropped.value)
        }
    }

    private fun drain(scheduler: MqttScheduler) { repeat(4) { scheduler.runAfterPending {} } }

    private fun fixture(limits: MqttIngressLimits = MqttIngressLimits(), version: MqttVersion = MqttVersion.V5,
                        scheduler: MqttScheduler = ExecutorScheduler(), limitsSource: () -> MqttIngressLimits = { limits },
                        test: (MqttSession, LinkEvents, MqttScheduler) -> Unit) {
        val events = latest
        val box = object : SecretBox {
            override fun seal(plain: String): String = error("No secrets in ingress budget test")
            override fun open(sealed: String): String = error("No secrets in ingress budget test")
            override fun destroy() = Unit
        }
        val link = object : MqttLink {
            override fun subscribe(filter: String, done: (Boolean) -> Unit) { done(true) }
            override fun unsubscribe(filter: String) = Unit
            override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) { done(PublishAnswer.Accepted) }
            override fun close() = Unit
        }
        val connector = MqttConnector { request, callbacks, done ->
            events.set(callbacks)
            done(if (request.version == MqttVersion.V5 && version == MqttVersion.V3_1_1)
                ConnectOutcome.Refused(ConnectRefusal.UNSUPPORTED_VERSION) else ConnectOutcome.Opened(link))
        }
        val session = MqttSession(1, "synthetic-p", "synthetic-c", "synthetic", 1883, 9,
            MqttEnvironment(MqttLogins(MemoryStorage(), box), PinStore(MemoryStorage()), connector, scheduler, ingressLimits = limitsSource))
        try {
            session.connect()
            drain(scheduler)
            assertEquals(MqttState.Open(version), session.state.value)
            test(session, requireNotNull(events.get()), scheduler)
        } finally {
            session.close()
            drain(scheduler)
            assertEquals(MqttState.Ended, session.state.value)
        }
    }
}
