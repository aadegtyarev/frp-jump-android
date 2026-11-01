package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun `empty level first, SYS last, digits by number, case ignored, ties by exact text`() {
        val names = listOf("\$SYS", "K10", "b", "k1", "", "K2", "a", "A", "K02", "\$other")
        assertEquals(listOf("", "\$other", "A", "a", "b", "k1", "K02", "K2", "K10", "\$SYS"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun `digit runs longer than a Long compare by number`() {
        val big = "x" + "9".repeat(30)
        val bigger = "x1" + "0".repeat(30)
        assertTrue(NaturalOrder.compare(big, bigger) < 0)
        assertTrue(NaturalOrder.compare("x" + "0".repeat(40) + "5", "x6") < 0)
    }

    @Test
    fun `paths compare level by level, a parent before its children`() {
        val paths = listOf("a/b10", "a", "/x", "a/b2").map(MqttPath::parse)
        assertEquals(listOf("/x", "a", "a/b2", "a/b10"), paths.sortedWith(NaturalOrder.paths).map { it.format() })
    }
}
