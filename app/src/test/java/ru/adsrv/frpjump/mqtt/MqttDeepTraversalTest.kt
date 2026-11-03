package ru.adsrv.frpjump.mqtt

import org.junit.Assert.*
import org.junit.Test

/** Valid MQTT names (<65535 UTF-8 bytes), real tree, no smaller artificial JVM stack or broker. */
class MqttDeepTraversalTest {
    private val deep = MqttPath(List(20_000) { "a" } + "Leaf")
    private val all = listOf(LevelPattern.Rest)
    private fun populated(): TopicTree {
        assertTrue(deep.format().toByteArray(Charsets.UTF_8).size < 65_535)
        return TopicTree(now = { 123L }).also {
            assertEquals(Received.NEW_TOPIC, it.receive(deep, byteArrayOf(7), false, 0, MqttVersion.V5))
        }
    }

    @Test fun `deep search keeps relative paths and case insensitive substring matching`() {
        val tree = populated()
        val at = MqttPath(listOf("a"))
        assertEquals(listOf(MqttPath(deep.levels.drop(1))), tree.search(at, "a/LEAF"))
    }

    @Test fun `hash match walks a deep legal topic`() {
        assertEquals(listOf(deep), populated().match(MqttPath.ROOT, all))
    }

    @Test fun `summary counts a deep topic without materializing every prefix`() {
        assertEquals(TopicTree.Summary(1, 123L), populated().summary(all))
    }

    @Test fun `values returns a deep topic and its actual payload`() {
        val values = populated().values(all)
        assertEquals(listOf(deep), values.map { it.first })
        assertArrayEquals(byteArrayOf(7), values.single().second.payload)
    }

    @Test fun `long exact pattern uses the same stack safe walker`() {
        val pattern = deep.levels.map { LevelPattern.Exact(it) }
        assertEquals(listOf(deep), populated().match(MqttPath.ROOT, pattern))
    }

    @Test fun `deep rest includes valued prefix but excludes the starting node in relative match`() {
        val prefix = MqttPath(deep.levels.take(10_000))
        var clock = 1L
        val tree = TopicTree(now = { clock })
        tree.receive(prefix, byteArrayOf(1), true, 0, MqttVersion.V5, onSubscribe = true)
        clock = 123L
        tree.receive(deep, byteArrayOf(7), false, 0, MqttVersion.V5)
        val pattern = prefix.levels.map { LevelPattern.Exact(it) } + LevelPattern.Rest
        assertEquals(setOf(prefix, deep), tree.match(MqttPath.ROOT, pattern).toSet())
        assertEquals(TopicTree.Summary(2, 123L), tree.summary(pattern))
        assertEquals(setOf(prefix, deep), tree.values(pattern).map { it.first }.toSet())
        assertEquals(listOf(MqttPath(deep.levels.drop(prefix.levels.size))), tree.match(prefix, all))
        assertTrue(tree.node(prefix)!!.value!!.beforeSession)
    }

    @Test fun `deep wildcard and case matching backtrack without confusing dollar or shorter siblings`() {
        val tree = populated()
        val system = MqttPath(listOf("\$SYS") + deep.levels.drop(1))
        val nested = MqttPath(listOf("a", "\$nested", "leaf"))
        val shorter = MqttPath(listOf("a", "Other"))
        for (path in listOf(system, nested, shorter)) tree.receive(path, byteArrayOf(8), false, 0, MqttVersion.V5)
        assertEquals(setOf(deep, nested, shorter), tree.match(MqttPath.ROOT, all).toSet())
        assertEquals(setOf(deep, system, nested), tree.search(MqttPath.ROOT, "LEAF").toSet())
        val wildcard = List(deep.levels.size - 1) { LevelPattern.OneLevel } + LevelPattern.Glob("le?f")
        assertEquals(listOf(deep), tree.match(MqttPath.ROOT, wildcard, ignoreCase = true))
        assertTrue(tree.match(MqttPath.ROOT, listOf(LevelPattern.Exact("A"), LevelPattern.Rest)).isEmpty())
    }

    @Test fun `deep receive lookup history and clear already work without recursive traversal`() {
        val tree = populated()
        assertEquals(1, tree.topicCount)
        assertArrayEquals(byteArrayOf(7), tree.node(deep)!!.value!!.payload)
        assertEquals(1, tree.node(MqttPath.ROOT)!!.topicsBelow)
        assertEquals(1, tree.history(deep).size)
        tree.clear()
        assertEquals(0, tree.topicCount)
        assertNull(tree.node(deep))
    }
}
