package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicTreeTest {
    private var clock = 1_000L
    private fun tree(limit: Int = TopicTree.DEFAULT_LIMIT) = TopicTree(limit) { clock }
    private fun p(text: String) = MqttPath.parse(text)

    private fun TopicTree.send(
        topic: String,
        payload: String,
        retain: Boolean = false,
        version: MqttVersion = MqttVersion.V5,
    ) = receive(p(topic), payload.toByteArray(), retain, qos = 0, version = version)

    private fun TopicTree.value(topic: String) = node(p(topic))!!.value!!

    @Test
    fun `counts seen topics below each branch and the newest change there`() {
        val tree = tree()
        tree.send("/devices/a/controls/K1", "0")
        clock = 2_000
        tree.send("/devices/a/controls/K2", "1")
        tree.send("/devices/b", "x")
        clock = 3_000
        tree.send("/devices/b/meta", "{}")

        assertEquals(4, tree.topicCount)
        assertEquals(4, tree.node(MqttPath.ROOT)!!.topicsBelow)
        val devices = tree.children(p("/devices"))
        assertEquals(listOf("a", "b"), devices.map { it.name })
        assertEquals(2, devices[0].topicsBelow)
        assertEquals(2_000L, devices[0].newestChangeAt)
        // A node with a value and children.
        assertEquals("x", String(devices[1].value!!.payload))
        assertTrue(devices[1].hasChildren)
        assertEquals(1, devices[1].topicsBelow)
        assertEquals(3_000L, tree.node(p("/devices"))!!.newestChangeAt)
        assertEquals(listOf(""), tree.children(MqttPath.ROOT).map { it.name })
        assertTrue(tree.children(p("devices")).isEmpty())
    }

    @Test
    fun `history keeps the last twenty values, newest first`() {
        val tree = tree()
        repeat(25) { tree.send("t", "$it") }
        val history = tree.history(p("t"))
        assertEquals(TopicTree.HISTORY, history.size)
        assertEquals("24", String(history.first().payload))
        assertEquals("5", String(history.last().payload))
        assertEquals(1, tree.topicCount)
    }

    @Test
    fun `at the limit known topics update and new ones are dropped`() {
        val tree = tree(limit = 2)
        assertEquals(Received.NEW_TOPIC, tree.send("a", "1"))
        tree.send("b/c", "1")
        // Exactly at the limit, nothing dropped yet.
        assertFalse(tree.limitReached)
        assertEquals(Received.OVER_LIMIT, tree.send("b/d", "1"))
        assertTrue(tree.limitReached)
        assertNull(tree.node(p("b/d")))
        // A branch-only level is not a topic yet.
        assertEquals(Received.OVER_LIMIT, tree.send("b", "1"))
        assertNull(tree.node(p("b"))!!.value)
        assertEquals(Received.CHANGED, tree.send("a", "2"))
        assertEquals("2", String(tree.value("a").payload))
        assertEquals(2, tree.topicCount)
        tree.clear()
        assertEquals(0, tree.topicCount)
        assertFalse(tree.limitReached)
        assertNull(tree.node(p("a")))
    }

    @Test
    fun `MQTT 5 marks retained as published, empty retained is a clear`() {
        val tree = tree()
        tree.send("t", "x", retain = true)
        assertTrue(tree.value("t").retained)
        tree.send("t", "y")
        assertFalse(tree.value("t").retained)
        tree.send("t", "", retain = true)
        assertTrue(tree.value("t").cleared)
        assertFalse(tree.value("t").retained)
        tree.send("t", "")
        assertFalse(tree.value("t").cleared)
        assertTrue(tree.value("t").isEmptyValue)
        tree.send("u", "", retain = true)
        assertTrue(tree.value("u").cleared)
    }

    @Test
    fun `3_1_1 keeps the mark until an empty message clears it`() {
        val tree = tree()
        val v3 = MqttVersion.V3_1_1
        tree.send("t", "x", retain = true, version = v3)
        tree.send("t", "y", version = v3)
        assertTrue(tree.value("t").retained)
        tree.send("t", "", version = v3)
        assertTrue(tree.value("t").cleared)
        assertFalse(tree.value("t").retained)
        tree.send("t", "", version = v3)
        assertTrue(tree.value("t").isEmptyValue)
        tree.send("u", "z", version = v3)
        assertFalse(tree.value("u").retained)
        tree.send("w", "", retain = true, version = v3)
        assertTrue(tree.value("w").cleared)
    }

    @Test
    fun `a retained value re-sent equal to the known one is not a change`() {
        val tree = tree()
        tree.send("t", "x", version = MqttVersion.V3_1_1)
        clock = 5_000
        assertEquals(Received.UNCHANGED, tree.send("t", "x", retain = true, version = MqttVersion.V3_1_1))
        assertEquals(1, tree.history(p("t")).size)
        assertTrue(tree.value("t").retained)
        assertEquals(1_000L, tree.value("t").receivedAt)
        assertEquals(1_000L, tree.node(MqttPath.ROOT)!!.newestChangeAt)
        // The same value published live again is a change.
        assertEquals(Received.CHANGED, tree.send("t", "x", version = MqttVersion.V3_1_1))
        assertEquals(2, tree.history(p("t")).size)
    }

    @Test
    fun `search matches paths below the level ignoring case, relative to it`() {
        val tree = tree()
        listOf("/devices/a/controls/K1", "/devices/a/controls/K10", "/devices/a/meta/error", "/devices/b/controls/k1", "/other/K1")
            .forEach { tree.send(it, "v") }
        val at = p("/devices")
        assertEquals(
            listOf("a/controls/K1", "a/controls/K10", "b/controls/k1"),
            tree.search(at, "k1").map { it.format() },
        )
        assertEquals(listOf("a/meta/error"), tree.search(at, "META/err").map { it.format() })
        // The level's own name is not part of the relative path.
        assertTrue(tree.search(at, "devices").isEmpty())
        assertTrue(tree.search(at, "").isEmpty())
        assertEquals(5, tree.search(MqttPath.ROOT, "/").size)
    }
}
