package ru.adsrv.frpjump.assistant

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Finds [needle], a plain substring, in a terminal's new output, fed chunk by chunk: UTF-8 split between chunks is put together, terminal escape
 * sequences (colours, cursor moves, titles) are dropped so that they cannot split the text, and the end of the text so far is kept so that a
 * match across two chunks is found. Output from before the matcher was made is never seen. Not thread-safe: one feeding thread.
 */
class OutputMatcher(private val needle: String) {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var leftover = ByteArray(0)
    private var tail = ""

    /** True when the output so far, this chunk included, has [needle] in what was fed since the matcher was made. */
    fun feed(bytes: ByteArray, length: Int): Boolean {
        if (needle.isEmpty() || length <= 0) return false
        val all = leftover + bytes.copyOf(length)
        val input = ByteBuffer.wrap(all)
        val out = CharBuffer.allocate(all.size + 1)
        decoder.decode(input, out, false)
        leftover = all.copyOfRange(input.position(), all.size)
        out.flip()
        val text = ESCAPES.replace(tail + out.toString(), "")
        tail = text.takeLast((needle.length - 1).coerceAtLeast(0))
        return text.contains(needle)
    }

    private companion object {
        val ESCAPES = Regex("\u001B\\[[0-9;?<=>]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[()][0-9A-Za-z]|\u001B[=>78MDEHc]")
    }
}
