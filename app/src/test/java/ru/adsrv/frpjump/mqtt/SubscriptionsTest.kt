package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionsTest {
    private var clock = 1_000L
    private fun p(text: String) = MqttPath.parse(text)

    private fun TopicTree.send(topic: String, payload: String, retain: Boolean = false, onSubscribe: Boolean = false) =
        receive(p(topic), payload.toByteArray(), retain, qos = 0, version = MqttVersion.V5, onSubscribe = onSubscribe)

    private fun tree() = TopicTree { clock }.apply {
        // Delivered as retained on subscribe: from before the session.
        send("/devices/hwmon/controls/Board Temperature", "41.5", retain = true, onSubscribe = true)
        send("/devices/battery/controls/Voltage", "12.1", retain = true, onSubscribe = true)
        clock = 5_000
        send("/devices/battery/controls/Current", "0.3")
        clock = 7_000
        send("/devices/wb-mr6c_1/controls/K1", "1")
        send("\$SYS/broker/uptime", "100")
    }

    private fun row(filter: String, tree: TopicTree) = subscriptionRows(listOf(filter), tree).single()

    @Test
    fun `a single topic shows its value and when it changed`() {
        val tree = tree()
        val temperature = row("/devices/hwmon/controls/Board Temperature", tree)
        assertTrue(temperature.single)
        assertEquals("41.5", String(temperature.node!!.value!!.payload))
        // Delivered as retained on subscribe: "before connecting", as in the tree.
        assertEquals(LastChange.BeforeSession, temperature.change)

        val k1 = row("/devices/wb-mr6c_1/controls/K1", tree)
        assertEquals(LastChange.At(7_000), k1.change)
        assertEquals(1, k1.topics)

        val unseen = row("/devices/none/controls/X", tree)
        assertNull(unseen.node)
        assertNull(unseen.change)
        assertEquals(0, unseen.topics)
    }

    @Test
    fun `a wildcard filter counts the matching topics and the newest change among them`() {
        val tree = tree()
        val battery = row("/devices/battery/#", tree)
        assertFalse(battery.single)
        assertEquals(2, battery.topics)
        assertEquals(LastChange.At(5_000), battery.change)

        // Only values from before the session: "before connecting".
        val hwmon = row("/devices/hwmon/controls/+", tree)
        assertEquals(1, hwmon.topics)
        assertEquals(LastChange.BeforeSession, hwmon.change)

        assertEquals(4, row("/devices/+/controls/+", tree).topics)
        // A first-level wildcard skips `$` topics, as MQTT does; `$SYS/#` names them.
        assertEquals(4, row("#", tree).topics)
        assertEquals(1, row("\$SYS/#", tree).topics)

        val none = row("/nothing/#", tree)
        assertEquals(0, none.topics)
        assertNull(none.change)
    }

    @Test
    fun `wildcard counts follow MQTT matching`() {
        val tree = TopicTree { clock }.apply {
            send("a", "1")
            send("a/b", "2")
            send("a/*", "3")
        }
        // `a/#` matches `a` itself; `*` is a plain character in a filter.
        assertEquals(3, row("a/#", tree).topics)
        assertEquals(2, row("a/+", tree).topics)
        assertEquals(1, row("a/*", tree).topics)
    }

    @Test
    fun `rows keep the order of the pins`() {
        val pins = listOf("/devices/battery/#", "/devices/hwmon/controls/Board Temperature", "+/controls/#")
        assertEquals(pins, subscriptionRows(pins, tree()).map { it.filter })
    }

    @Test
    fun `a tap goes to the topic, the branch, or a search`() {
        assertEquals(SubscriptionTarget.Topic(p("/devices/hwmon/controls/Board Temperature")), subscriptionTarget("/devices/hwmon/controls/Board Temperature"))
        assertEquals(SubscriptionTarget.Branch(p("/devices/battery"), null), subscriptionTarget("/devices/battery/#"))
        assertEquals(SubscriptionTarget.Branch(p("/devices/x/controls"), null), subscriptionTarget("/devices/x/controls/+"))
        // Wildcards go on below the fixed start: the branch opens and the rest is searched there.
        assertEquals(SubscriptionTarget.Branch(p("/devices"), "+/controls/K1"), subscriptionTarget("/devices/+/controls/K1"))
        assertEquals(SubscriptionTarget.Branch(MqttPath(listOf("")), "+/controls/#"), subscriptionTarget("/+/controls/#"))
        assertEquals(SubscriptionTarget.Branch(p("\$SYS"), null), subscriptionTarget("\$SYS/#"))
        // No fixed start: the whole tree is searched with it.
        assertEquals(SubscriptionTarget.Search("+/controls/#"), subscriptionTarget("+/controls/#"))
        assertEquals(SubscriptionTarget.Search("#"), subscriptionTarget("#"))
    }

    @Test
    fun `a pin a card can name has its topic and mask`() {
        assertEquals(RootedPin(p("/devices/x/controls/K1"), PinMask.TOPIC), rootedPin("/devices/x/controls/K1"))
        assertEquals(RootedPin(p("/devices/x"), PinMask.ONE_LEVEL_BELOW), rootedPin("/devices/x/+"))
        assertEquals(RootedPin(p("/devices/battery"), PinMask.WHOLE_BRANCH), rootedPin("/devices/battery/#"))
        // The empty first level: `/#`.
        assertEquals(RootedPin(MqttPath(listOf("")), PinMask.WHOLE_BRANCH), rootedPin("/#"))
        assertNull(rootedPin("#"))
        assertNull(rootedPin("+"))
        assertNull(rootedPin("+/controls/#"))
        assertNull(rootedPin("/devices/+/controls/#"))
        assertNull(rootedPin("/devices/+/controls"))
        // Read back, each makes the same filter.
        listOf("/devices/x/controls/K1", "/devices/x/+", "/devices/battery/#", "/#").forEach { filter ->
            val pin = rootedPin(filter)!!
            assertEquals(filter, pinFilter(pin.topic, pin.mask))
        }
    }

    @Test
    fun `a typed filter is checked by the contract's rules`() {
        assertEquals(FilterProblem.EMPTY, filterProblem(""))
        assertEquals(FilterProblem.HASH_NOT_LAST, filterProblem("a/#/b"))
        assertEquals(FilterProblem.WILDCARD_IN_LEVEL, filterProblem("a/b#"))
        assertEquals(FilterProblem.WILDCARD_IN_LEVEL, filterProblem("a/K+/c"))
        assertEquals(FilterProblem.NUL, filterProblem("a/\u0000"))
        assertEquals(FilterProblem.TOO_LONG, filterProblem("a".repeat(MAX_TOPIC_BYTES + 1)))
        listOf("\$SYS/#", "#", "+", "/devices/+/controls/#", "/", "a/*", "a b/c").forEach { assertNull(it, filterProblem(it)) }
    }

    @Test
    fun `a typed filter that is or is covered by a pin is not pinned again`() {
        val pins = listOf("/devices/#", "x/+")
        assertNull(typedFilterRefusal("y/#", pins))
        assertEquals(FilterRefusal.Pinned, typedFilterRefusal("/devices/#", pins))
        assertEquals(FilterRefusal.Covered("/devices/#"), typedFilterRefusal("/devices/+/controls/K1", pins))
        assertEquals(FilterRefusal.Invalid(FilterProblem.HASH_NOT_LAST), typedFilterRefusal("a/#/b", pins))
        // Broader than a pin: allowed.
        assertNull(typedFilterRefusal("x/#", pins))
    }

    @Test
    fun `the field reminds of dollar topics and plain stars`() {
        assertTrue(filterSkipsDollar("#"))
        assertTrue(filterSkipsDollar("+/controls/#"))
        assertFalse(filterSkipsDollar("/+/controls"))
        assertFalse(filterSkipsDollar("\$SYS/#"))
        assertTrue(filterHasGlobChars("/devices/*/controls"))
        assertFalse(filterHasGlobChars("/devices/+/controls"))
    }
}
