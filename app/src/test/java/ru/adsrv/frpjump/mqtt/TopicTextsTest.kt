package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicTextsTest {
    private val empty = "(empty)"

    @Test
    fun `the path bar shows the empty first level as a slash and each part opens its own place`() {
        val path = MqttPath.parse("/devices/wb-mr6c_1")
        assertEquals(listOf("#", "/", "devices", "wb-mr6c_1"), pathBarLabels(path, "#", empty))
        assertEquals(MqttPath.ROOT, pathAtPart(path, 0))
        // The empty first level is a place of its own, not the root.
        assertEquals(MqttPath(listOf("")), pathAtPart(path, 1))
        assertEquals(MqttPath.parse("/devices"), pathAtPart(path, 2))
        assertEquals(path, pathAtPart(path, 3))
        // No `/` after the root nor after the leading `/`: it reads `# /devices / wb-mr6c_1`.
        assertEquals(listOf(false, false, false, true), (0..3).map { pathBarSeparator(path, it) })
        // Empty levels in the middle and at the end keep their name; `devices/x` has none.
        assertEquals(listOf("#", "a", empty, "b", empty), pathBarLabels(MqttPath.parse("a//b/"), "#", empty))
        assertEquals(listOf(false, false, true, true, true), (0..4).map { pathBarSeparator(MqttPath.parse("a//b/"), it) })
        assertEquals(listOf("#", "devices", "x"), pathBarLabels(MqttPath.parse("devices/x"), "#", empty))
        assertEquals(listOf("#"), pathBarLabels(MqttPath.ROOT, "#", empty))
    }

    @Test
    fun `the empty first level reads as a slash in rows, paths and search hits`() {
        val leading = "/ (leading slash)"
        assertEquals(leading, nodeLabel(MqttPath(listOf("")), empty, leading))
        assertEquals(empty, nodeLabel(MqttPath.parse("a/"), empty, leading))
        assertEquals("K1", nodeLabel(MqttPath.parse("/devices/x/K1"), empty, leading))
        assertEquals("/", shownPath(MqttPath(listOf(""))))
        assertEquals("/devices/x", shownPath(MqttPath.parse("/devices/x")))
        assertNull(shownPath(MqttPath.ROOT))
        // Hits at the root show the real topic, not `(empty)/devices/…`; the topic `<level>/` alone reads as empty.
        assertEquals("/devices/x/controls/K1", hitLabel(MqttPath.parse("/devices/x/controls/K1"), empty))
        assertEquals("a//b", hitLabel(MqttPath.parse("a//b"), empty))
        assertEquals(empty, hitLabel(MqttPath(listOf("")), empty))
    }

    @Test
    fun `a long path in the search hint is cut in the middle`() {
        assertEquals("/devices/x", middleEllipsis("/devices/x", 12))
        assertEquals("/devic…ols/K1", middleEllipsis("/devices/wb-mr6c_1/controls/K1", 13))
        assertEquals(13, middleEllipsis("/devices/wb-mr6c_1/controls/K1", 13).length)
        // A surrogate pair is never split: the cut moves off it.
        val cut = middleEllipsis("ab😀cdefgh😀ij", 7)
        assertTrue(cut.codePoints().noneMatch { it in 0xD800..0xDFFF })
    }

    @Test
    fun `search looks in the open level, or the whole tree once widened`() {
        val level = MqttPath.parse("/devices/x")
        assertEquals(level, searchScope(level, whole = false))
        assertEquals(MqttPath.ROOT, searchScope(level, whole = true))
        assertEquals(MqttPath.ROOT, searchScope(MqttPath.ROOT, whole = false))
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

    @Test
    fun `the joiners ZWNJ and ZWJ stay, other format characters go`() {
        assertEquals("a\u200Cb\u200Dc", oneLineText("a\u200Cb\u200Dc\u200B"))
        assertEquals("\uD83D\uDC68\u200D\uD83D\uDCBB", multiLineText("\uD83D\uDC68\u200D\uD83D\uDCBB\u2066", 100))
    }

    @Test
    fun `a typed topic is checked as is before it goes out`() {
        assertNull(topicProblem("/devices/x"))
        // Not trimmed: spaces are part of a topic.
        assertNull(topicProblem(" devices "))
        assertEquals(TopicProblem.EMPTY, topicProblem(""))
        assertEquals(TopicProblem.WILDCARD, topicProblem("devices/+/controls"))
        assertEquals(TopicProblem.WILDCARD, topicProblem("devices/#"))
        assertEquals(TopicProblem.NUL, topicProblem("a\u0000b"))
        assertNull(topicProblem("a".repeat(MAX_TOPIC_BYTES)))
        assertEquals(TopicProblem.TOO_LONG, topicProblem("a".repeat(MAX_TOPIC_BYTES + 1)))
        // Counted in UTF-8 bytes, with what is added for a pin.
        assertEquals(TopicProblem.TOO_LONG, topicProblem("ж".repeat(MAX_TOPIC_BYTES / 2 + 1)))
        assertNull(topicProblem("a".repeat(MAX_TOPIC_BYTES - 2), suffix = "/#"))
        assertEquals(TopicProblem.TOO_LONG, topicProblem("a".repeat(MAX_TOPIC_BYTES - 1), suffix = "/#"))
    }

    @Test
    fun `the root SYS row says why nothing of it is known`() {
        // `#` and first-level wildcards never bring `$` topics.
        assertEquals(SysRowState.NOT_SUBSCRIBED, sysRowState(listOf("#", "+/x", "/devices/#"), emptySet()))
        assertEquals(SysRowState.NO_MESSAGES, sysRowState(listOf("\$SYS/#"), emptySet()))
        assertEquals(SysRowState.NO_MESSAGES, sysRowState(listOf("\$SYS/broker/uptime"), emptySet()))
        // A similar name is another branch.
        assertEquals(SysRowState.NOT_SUBSCRIBED, sysRowState(listOf("\$SYSTEM/#", "x/\$SYS/#"), emptySet()))
        // Refused wins over pinned: the pin cannot bring anything.
        assertEquals(SysRowState.REFUSED, sysRowState(listOf("\$SYS/#"), setOf("\$SYS/#")))
        assertEquals(SysRowState.REFUSED, sysRowState(emptyList(), setOf("\$SYS/#")))
    }
}
