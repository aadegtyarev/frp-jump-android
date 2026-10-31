package ru.adsrv.frpjump.mqtt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.ports.profilePortKey
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.undo.PendingUndo

class PinsTest {
    @Test
    fun `each mask gives its topic filter`() {
        val k1 = MqttPath.parse("/devices/x/controls/K1")
        assertEquals("/devices/x/controls/K1", pinFilter(k1, PinMask.TOPIC))
        assertEquals("/devices/x/controls/K1/+", pinFilter(k1, PinMask.ONE_LEVEL_BELOW))
        assertEquals("/devices/x/controls/K1/#", pinFilter(k1, PinMask.WHOLE_BRANCH))
        assertEquals("\$SYS/#", pinFilter(MqttPath.parse("\$SYS"), PinMask.WHOLE_BRANCH))
        // The empty first level: the branch `/#`.
        assertEquals("/#", pinFilter(MqttPath(listOf("")), PinMask.WHOLE_BRANCH))
    }

    @Test
    fun `the masks a path is pinned with are read back from the filters`() {
        val controls = MqttPath.parse("/devices/x/controls")
        val pins = listOf("/devices/x/controls/#", "/devices/x/controls/K1", "/devices/x/controls/+")
        assertEquals(listOf(PinMask.ONE_LEVEL_BELOW, PinMask.WHOLE_BRANCH), pinnedMasks(controls, pins))
        assertEquals(listOf(PinMask.TOPIC), pinnedMasks(controls.child("K1"), pins))
        assertEquals(emptyList<PinMask>(), pinnedMasks(MqttPath.ROOT, listOf("#")))
    }

    @Test
    fun `pins made in a session are subscribed again by the next session of the connection`() {
        val scheduler = FakeScheduler()
        val storage = MemoryStorage()
        val subscribed = mutableListOf<String>()
        val connector = MqttConnector { _, _, done ->
            done(
                ConnectOutcome.Opened(object : MqttLink {
                    override fun subscribe(filter: String, done: (granted: Boolean) -> Unit) {
                        subscribed += filter
                        done(true)
                    }

                    override fun unsubscribe(filter: String) = Unit

                    override fun close() = Unit
                }),
            )
        }
        fun env() = MqttEnvironment(MqttLogins(MemoryStorage(), FakeKeystore().box("k")), PinStore(storage), connector, scheduler)
        val k1 = pinFilter(MqttPath.parse("/devices/x/controls/K1"), PinMask.TOPIC)
        val controls = pinFilter(MqttPath.parse("/devices/x/controls"), PinMask.ONE_LEVEL_BELOW)

        val first = MqttSession(1, "p", "c", "wb", 1883, 20000, env())
        first.connect()
        first.pin(k1)
        first.pin(controls)
        first.pin("gone/#")
        first.unpin("gone/#")
        first.close()

        subscribed.clear()
        // A new store over the same file, as after the app restarts.
        val second = MqttSession(2, "p", "c", "wb", 1883, 20001, env())
        second.connect()
        assertEquals(listOf(k1, controls), second.pins.value)
        assertEquals(listOf(k1, controls, "#"), subscribed)

        // Another connection of the profile has its own pins.
        assertEquals(emptyList<String>(), MqttSession(3, "p", "other", "wb", 1883, 20002, env()).also { it.connect() }.pins.value)
    }

    @Test
    fun `an unpin that comes after the session closed still leaves the store`() {
        // Undo ran out after the session went: the pin must not come back with the next session.
        val scheduler = FakeScheduler()
        val storage = MemoryStorage()
        val env = MqttEnvironment(MqttLogins(MemoryStorage(), FakeKeystore().box("k")), PinStore(storage).apply { set("p", "c", listOf("a", "b/#", "c/+")) }, MqttConnector { _, _, _ -> }, scheduler)
        val up = MutableStateFlow(setOf(profilePortKey("p", "wb", 1883)))
        val sessions = MqttSessions(env, up, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        val session = sessions.open("p", "c", "wb", 1883, 20000)
        session.close()
        session.unpin("b/#")
        assertEquals(listOf("a", "c/+"), PinStore(storage).get("p", "c"))
        // No session at all: the store only, the others kept in their order.
        sessions.close(session.id)
        sessions.unpin("p", "c", "a")
        assertEquals(listOf("c/+"), PinStore(storage).get("p", "c"))
    }

    @Test
    fun `hidden pins are read back per connection from the undo keys`() {
        val hidden = setOf(
            PendingUndo.mqttPinKey("p", "c", "/devices/x/#"),
            PendingUndo.mqttPinKey("p", "c2", "a"),
            PendingUndo.connectionKey("p", "c"),
        )
        assertEquals(setOf("/devices/x/#"), PendingUndo.mqttPins(hidden, "p", "c"))
        assertEquals(emptySet<String>(), PendingUndo.mqttPins(hidden, "q", "c"))
    }

    @Test
    fun `a pin made again while its unpin waits for Undo is kept`() {
        val scheduler = FakeScheduler()
        val storage = MemoryStorage()
        val env = MqttEnvironment(MqttLogins(MemoryStorage(), FakeKeystore().box("k")), PinStore(storage).apply { set("p", "c", listOf("a/#")) }, MqttConnector { _, _, _ -> }, scheduler)
        val sessions = MqttSessions(env, MutableStateFlow(setOf(profilePortKey("p", "wb", 1883))), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        sessions.open("p", "c", "wb", 1883, 20000)
        val mark = sessions.pinMark("p", "c", "a/#")
        sessions.pin("p", "c", "a/#")
        sessions.unpin("p", "c", "a/#", mark)
        assertEquals(listOf("a/#"), PinStore(storage).get("p", "c"))
        sessions.unpin("p", "c", "a/#", sessions.pinMark("p", "c", "a/#"))
        assertEquals(emptyList<String>(), PinStore(storage).get("p", "c"))
    }
}
