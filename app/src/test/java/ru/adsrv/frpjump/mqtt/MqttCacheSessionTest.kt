package ru.adsrv.frpjump.mqtt

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.settings.MqttCacheLimits
import ru.adsrv.frpjump.settings.MqttOverflow
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.SecretBox

/** Real session/scheduler, fake accepted broker; no sockets or credentials. */
class MqttCacheSessionTest {
    @Test fun `storage drop stops snapshot with distinct reason not quiet or topic count`() = fixture(
        MqttCacheLimits(storageBytes = 4096),
    ) { session, events, scheduler ->
        repeat(4) { events.get().message("x", ByteArray(1024), false, 0) }
        drain(scheduler)
        assertEquals(MqttState.Open(MqttVersion.V5), session.state.value)
        assertEquals(SnapshotState.Done(SnapshotEnd.STORAGE_LIMIT), session.snapshot.value)
        assertEquals(1L, session.cacheDropped.value)
        assertEquals(0L, session.dropped.value)
        assertEquals(3, session.tree.history(MqttPath.parse("x")).size)
        assertTrue(session.tree.storageLimitReached)
        assertFalse(session.tree.limitReached)
    }

    @Test fun `cache stop is manual and refreshing retained data permits retry without hiding old rejections`() = fixture(
        MqttCacheLimits(storageBytes = 4096, overflow = MqttOverflow.STOP_SESSION),
    ) { session, events, scheduler ->
        repeat(4) { events.get().message("x", ByteArray(1024), false, 0) }
        drain(scheduler)
        assertEquals(MqttState.Failed(MqttFailure.CACHE_LIMIT), session.state.value)
        val old = events.get()
        var copies = 0
        old.message("late", 1, { copies++; byteArrayOf(1) }, false, 0)
        assertEquals(0, copies)
        session.refreshTree()
        session.retry()
        drain(scheduler)
        events.get().message("after-refresh", byteArrayOf(1), false, 0)
        drain(scheduler)
        assertEquals(MqttState.Open(MqttVersion.V5), session.state.value)
        assertEquals(1, session.tree.topicCount)
        assertEquals(1L, session.cacheDropped.value)
    }

    @Test fun `topic count rejection remains distinct and uses selected cache policy`() = fixture(
        MqttCacheLimits(topics = 1),
    ) { session, events, scheduler ->
        events.get().message("x", byteArrayOf(1), false, 0)
        events.get().message("y", byteArrayOf(2), false, 0)
        drain(scheduler)
        assertEquals(SnapshotState.Done(SnapshotEnd.LIMIT), session.snapshot.value)
        assertTrue(session.tree.limitReached)
        assertFalse(session.tree.storageLimitReached)
        assertEquals(1L, session.cacheDropped.value)
        assertEquals(MqttState.Open(MqttVersion.V5), session.state.value)
    }

    @Test fun `working explorer keeps its captured cache preferences across reconnect`() {
        val selected = AtomicReference(MqttCacheLimits())
        fixture(MqttCacheLimits(), limitsSource = { selected.get() }) { session, events, scheduler ->
            selected.set(MqttCacheLimits(4096, 1, 1, MqttOverflow.STOP_SESSION))
            session.connect(); drain(scheduler)
            events.get().message("one", byteArrayOf(1), false, 0)
            events.get().message("two", byteArrayOf(2), false, 0)
            drain(scheduler)
            assertEquals(MqttCacheLimits(), session.cacheLimits)
            assertEquals(2, session.tree.topicCount)
            assertEquals(0L, session.cacheDropped.value)
        }
    }

    private fun drain(scheduler: MqttScheduler) { repeat(4) { scheduler.runAfterPending {} } }
    private fun fixture(limits: MqttCacheLimits, limitsSource: () -> MqttCacheLimits = { limits },
                        test: (MqttSession, AtomicReference<LinkEvents>, MqttScheduler) -> Unit) {
        val scheduler = ExecutorScheduler()
        val events = AtomicReference<LinkEvents>()
        val box = object : SecretBox {
            override fun seal(plain: String): String = error("No credentials in cache test")
            override fun open(sealed: String): String = error("No credentials in cache test")
            override fun destroy() = Unit
        }
        val link = object : MqttLink {
            override fun subscribe(filter: String, done: (Boolean) -> Unit) { done(true) }
            override fun unsubscribe(filter: String) = Unit
            override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) { done(PublishAnswer.Accepted) }
            override fun close() = Unit
        }
        val connector = MqttConnector { _, callbacks, done -> events.set(callbacks); done(ConnectOutcome.Opened(link)) }
        val session = MqttSession(1, "synthetic", "cache", "synthetic", 1883, 9,
            MqttEnvironment(MqttLogins(MemoryStorage(), box), PinStore(MemoryStorage()), connector, scheduler, cacheLimits = limitsSource))
        try {
            session.connect(); drain(scheduler)
            assertEquals(MqttState.Open(MqttVersion.V5), session.state.value)
            test(session, events, scheduler)
        } finally { session.close(); drain(scheduler); assertEquals(MqttState.Ended, session.state.value) }
    }
}
