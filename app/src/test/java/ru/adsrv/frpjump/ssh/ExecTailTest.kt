package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ExecTailTest {
    private fun tail(text: String, max: Int, chunk: Int = 7) = String(readTail(object : InputStream() {
        private val inner = ByteArrayInputStream(text.toByteArray())
        override fun read() = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, minOf(len, chunk))
    }, max))

    @Test fun `everything when it fits and the last bytes when it does not`() {
        assertEquals("", tail("", 10))
        assertEquals("abc", tail("abc", 10))
        assertEquals("0123456789", tail("0123456789", 10))
        assertEquals("56789", tail("0123456789", 5))
        assertEquals("ghij", tail("abcdefghij".repeat(100) + "ghij", 4))
        val long = (1..5000).joinToString("\n")
        assertEquals(long.takeLast(321), tail(long, 321))
        assertEquals(long.takeLast(321), tail(long, 321, chunk = 8192))
    }
}
