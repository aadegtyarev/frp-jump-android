package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPatternTest {
    private fun p(text: String) = MqttPath.parse(text)
    private fun glob(pattern: String, level: String) = globMatches(pattern.codePoints().toArray(), level)
    private fun pattern(query: String) = parseSearch(query) as SearchQuery.Pattern

    private fun tree(vararg topics: String) = TopicTree().apply {
        topics.forEach { receive(p(it), "1".toByteArray(), retain = false, qos = 0, version = MqttVersion.V5) }
    }

    private fun TopicTree.find(query: String, at: String = ""): List<String> {
        val q = pattern(query)
        return match(q.base(p(at)), q.levels).map { it.format() }
    }

    @Test
    fun `star and question mark match within one level`() {
        assertTrue(glob("K*", "K"))
        assertTrue(glob("K*", "K12"))
        assertTrue(glob("*1", "K1"))
        assertTrue(glob("*", ""))
        assertTrue(glob("K?", "K1"))
        assertFalse(glob("K?", "K"))
        assertFalse(glob("K?", "K12"))
        assertTrue(glob("a*b*c", "a-b-b-c"))
        assertFalse(glob("a*b*c", "a-b-b-d"))
        // Exact case; other characters are literal, including regex ones.
        assertFalse(glob("k*", "K1"))
        assertTrue(glob("a.b*", "a.b(1)"))
        assertFalse(glob("a.b*", "axb"))
        // `?` is one character, also outside the BMP.
        assertTrue(glob("t?", "t😀"))
    }

    @Test
    fun `stars never cross a level`() {
        val tree = tree("/devices/a/controls/K1", "/devices/b/controls/K2/meta", "/devices/b/x")
        assertEquals(listOf("/devices/a/controls/K1"), tree.find("/devices/*/controls/K*"))
        assertEquals(emptyList<String>(), tree.find("/devices/*/K1"))
        assertEquals(listOf("/devices/b/x"), tree.find("/devices/?/?"))
    }

    @Test
    fun `plus is one level, hash this level and all below`() {
        val tree = tree("/devices/a/controls/K1", "/devices/a/controls/K1/meta", "/devices/a", "/devices/b/controls/K2", "other/x")
        assertEquals(listOf("/devices/a/controls/K1", "/devices/b/controls/K2"), tree.find("/devices/+/controls/K*"))
        // `#` takes the level above it too, as in MQTT.
        assertEquals(listOf("/devices/a", "/devices/a/controls/K1", "/devices/a/controls/K1/meta"), tree.find("/devices/a/#"))
        assertEquals(listOf("/devices/a/controls/K1/meta"), tree.find("/devices/+/controls/+/meta"))
        assertEquals(listOf("other/x"), tree.find("+/x"))
    }

    @Test
    fun `wildcards at the first level skip dollar topics`() {
        val tree = tree("\$SYS/broker/uptime", "a/b")
        assertEquals(listOf("a/b"), tree.find("#"))
        assertEquals(listOf("a/b"), tree.find("+/+"))
        assertEquals(listOf("a/b"), tree.find("*/*"))
        assertEquals(listOf("\$SYS/broker/uptime"), tree.find("\$SYS/#"))
        assertEquals(listOf("\$SYS/broker/uptime"), tree.find("\$*/+/+"))
    }

    @Test
    fun `relative to the open branch, absolute with a leading slash`() {
        val tree = tree("/devices/a/controls/K1", "devices/a/controls/K1")
        // In /devices: relative, hits relative to it.
        assertEquals(listOf("a/controls/K1"), tree.find("+/controls/K*", at = "/devices"))
        // A leading slash starts at the root, its empty first level matching Wiren Board topics only.
        assertEquals(listOf("/devices/a/controls/K1"), tree.find("/devices/+/controls/K*", at = "devices"))
        // Relative at the root: topics without the leading slash.
        assertEquals(listOf("devices/a/controls/K1"), tree.find("devices/+/controls/K*"))
        // The branch itself is not a hit of its own `#`.
        assertEquals(listOf("a/controls/K1"), tree.find("#", at = "/devices"))
    }

    @Test
    fun `a query without wildcards stays a substring search`() {
        assertEquals(SearchQuery.Substring("/devices/K1"), parseSearch("/devices/K1"))
    }

    @Test
    fun `plus and hash must take a whole level, hash only the last`() {
        assertEquals(SearchQuery.Invalid(PatternProblem.WILDCARD_IN_LEVEL), parseSearch("/devices/K+"))
        assertEquals(SearchQuery.Invalid(PatternProblem.WILDCARD_IN_LEVEL), parseSearch("a#"))
        assertEquals(SearchQuery.Invalid(PatternProblem.WILDCARD_IN_LEVEL), parseSearch("K*+"))
        assertEquals(SearchQuery.Invalid(PatternProblem.HASH_NOT_LAST), parseSearch("#/a"))
        assertEquals(SearchQuery.Invalid(PatternProblem.HASH_NOT_LAST), parseSearch("/devices/#/x"))
        val ok = pattern("/devices/+/controls/K*")
        assertTrue(ok.absolute)
        assertEquals(LevelPattern.Exact(""), ok.levels[0])
        assertEquals(LevelPattern.OneLevel, ok.levels[2])
    }

    @Test
    fun `pure MQTT filter gives the absolute filter, globs give none`() {
        assertTrue(pattern("+/controls/#").isMqttFilter)
        assertEquals("/devices/+/controls/#", pattern("+/controls/#").filter(p("/devices")))
        assertEquals("/devices/+", pattern("devices/+").filter(MqttPath(listOf(""))))
        assertEquals("devices/+", pattern("devices/+").filter(MqttPath.ROOT))
        assertEquals("/devices/+/x", pattern("/devices/+/x").filter(p("other/branch")))
        assertFalse(pattern("+/K*").isMqttFilter)
        assertNull(pattern("+/K*").filter(MqttPath.ROOT))
    }

    @Test
    fun `the offer under the hits`() {
        val pins = listOf("/devices/+/controls/#", "/devices/#")
        fun offer(query: String, at: String = "", open: Boolean = true) = filterOffer(parseSearch(query), p(at), pins, open)
        assertNull(offer("K1"))
        assertNull(offer("K+"))
        // The filter is a pin itself, even when a broader pin covers it too: it can be unpinned here.
        assertEquals(FilterOffer.Unsubscribe("/devices/+/controls/#"), offer("+/controls/#", at = "/devices"))
        assertEquals(FilterOffer.Unsubscribe("/devices/#"), offer("/devices/#"))
        assertEquals(FilterOffer.Subscribe("devices/+"), offer("devices/+"))
        assertEquals(FilterOffer.Subscribe("x/+"), offer("+", at = "x"))
        // Only covered by a broader pin: the first one that covers it is named.
        assertEquals(FilterOffer.Covered("/devices/+/controls/K1", by = "/devices/+/controls/#"), offer("/devices/+/controls/K1"))
        assertEquals(FilterOffer.Covered("/devices/+", by = "/devices/#"), offer("/devices/+", at = "x"))
        assertEquals(FilterOffer.SessionClosed, offer("/devices/+", open = false))
        assertEquals(FilterOffer.SessionClosed, offer("/devices/#", open = false))
        assertEquals(FilterOffer.NotForBroker, offer("/devices/+/controls/K?"))
        assertEquals(FilterOffer.NotForBroker, offer("/devices/+/controls/K?", open = false))
        assertEquals(FilterOffer.TooLong, offer("a".repeat(MAX_TOPIC_BYTES) + "/#"))
    }

    @Test
    fun `a pin covers a filter when it delivers all the filter does`() {
        assertTrue(filterCovers("a/b", "a/b"))
        assertTrue(filterCovers("#", "a/+/#"))
        assertTrue(filterCovers("a/#", "a"))
        assertTrue(filterCovers("a/+", "a/+"))
        assertTrue(filterCovers("+/+", "a/b"))
        assertFalse(filterCovers("a/+", "a/#"))
        assertFalse(filterCovers("a/b", "a/+"))
        assertFalse(filterCovers("a/+", "a/b/c"))
        assertFalse(filterCovers("a/b/#", "a/#"))
        // Wildcards at the first level never bring `$` topics.
        assertFalse(filterCovers("#", "\$SYS/#"))
        assertFalse(filterCovers("+/x", "\$SYS/x"))
        assertTrue(filterCovers("#", "/devices/#"))
    }

    @Test
    fun `dollar levels below the root and empty levels`() {
        val tree = tree("a/\$x/y", "/\$SYS/x", "a//b")
        // The `$` rule is for the tree's first level only.
        assertEquals(listOf("a/\$x/y"), tree.find("a/+/y"))
        assertEquals(listOf("\$x/y"), tree.find("+/y", at = "a"))
        assertEquals(listOf("\$x/y"), tree.find("*/y", at = "a"))
        // The empty first level is literal: `/#` brings `$` topics below it.
        assertEquals(listOf("/\$SYS/x"), tree.find("/#"))
        // `+` takes an empty level.
        assertEquals(listOf("a//b"), tree.find("a/+/b"))
    }

    @Test
    fun `pure filters find what the broker would deliver`() {
        val topics = listOf(
            "/devices/a/controls/K1", "/devices/a/controls/K1/meta", "/devices/a", "/devices/b/meta/name",
            "a", "a/b", "a/b/c", "a//b", "a/", "\$SYS/broker/uptime", "/\$SYS/x", "x/\$y/z",
        )
        val tree = tree(*topics.toTypedArray())
        val filters = listOf(
            "#", "+", "+/#", "+/+", "/#", "/+", "/+/+", "/devices/+", "/devices/#", "/devices/+/controls/+",
            "/devices/+/controls/#", "/devices/+/meta/+", "a/#", "a/+", "a/+/b", "a/+/+", "+/+/c", "x/+/z", "\$SYS/#", "+/\$y/#",
        )
        filters.forEach { filter ->
            val expected = topics.filter { topicMatches(filter, p(it)) }.sortedWith(compareBy(NaturalOrder.paths) { p(it) })
            assertEquals(filter, expected, tree.find(filter))
        }
    }

    @Test
    fun `a case-insensitive run is only on request`() {
        val tree = tree("/devices/Relay/controls/K1")
        assertEquals(emptyList<String>(), tree.find("/devices/relay/+/k*"))
        val q = pattern("/devices/relay/+/k*")
        assertEquals(listOf(p("/devices/Relay/controls/K1")), tree.match(q.base(MqttPath.ROOT), q.levels, ignoreCase = true))
    }

    @Test
    fun `fast on a large tree and on a pathological glob`() {
        val tree = TopicTree()
        for (d in 0 until 500) for (c in 0 until 100) {
            tree.receive(p("/devices/dev_$d/controls/K$c"), "1".toByteArray(), retain = false, qos = 0, version = MqttVersion.V5)
        }
        assertEquals(50_000, tree.topicCount)
        val started = System.nanoTime()
        assertEquals(500, tree.find("/devices/*/controls/K4?").size / 10)
        assertEquals(50_000, tree.find("/#").size)
        assertEquals(500, tree.find("/devices/+/controls/K1").size)
        assertEquals(0, tree.find("/devices/*a*a*a*a*a*b/+/+").size)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 3_000)
        assertFalse(glob("*a*a*a*a*a*a*a*a*b", "a".repeat(10_000)))
    }
}
