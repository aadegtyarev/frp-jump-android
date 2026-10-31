package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class PublishMemoryTest {
    private fun SentTopic?.payloadsOrNone() = this?.payloads.orEmpty()

    private fun List<SentTopic>.of(topic: String) = firstOrNull { it.topic == topic }

    @Test
    fun `retain, QoS and format come back per topic and per connection, after a restart`() {
        val storage = MemoryStorage()
        val memory = PublishMemory(storage)
        memory.sent("p", "c1", "a/on", "1", retain = true, qos = 2, json = false)
        memory.sent("p", "c1", "a/cfg", "{}", retain = false, qos = 0, json = true)
        memory.sent("p", "c2", "a/on", "0", retain = false, qos = 1, json = false)

        val again = PublishMemory(storage)
        assertEquals(SentTopic("a/on", retain = true, qos = 2, json = false, payloads = listOf("1")), again.get("p", "c1").of("a/on"))
        assertEquals(SentTopic("a/cfg", retain = false, qos = 0, json = true, payloads = listOf("{}")), again.get("p", "c1").of("a/cfg"))
        assertEquals(listOf("0"), again.get("p", "c2").of("a/on").payloadsOrNone())
        assertNull(again.get("q", "c1").of("a/on"))
        // The most recently sent topic first.
        assertEquals(listOf("a/cfg", "a/on"), again.get("p", "c1").map { it.topic })
    }

    @Test
    fun `recent payloads are newest first, without repeats, at most ten a topic`() {
        val memory = PublishMemory(MemoryStorage())
        (1..12).forEach { memory.sent("p", "c", "t", "$it", false, 1, false) }
        memory.sent("p", "c", "t", "5", true, 0, false)
        val t = memory.get("p", "c").of("t")!!
        assertEquals(listOf("5", "12", "11", "10", "9", "8", "7", "6", "4", "3"), t.payloads)
        assertEquals(PublishMemory.MAX_PAYLOADS, t.payloads.size)
        // The last send's settings.
        assertTrue(t.retain)
        assertEquals(0, t.qos)
    }

    @Test
    fun `a payload too long to keep is sent without being remembered, the settings still are`() {
        val memory = PublishMemory(MemoryStorage())
        memory.sent("p", "c", "t", "small", false, 1, false)
        memory.sent("p", "c", "t", "x".repeat(PublishMemory.MAX_PAYLOAD_CHARS + 1), true, 2, false)
        val t = memory.get("p", "c").of("t")!!
        assertEquals(listOf("small"), t.payloads)
        assertEquals(2, t.qos)
    }

    @Test
    fun `topics and characters a connection are capped, the least recent dropped first`() {
        val memory = PublishMemory(MemoryStorage())
        (1..PublishMemory.MAX_TOPICS + 5).forEach { memory.sent("p", "c", "t$it", "v", false, 1, false) }
        val topics = memory.get("p", "c")
        assertEquals(PublishMemory.MAX_TOPICS, topics.size)
        assertNull(topics.of("t1"))
        assertEquals("t${PublishMemory.MAX_TOPICS + 5}", topics.first().topic)

        val big = PublishMemory(MemoryStorage())
        val chunk = PublishMemory.MAX_PAYLOAD_CHARS
        val count = PublishMemory.MAX_CHARS / chunk + 3
        (1..count).forEach { big.sent("p", "c", "t$it", "$it".padEnd(chunk, '.'), false, 1, false) }
        val kept = big.get("p", "c")
        assertTrue(kept.sumOf { t -> t.payloads.sumOf { it.length } } <= PublishMemory.MAX_CHARS)
        // The newest topic keeps its payload; the oldest lose theirs but keep their settings.
        assertEquals(1, kept.of("t$count").payloadsOrNone().size)
        assertEquals(emptyList<String>(), kept.of("t1").payloadsOrNone())
        assertEquals(count, kept.size)
    }

    @Test
    fun `forgetting a connection or a profile leaves nothing of it behind`() {
        val storage = MemoryStorage()
        val memory = PublishMemory(storage)
        memory.sent("p", "c1", "t", "1", false, 1, false)
        memory.sent("p", "c2", "t", "2", false, 1, false)
        memory.sent("q", "c1", "t", "3", false, 1, false)
        memory.forget("p", "c1")
        assertEquals(emptyList<SentTopic>(), PublishMemory(storage).get("p", "c1"))
        assertEquals(listOf("2"), PublishMemory(storage).get("p", "c2").of("t").payloadsOrNone())
        memory.forgetProfile("p")
        assertEquals(emptyList<SentTopic>(), PublishMemory(storage).get("p", "c2"))
        assertEquals(listOf("3"), PublishMemory(storage).get("q", "c1").of("t").payloadsOrNone())
        memory.wipe()
        assertNull(storage.read())
    }

    @Test
    fun `an unreadable file is an empty memory`() {
        val storage = MemoryStorage()
        storage.write("not json")
        assertEquals(emptyList<SentTopic>(), PublishMemory(storage).get("p", "c"))
    }
}
