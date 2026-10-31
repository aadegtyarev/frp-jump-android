package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadViewTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun `invalid UTF-8 opens as hex`() {
        assertEquals(PayloadView.HEX, detectView(byteArrayOf(0x01, 0xff.toByte(), 0xfe.toByte())))
        // A JSON-looking start does not save a payload that is not UTF-8.
        assertEquals(PayloadView.HEX, detectView(byteArrayOf('{'.code.toByte(), 0xc3.toByte())))
    }

    @Test
    fun `a JSON object or array opens as JSON`() {
        assertEquals(PayloadView.JSON, detectView(bytes("""{"type":"switch","readonly":false}""")))
        assertEquals(PayloadView.JSON, detectView(bytes("  [1, 2, {\"a\": null}]\n")))
        assertEquals(PayloadView.JSON, detectView(bytes("{}")))
    }

    @Test
    fun `a scalar, broken JSON and plain text open as text`() {
        listOf("21.5", "true", "\"on\"", "null", "{\"a\":", "[1,]", "{a:1}", "hello", "").forEach {
            assertEquals(it, PayloadView.TEXT, detectView(bytes(it)))
        }
    }

    @Test
    fun `JSON over the parse limit opens as text`() {
        val big = "[" + "1,".repeat(MAX_JSON_BYTES / 2) + "1]"
        assertEquals(PayloadView.TEXT, detectView(bytes(big)))
        assertNull(jsonTree(bytes(big)))
    }

    @Test
    fun `JSON is pretty-printed into foldable lines`() {
        val lines = jsonLines(jsonTree(bytes("""{"a":1,"b":{"c":"x\"y","d":[]},"e":[true]}"""))!!)
        assertEquals(
            listOf("{", "\"a\": 1,", "\"b\": {", "\"c\": \"x\\\"y\",", "\"d\": []", "},", "\"e\": [", "true", "]", "}"),
            lines.map { it.text },
        )
        assertEquals(listOf(0, 1, 1, 2, 2, 1, 1, 2, 1, 0), lines.map { it.depth })
        assertEquals(9, lines[0].end)
        assertEquals(3, lines[0].children)
        assertEquals(5, lines[2].end)
        assertFalse(lines[4].foldable) // an empty array has nothing to fold
    }

    @Test
    fun `a folded line hides what it holds up to its closing line`() {
        val lines = jsonLines(jsonTree(bytes("""{"a":1,"b":{"c":2},"e":[3]}"""))!!)
        // { / "a": 1, / "b": { / "c": 2 / }, / "e": [ / 3 / ] / }
        assertEquals((0..8).map { it to false }, visibleJsonLines(lines, emptySet()))
        assertEquals(listOf(0 to false, 1 to false, 2 to true, 5 to false, 6 to false, 7 to false, 8 to false), visibleJsonLines(lines, setOf(2)))
        assertEquals(listOf(0 to true), visibleJsonLines(lines, setOf(0, 2)))
        // An index that is not an opening line is ignored.
        assertEquals((0..8).map { it to false }, visibleJsonLines(lines, setOf(1)))
    }

    @Test
    fun `the hex dump shows offset, bytes and printable ASCII`() {
        val dump = hexDump(bytes("Hello, world") + byteArrayOf(0, 0x7f), perLine = 8)
        assertEquals(listOf("0000  48 65 6c 6c 6f 2c 20 77  Hello, w", "0008  6f 72 6c 64 00 7f        orld.."), dump)
        assertEquals(1, hexDump(ByteArray(100), maxBytes = 8).size)
    }

    @Test
    fun `copy value takes the text as is up to 64 KB`() {
        val small = copiedValue(bytes("{\"a\": 1}"), PayloadView.JSON)
        assertEquals(CopiedValue("{\"a\": 1}", cut = false), small)

        val exact = copiedValue(bytes("x".repeat(MAX_COPY_CHARS)), PayloadView.TEXT)
        assertEquals(MAX_COPY_CHARS, exact.text.length)
        assertFalse(exact.cut)

        val over = copiedValue(bytes("x".repeat(MAX_COPY_CHARS + 10)), PayloadView.TEXT)
        assertEquals(MAX_COPY_CHARS, over.text.length)
        assertTrue(over.cut)
    }

    @Test
    fun `copy value never cuts a surrogate pair`() {
        val text = "x".repeat(MAX_COPY_CHARS - 1) + "😀" + "tail"
        val copied = copiedValue(bytes(text), PayloadView.TEXT)
        assertEquals(MAX_COPY_CHARS - 1, copied.text.length)
        assertTrue(copied.cut)
    }

    @Test
    fun `copy value in hex takes whole pairs up to 64 KB`() {
        assertEquals(CopiedValue("00 ff", cut = false), copiedValue(byteArrayOf(0, -1), PayloadView.HEX))
        val big = copiedValue(ByteArray(MAX_COPY_CHARS), PayloadView.HEX)
        assertTrue(big.cut)
        assertTrue(big.text.length <= MAX_COPY_CHARS)
        assertTrue(big.text.endsWith("00"))
    }

    @Test
    fun `deeply nested JSON opens as text without parsing it`() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertEquals(PayloadReading(PayloadView.TEXT, null), readPayload(bytes(deep)))
        assertNull(jsonTree(bytes(deep)))
        val limit = "[".repeat(MAX_JSON_DEPTH) + "]".repeat(MAX_JSON_DEPTH)
        assertEquals(PayloadView.JSON, detectView(bytes(limit)))
        assertEquals(PayloadView.TEXT, detectView(bytes("[$limit]")))
        // Brackets inside strings do not count.
        assertEquals(1, nestingDepth("""["[[[\\\"[[", "]"]"""))
    }

    @Test
    fun `equal values read to equal lines, another shape to other lines`() {
        // The card keeps its folds while the lines stay equal and starts unfolded when they change.
        val a = readPayload(bytes("""{"a":{"b":1}}"""))
        assertEquals(a, readPayload(bytes("""{"a":{"b":1}}""")))
        assertTrue(a != readPayload(bytes("""{"a":[1,2]}""")))
        assertEquals(PayloadReading(PayloadView.HEX, null), readPayload(byteArrayOf(0xff.toByte())))
    }
}
