package ru.adsrv.frpjump.mqtt

import org.junit.Assert.*
import org.junit.Test

/** Real tree, legal names and bounded synthetic allocations; no broker or deliberate OOM. */
class MqttCacheBudgetTest {
    // S8b finite default target: 32MiB accounted names/nodes/value-history storage, configurable later.
    @Test fun `stored payloads are bounded below the existing topic count ceiling`() {
        val tree = TopicTree()
        val body = ByteArray(1024 * 1024)
        var accepted = 0
        repeat(33) { i ->
            val result = tree.receive(MqttPath.parse("synthetic/$i"), body, false, 0, MqttVersion.V5)
            if (result == Received.NEW_TOPIC) accepted++
        }
        assertTrue("33MiB of stored payload must not all fit the default32MiB cache", accepted < 33)
        assertEquals(accepted, tree.topicCount)
    }

    @Test fun `history bytes are bounded even with only three known topics`() {
        val tree = TopicTree()
        val body = ByteArray(600 * 1024)
        var rejected = 0
        repeat(3) { topic ->
            repeat(20) {
                val result = tree.receive(MqttPath.parse("history/$topic"), body, false, 0, MqttVersion.V5)
                if (result != Received.NEW_TOPIC && result != Received.CHANGED) rejected++
            }
        }
        // 60 owned history copies, ~35MiB, not a large/unbounded allocation proof.
        assertTrue("A small topic count does not bound stored history bytes", rejected > 0)
    }

    @Test fun `empty values cannot bypass accounting for deep structural nodes`() {
        val tree = TopicTree()
        var accepted = 0
        repeat(20) { branch ->
            val path = MqttPath(listOf("branch$branch") + List(10_000) { "n" })
            assertTrue(path.format().toByteArray(Charsets.UTF_8).size < 65_535)
            if (tree.receive(path, byteArrayOf(), false, 0, MqttVersion.V5) == Received.NEW_TOPIC) accepted++
        }
        assertTrue("Structural nodes consume budget even when payloads are empty", accepted < 20)
        assertEquals(accepted, tree.topicCount)
    }

    @Test fun `byte denial creates no branch and does not alter old value history counts or times`() {
        val path = MqttPath.parse("x")
        val exact = TopicTree.NODE_CHARGE * 2 + 2 + TopicTree.VALUE_CHARGE + 1024
        var clock = 1L
        val tree = TopicTree(storageLimitBytes = exact, historyLimit = 1, now = { clock })
        tree.receive(path, ByteArray(1024), false, 0, MqttVersion.V5)
        val old = tree.node(path)!!.value!!
        clock = 2L
        assertEquals(Received.STORAGE_LIMIT, tree.receive(MqttPath.parse("new/deep"), byteArrayOf(), false, 0, MqttVersion.V5))
        assertNull(tree.node(MqttPath.parse("new")))
        assertEquals(Received.STORAGE_LIMIT, tree.receive(path, ByteArray(2048), false, 0, MqttVersion.V5))
        assertSame(old, tree.node(path)!!.value)
        assertEquals(listOf(old), tree.history(path))
        assertEquals(1, tree.topicCount)
        assertEquals(1L, tree.node(MqttPath.ROOT)!!.newestChangeAt)
        assertEquals(exact, tree.storedBytes)
        assertTrue(tree.storageLimitReached)
        assertFalse(tree.limitReached)
    }

    @Test fun `history rotation and shrinking payload receive credit before the capacity check`() {
        val path = MqttPath.parse("x")
        val exact = TopicTree.NODE_CHARGE * 2 + 2 + 2 * (TopicTree.VALUE_CHARGE + 100)
        val tree = TopicTree(storageLimitBytes = exact, historyLimit = 2)
        tree.receive(path, ByteArray(100) { 1 }, false, 0, MqttVersion.V5)
        tree.receive(path, ByteArray(100) { 2 }, false, 0, MqttVersion.V5)
        assertEquals(exact, tree.storedBytes)
        assertEquals(Received.CHANGED, tree.receive(path, ByteArray(100) { 3 }, false, 0, MqttVersion.V5))
        assertEquals(exact, tree.storedBytes)
        assertEquals(listOf(3.toByte(), 2.toByte()), tree.history(path).map { it.payload[0] })
        assertEquals(Received.CHANGED, tree.receive(path, byteArrayOf(4), false, 0, MqttVersion.V5))
        assertEquals(exact - 99, tree.storedBytes)
        assertFalse(tree.storageLimitReached)
    }

    @Test fun `retained refresh is neutral at a full budget and clear returns all owned charges`() {
        for (version in MqttVersion.entries) {
            val exact = TopicTree.NODE_CHARGE * 2 + 2 + TopicTree.VALUE_CHARGE + 1024
            var clock = 1L
            val tree = TopicTree(storageLimitBytes = exact, now = { clock })
            val path = MqttPath.parse("x")
            tree.receive(path, ByteArray(1024), true, 0, version, onSubscribe = true)
            clock = 20L
            assertEquals(Received.UNCHANGED, tree.receive(path, ByteArray(1024), true, 1, version))
            assertEquals(exact, tree.storedBytes)
            assertEquals(1, tree.history(path).size)
            assertEquals(1L, tree.node(path)!!.value!!.receivedAt)
            assertTrue(tree.node(path)!!.value!!.beforeSession)
            assertFalse(tree.storageLimitReached)
            tree.clear()
            assertEquals(TopicTree.NODE_CHARGE, tree.storedBytes)
            assertFalse(tree.storageLimitReached)
            assertEquals(Received.NEW_TOPIC, tree.receive(path, ByteArray(1024), false, 0, version))
        }
    }

    @Test fun `structural empty levels are charged and topic count failure stays distinct`() {
        val tree = TopicTree(limit = 1, storageLimitBytes = 4096)
        val path = MqttPath.parse("long/")
        tree.receive(path, byteArrayOf(), false, 0, MqttVersion.V5)
        assertEquals(3 * TopicTree.NODE_CHARGE + 8 + TopicTree.VALUE_CHARGE, tree.storedBytes)
        val before = tree.storedBytes
        assertEquals(Received.OVER_LIMIT, tree.receive(MqttPath.parse("other"), byteArrayOf(), false, 0, MqttVersion.V5))
        assertEquals(before, tree.storedBytes)
        assertTrue(tree.limitReached)
        assertFalse(tree.storageLimitReached)
        val deep = TopicTree(storageLimitBytes = 4096)
        assertEquals(Received.STORAGE_LIMIT, deep.receive(MqttPath(List(10_000) { "n" }), byteArrayOf(), false, 0, MqttVersion.V5))
        assertTrue(deep.children(MqttPath.ROOT).isEmpty())
        assertEquals(TopicTree.NODE_CHARGE, deep.storedBytes)
    }

    @Test fun `ordinary values retained refresh marks and clear remain usable`() {
        val tree = TopicTree(now = { 123L })
        val path = MqttPath.parse("ordinary/value")
        assertEquals(Received.NEW_TOPIC, tree.receive(path, byteArrayOf(1), false, 0, MqttVersion.V5))
        assertEquals(Received.UNCHANGED, tree.receive(path, byteArrayOf(1), true, 0, MqttVersion.V5))
        assertEquals(1, tree.history(path).size)
        assertTrue(tree.node(path)!!.value!!.retained)
        assertEquals(123L, tree.node(path)!!.value!!.receivedAt)
        tree.clear()
        assertEquals(0, tree.topicCount)
        assertNull(tree.node(path))
    }
}
