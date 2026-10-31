package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.ssh.MemoryStorage

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
}
