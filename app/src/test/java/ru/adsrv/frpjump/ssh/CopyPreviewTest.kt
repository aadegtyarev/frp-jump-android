package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class CopyPreviewTest {
    @Test
    fun `a short line is shown whole`() = assertEquals("ls -la", copyPreview("ls -la", 40))

    @Test
    fun `a long line is cut with an ellipsis`() = assertEquals("abcd…", copyPreview("abcdefgh", 4))

    @Test
    fun `more lines than one end in an ellipsis`() {
        assertEquals("first…", copyPreview("first\nsecond", 40))
        assertEquals("first…", copyPreview("first\rsecond", 40))
    }

    @Test
    fun `blank leading lines are skipped`() = assertEquals("secret", copyPreview("\n  \nsecret\n", 40))

    @Test
    fun `nothing but blanks shows an ellipsis`() = assertEquals("…", copyPreview("\n \n", 40))

    @Test
    fun `control and bidi override characters are dropped`() =
        assertEquals("evil.sh", copyPreview("\u0007evil\u202E.sh\u0000", 40))

    @Test
    fun `a surrogate pair is not cut in half`() = assertEquals("a😀…", copyPreview("a😀bc", 2))
}
