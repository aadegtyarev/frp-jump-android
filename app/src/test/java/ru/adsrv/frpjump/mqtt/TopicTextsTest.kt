package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicTextsTest {
    private val empty = "(empty)"

    @Test
    fun `the path bar names empty levels and each part opens its own place`() {
        val path = MqttPath.parse("/devices/wb-mr6c_1")
        assertEquals(listOf("/", empty, "devices", "wb-mr6c_1"), pathBarLabels(path, "/", empty))
        assertEquals(MqttPath.ROOT, pathAtPart(path, 0))
        // The empty first level is a place of its own, not the root.
        assertEquals(MqttPath(listOf("")), pathAtPart(path, 1))
        assertEquals(MqttPath.parse("/devices"), pathAtPart(path, 2))
        assertEquals(path, pathAtPart(path, 3))
        // Empty levels in the middle and at the end; `devices/x` has none.
        assertEquals(listOf("/", "a", empty, "b", empty), pathBarLabels(MqttPath.parse("a//b/"), "/", empty))
        assertEquals(listOf("/", "devices", "x"), pathBarLabels(MqttPath.parse("devices/x"), "/", empty))
        assertEquals(listOf("/"), pathBarLabels(MqttPath.ROOT, "/", empty))
    }

    @Test
    fun `one line drops bidi and format characters and turns controls into single spaces`() {
        assertEquals("a b c", oneLineText("a\nb\r\n\tc"))
        assertEquals("on", oneLineText("‮o⁦n⁩​﻿"))
        assertEquals("x y", oneLineText("x \u0000\u001B y"))
        assertEquals("21.5", oneLineText("  21.5  \n"))
        assertEquals("", oneLineText("\n\t "))
    }

    @Test
    fun `one line is cut with an ellipsis and never inside a surrogate pair`() {
        assertEquals("abc", oneLineText("abc", maxChars = 3))
        assertEquals("abc…", oneLineText("abcd", maxChars = 3))
        // The emoji is two chars: cut before it rather than keep half.
        assertEquals("ab…", oneLineText("ab😀z", maxChars = 3))
        // Trailing spaces left by the cut go.
        assertEquals("ab…", oneLineText("ab   cd", maxChars = 3))
    }

    @Test
    fun `a payload that is not UTF-8 shows as hex`() {
        assertEquals(ValueLine.Text("{\"v\": 1}"), valueLine("{\"v\":\n 1}".toByteArray()))
        val binary = byteArrayOf(0xff.toByte(), 0x00, 0x10)
        assertNull(decodeUtf8(binary))
        assertEquals(ValueLine.Hex("ff 00 10"), valueLine(binary))
        assertEquals("ff 00 …", hexPreview(binary, 2))
    }

    @Test
    fun `several lines keep breaks and tabs but drop bidi characters`() {
        assertEquals("a\n\tb\nc", multiLineText("a\r\n\tb\rc‮", 100))
        assertEquals("ab…", multiLineText("abcdef", 2))
    }

    @Test
    fun `a pin of any mask marks its own topic only`() {
        val k1 = MqttPath.parse("/devices/x/controls/K1")
        assertTrue(pinnedAt(k1, listOf("/devices/x/controls/K1")))
        assertTrue(pinnedAt(k1, listOf("/devices/x/controls/K1/+")))
        assertTrue(pinnedAt(k1, listOf("/devices/x/controls/K1/#")))
        assertFalse(pinnedAt(k1, listOf("/devices/x/controls/#")))
        assertFalse(pinnedAt(MqttPath.ROOT, listOf("#")))
    }
}
