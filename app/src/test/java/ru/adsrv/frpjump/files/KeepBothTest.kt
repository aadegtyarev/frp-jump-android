package ru.adsrv.frpjump.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepBothTest {
    @Test
    fun `a free name stays as it is`() {
        assertEquals("report.pdf", keepBothName("report.pdf") { false })
    }

    @Test
    fun `a taken name gets the first free number before the extension`() {
        val taken = setOf("report.pdf", "report (1).pdf")
        assertEquals("report (2).pdf", keepBothName("report.pdf") { it in taken })
        assertEquals("README (1)", keepBothName("README") { it == "README" })
    }

    @Test
    fun `dot files and archives keep their ending whole`() {
        assertEquals(".bashrc (1)", keepBothName(".bashrc") { it == ".bashrc" })
        assertEquals("backup (1).tar.gz", keepBothName("backup.tar.gz") { it == "backup.tar.gz" })
        assertEquals("name. (1)", keepBothName("name.") { it == "name." })
    }

    @Test
    fun `an upload is written under a hidden part name first`() {
        assertEquals(".x.js.frpjump-part", uploadPartName("x.js"))
    }

    @Test
    fun `a long name's part name stays within 255 bytes and differs from another long name's`() {
        val long = "ж".repeat(126) + ".txt"
        val part = uploadPartName(long)
        assertTrue(part.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES)
        assertTrue(part.startsWith(".ж") && part.endsWith(".frpjump-part"))
        assertNotEquals(part, uploadPartName("ж".repeat(126) + ".log"))
        val ascii = "a".repeat(250)
        assertTrue(uploadPartName(ascii).toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES)
    }

    @Test
    fun `a device's name is made safe for the phone's cache`() {
        assertEquals("a_b", safeLocalName("a/b"))
        assertEquals("a_b", safeLocalName("a\u0000b"))
        assertEquals("file", safeLocalName("."))
        assertEquals("file", safeLocalName(".."))
        assertEquals("file", safeLocalName(""))
        assertEquals(".bashrc", safeLocalName(".bashrc"))
    }
}
