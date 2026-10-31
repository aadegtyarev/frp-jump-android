package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttPathTest {
    @Test
    fun `levels are kept as is, empty ones too, and format back`() {
        val cases = mapOf(
            "/" to listOf("", ""),
            "//a" to listOf("", "", "a"),
            "a/" to listOf("a", ""),
            "\$SYS/x" to listOf("\$SYS", "x"),
            "/devices/x" to listOf("", "devices", "x"),
        )
        cases.forEach { (text, levels) ->
            assertEquals(text, levels, MqttPath.parse(text).levels)
            assertEquals(text, MqttPath.parse(text).format())
        }
        assertNotEquals(MqttPath.parse("/devices/x"), MqttPath.parse("devices/x"))
    }

    @Test
    fun `the root has no levels and is its own parent`() {
        assertTrue(MqttPath.parse("").isRoot)
        assertEquals("", MqttPath.ROOT.format())
        assertEquals(MqttPath.ROOT, MqttPath.ROOT.parent)
        assertEquals(MqttPath.ROOT, MqttPath.parse("a").parent)
        assertEquals(MqttPath.parse("/"), MqttPath.parse("/").parent.child(""))
    }

    @Test
    fun `a path relative to a level below it`() {
        val base = MqttPath.parse("/devices")
        assertEquals("x/controls/K1", MqttPath.parse("/devices/x/controls/K1").relativeTo(base).format())
        assertTrue(MqttPath.ROOT.contains(base))
        assertTrue(!base.contains(MqttPath.parse("devices/x")))
    }
}
