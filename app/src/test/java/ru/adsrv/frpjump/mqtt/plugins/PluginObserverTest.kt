package ru.adsrv.frpjump.mqtt.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.mqtt.ConnectOutcome
import ru.adsrv.frpjump.mqtt.FakeScheduler
import ru.adsrv.frpjump.mqtt.LinkEvents
import ru.adsrv.frpjump.mqtt.MqttConnector
import ru.adsrv.frpjump.mqtt.MqttEnvironment
import ru.adsrv.frpjump.mqtt.MqttLink
import ru.adsrv.frpjump.mqtt.MqttLogins
import ru.adsrv.frpjump.mqtt.MqttSession
import ru.adsrv.frpjump.mqtt.PinStore
import ru.adsrv.frpjump.mqtt.PublishAnswer
import ru.adsrv.frpjump.mqtt.SnapshotEnd
import ru.adsrv.frpjump.mqtt.SnapshotState
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WB_META_FILTERS

class PluginObserverTest {
    @Test
    fun `off drops the filters whatever the session saw`() {
        listOf(SnapshotState.Idle, SnapshotState.Reading, SnapshotState.Done(SnapshotEnd.QUIET), SnapshotState.Refused).forEach {
            assertEquals(emptyList<String>(), wbFilters(on = false, seen = true, snapshot = it))
        }
    }

    @Test
    fun `on, the filters are held once meta is seen or the snapshot may have missed it`() {
        assertEquals(WB_META_FILTERS, wbFilters(on = true, seen = true, snapshot = SnapshotState.Done(SnapshotEnd.QUIET)))
        assertEquals(WB_META_FILTERS, wbFilters(on = true, seen = false, snapshot = SnapshotState.Done(SnapshotEnd.TIMED_OUT)))
        assertEquals(WB_META_FILTERS, wbFilters(on = true, seen = false, snapshot = SnapshotState.Refused))
        // A whole tree without meta drops them.
        assertEquals(emptyList<String>(), wbFilters(on = true, seen = false, snapshot = SnapshotState.Done(SnapshotEnd.QUIET)))
        // Still reading: left as held.
        assertNull(wbFilters(on = true, seen = false, snapshot = SnapshotState.Reading))
        assertNull(wbFilters(on = true, seen = false, snapshot = SnapshotState.Idle))
    }
}

/** The observer over real sessions with a fake broker, without an explorer screen. */
class PluginObserverLifecycleTest {
    private val scheduler = FakeScheduler()
    private val store = PluginStore(MemoryStorage(), MemoryStorage(), MemoryStorage(), MemoryStorage())
    private val sessions = MutableStateFlow<List<MqttSession>>(emptyList())
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val observer = PluginObserver(store, sessions, scope, Dispatchers.Unconfined)

    /** One session's broker: what it was asked to subscribe and unsubscribe. */
    private class Broker {
        val subscribed = mutableListOf<String>()
        val unsubscribed = mutableListOf<String>()
        lateinit var events: LinkEvents
        val connector = MqttConnector { _, e, done ->
            events = e
            done(
                ConnectOutcome.Opened(object : MqttLink {
                    override fun subscribe(filter: String, done: (granted: Boolean) -> Unit) {
                        subscribed += filter
                        done(true)
                    }

                    override fun unsubscribe(filter: String) {
                        unsubscribed += filter
                    }

                    override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) = Unit

                    override fun close() = Unit
                }),
            )
        }

        /** How many times the meta filters were taken (each take subscribes all of them). */
        fun metaSubscribes() = subscribed.count { it in WB_META_FILTERS } / WB_META_FILTERS.size

        /** How many times they were dropped. */
        fun metaUnsubscribes() = unsubscribed.count { it in WB_META_FILTERS } / WB_META_FILTERS.size
    }

    private fun session(id: Int, connection: String, broker: Broker): MqttSession {
        val env = MqttEnvironment(MqttLogins(MemoryStorage(), FakeKeystore().box("k")), PinStore(MemoryStorage()), broker.connector, scheduler)
        return MqttSession(id, "p", connection, "wb", 1883, 20000 + id, env).also { it.connect() }
    }

    /** A session whose whole snapshot held a control's meta. */
    private fun wbSession(id: Int, connection: String, broker: Broker): MqttSession {
        val s = session(id, connection, broker)
        broker.events.message("/devices/d/controls/K1/meta", "{\"type\":\"switch\"}".toByteArray(), retain = true, qos = 0)
        return s
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a store change reaches a session with no screen open`() {
        val broker = Broker()
        val s = wbSession(1, "c", broker)
        sessions.value = listOf(s)
        scheduler.advanceBy(20_000)
        assertEquals(1, broker.metaSubscribes())
        assertTrue(observer.wbSeen(1).value)
        store.switch("p", "c", PluginIds.WIREN_BOARD, on = false)
        assertEquals(1, broker.metaUnsubscribes())
        // At app level too: off and on again, the connection follows it.
        store.switch("p", "c", PluginIds.WIREN_BOARD, on = true)
        assertEquals(2, broker.metaSubscribes())
        store.update { f -> f.withApp { it.withOn(PluginIds.WIREN_BOARD, false) } }
        assertEquals(2, broker.metaUnsubscribes())
    }

    @Test
    fun `the sessions list emitted again does not subscribe a session twice`() {
        val one = Broker()
        val two = Broker()
        val s1 = wbSession(1, "c1", one)
        sessions.value = listOf(s1)
        scheduler.advanceBy(20_000)
        val watch = observer.watchOf(1)
        sessions.value = listOf(s1, wbSession(2, "c2", two))
        scheduler.advanceBy(20_000)
        sessions.value = listOf(s1)
        // One watch per session, the same one throughout.
        assertSame(watch, observer.watchOf(1))
        assertTrue(watch!!.isActive)
        assertNull(observer.watchOf(2))
        assertEquals(1, one.metaSubscribes())
        assertEquals(0, one.metaUnsubscribes())
    }

    @Test
    fun `a session that leaves is no longer watched and its mark goes`() {
        val one = Broker()
        val two = Broker()
        val s1 = wbSession(1, "c1", one)
        val s2 = wbSession(2, "c2", two)
        sessions.value = listOf(s1, s2)
        scheduler.advanceBy(20_000)
        val mark = observer.wbSeen(1)
        assertTrue(mark.value)
        val watch = observer.watchOf(1)!!
        sessions.value = listOf(s2)
        assertTrue(watch.isCancelled)
        assertNull(observer.watchOf(1))
        store.switch("p", "c1", PluginIds.WIREN_BOARD, on = false)
        assertEquals(0, one.metaUnsubscribes())
        // Asked again, a gone session gets a mark that is not kept.
        assertNotSame(mark, observer.wbSeen(1))
        assertFalse(observer.wbSeen(1).value)
        assertNotSame(observer.wbSeen(1), observer.wbSeen(1))
        // The session still there keeps its own.
        assertSame(observer.wbSeen(2), observer.wbSeen(2))
    }

    @Test
    fun `a session opened without meta drops the filters once its whole tree is read`() {
        val broker = Broker()
        val s = session(1, "c", broker)
        sessions.value = listOf(s)
        scheduler.advanceBy(20_000)
        assertEquals(0, broker.metaSubscribes())
        assertFalse(observer.wbSeen(1).value)
    }
}
