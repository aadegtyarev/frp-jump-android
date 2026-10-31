package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicFiltersTest {
    private fun matches(filter: String, topic: String) = topicMatches(filter, MqttPath.parse(topic))

    @Test
    fun `plus is one level, hash this level and below`() {
        assertTrue(matches("/devices/#", "/devices"))
        assertTrue(matches("/devices/#", "/devices/x/controls/K1"))
        assertFalse(matches("/devices/#", "devices/x"))
        assertTrue(matches("/devices/+/controls/+", "/devices/x/controls/K1"))
        assertFalse(matches("/devices/+/controls/+", "/devices/x/controls/K1/on"))
        assertFalse(matches("a/+", "a"))
        assertTrue(matches("a/+", "a/"))
        assertTrue(matches("+/a", "/a"))
        assertTrue(matches("#", "/"))
        assertTrue(matches("K1", "K1"))
        assertFalse(matches("K1", "K1/on"))
    }

    @Test
    fun `a leading wildcard never matches a dollar topic`() {
        assertFalse(matches("#", "\$SYS/broker/uptime"))
        assertFalse(matches("+/broker/uptime", "\$SYS/broker/uptime"))
        assertTrue(matches("\$SYS/#", "\$SYS/broker/uptime"))
        assertTrue(matches("\$SYS/+/uptime", "\$SYS/broker/uptime"))
        assertTrue(matches("a/#", "a/\$x"))
    }

    @Test
    fun `filters are tried in order, open branch then pins`() {
        val filters = listOf("/devices/x/#", "/devices/x/controls/K1", "/devices/+/controls/+")
        val k1 = MqttPath.parse("/devices/x/controls/K1")
        assertEquals(0, firstMatchingFilter(filters, k1))
        assertEquals(3, matchingFilters(filters, k1))
        assertEquals(2, firstMatchingFilter(filters, MqttPath.parse("/devices/y/controls/K1")))
        assertEquals(-1, firstMatchingFilter(filters, MqttPath.parse("other")))
    }

    @Test
    fun `copies of one publish are dropped, at most one fewer than the matching subscriptions`() {
        val filter = CopyFilter()
        val k1 = MqttPath.parse("K1")
        val one = "1".toByteArray()
        assertFalse(filter.isCopy(k1, one, retain = false, qos = 0, matching = 3))
        assertTrue(filter.isCopy(k1, one, retain = false, qos = 0, matching = 3))
        assertTrue(filter.isCopy(k1, one, retain = false, qos = 0, matching = 3))
        // A fourth identical message is a new publish.
        assertFalse(filter.isCopy(k1, one, retain = false, qos = 0, matching = 3))
    }

    @Test
    fun `a different message ends the expected copies, one subscription expects none`() {
        val filter = CopyFilter()
        val k1 = MqttPath.parse("K1")
        assertFalse(filter.isCopy(k1, "1".toByteArray(), retain = false, qos = 0, matching = 2))
        assertFalse(filter.isCopy(k1, "1".toByteArray(), retain = true, qos = 0, matching = 2))
        assertFalse(filter.isCopy(k1, "0".toByteArray(), retain = false, qos = 0, matching = 2))
        assertFalse(filter.isCopy(k1, "1".toByteArray(), retain = false, qos = 0, matching = 2))
        assertFalse(filter.isCopy(MqttPath.parse("K2"), "1".toByteArray(), retain = false, qos = 1, matching = 1))
        assertFalse(filter.isCopy(MqttPath.parse("K2"), "1".toByteArray(), retain = false, qos = 1, matching = 1))
    }
}
