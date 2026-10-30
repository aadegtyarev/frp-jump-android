package ru.adsrv.frpjump.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class WebSiteMemoryTest {
    private val storage = MemoryStorage()

    @Test
    fun `the first site and every other one need clearing, the same one does not`() {
        val memory = WebSiteMemory(storage)
        assertTrue(memory.isOther("wb", 40000))
        memory.remember("wb", 40000)
        assertFalse(memory.isOther("wb", 40000))
        assertTrue(memory.isOther("wb", 40001))
        assertTrue(memory.isOther("other", 40000))
    }

    @Test
    fun `the last site survives a restart and is dropped by forget`() {
        WebSiteMemory(storage).remember("wb", 40000)
        val restarted = WebSiteMemory(storage)
        assertFalse(restarted.isOther("wb", 40000))
        restarted.forget()
        assertTrue(WebSiteMemory(storage).isOther("wb", 40000))
    }
}
