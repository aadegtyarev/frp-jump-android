package ru.adsrv.frpjump.files

import org.junit.Assert.assertEquals
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
}
