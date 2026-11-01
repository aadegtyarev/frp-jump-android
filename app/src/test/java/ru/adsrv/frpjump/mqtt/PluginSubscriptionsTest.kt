package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.ssh.MemoryStorage

class PluginSubscriptionsTest {
    private val scheduler = FakeScheduler()
    private val subscribed = mutableListOf<String>()
    private val unsubscribed = mutableListOf<String>()
    private lateinit var events: LinkEvents

    private val connector = MqttConnector { _, e, done ->
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

    private fun session(pins: List<String> = emptyList()): MqttSession {
        val env = MqttEnvironment(MqttLogins(MemoryStorage(), FakeKeystore().box("k")), PinStore(MemoryStorage()).apply { set("p", "c", pins) }, connector, scheduler)
        return MqttSession(1, "p", "c", "wb", 1883, 20000, env).also { it.connect() }
    }

    @Test
    fun `plugin filters are subscribed and dropped as set`() {
        val s = session()
        subscribed.clear()
        s.setPluginFilters(listOf("/devices/+/meta/#", "/devices/+/controls/+/meta/#"))
        assertEquals(listOf("/devices/+/meta/#", "/devices/+/controls/+/meta/#"), subscribed)
        s.setPluginFilters(emptyList())
        assertEquals(listOf("/devices/+/meta/#", "/devices/+/controls/+/meta/#"), unsubscribed)
    }

    @Test
    fun `a copy through a plugin filter is dropped after the pins took the message`() {
        // MQTT 5: the first matching filter in the order branch, pins, plugins takes it; the copy is not a second value.
        val s = session(pins = listOf("/devices/d/controls/K1/meta"))
        s.setPluginFilters(listOf("/devices/+/controls/+/meta/#"))
        scheduler.advanceBy(20_000)
        val topic = "/devices/d/controls/K1/meta"
        events.message(topic, "{\"type\":\"switch\"}".toByteArray(), retain = false, qos = 0)
        events.message(topic, "{\"type\":\"switch\"}".toByteArray(), retain = false, qos = 0)
        assertEquals(1, s.tree.history(MqttPath.parse(topic)).size)
    }

    @Test
    fun `an open view replaces the tree's live branch without moving the tree`() {
        val s = session()
        s.open(MqttPath.parse("/devices/x"))
        subscribed.clear()
        s.openView(MqttPath.parse("/devices/d"))
        assertEquals(listOf("/devices/d/#"), subscribed)
        assertEquals(listOf("/devices/x/#"), unsubscribed)
        assertEquals(MqttPath.parse("/devices/x"), s.branch.value)
        // The tree moving meanwhile keeps the view live; closing the view brings the tree's level back.
        s.open(MqttPath.parse("/devices/y"))
        assertEquals(listOf("/devices/d/#"), subscribed)
        s.openView(null)
        assertEquals(listOf("/devices/d/#", "/devices/y/#"), subscribed)
        assertEquals(listOf("/devices/x/#", "/devices/d/#"), unsubscribed)
    }
}
