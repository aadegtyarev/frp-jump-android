package ru.adsrv.frpjump.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class WebSiteMemoryTest {
    private val storage = MemoryStorage()

    @Test
    fun `the first site and every other one need clearing, the same one does not`() {
        val memory = WebSiteMemory(storage)
        assertTrue(memory.isOther("p1", "wb", 40000))
        memory.remember("p1", "wb", 40000)
        assertFalse(memory.isOther("p1", "wb", 40000))
        assertTrue(memory.isOther("p1", "wb", 40001))
        assertTrue(memory.isOther("p1", "other", 40000))
        // Another profile's device of the same name is another site.
        assertTrue(memory.isOther("p2", "wb", 40000))
    }

    @Test
    fun `the last site survives a restart and is dropped by forget`() {
        WebSiteMemory(storage).remember("p1", "wb", 40000)
        val restarted = WebSiteMemory(storage)
        assertFalse(restarted.isOther("p1", "wb", 40000))
        assertTrue(restarted.belongsTo("p1"))
        assertFalse(restarted.belongsTo("p2"))
        restarted.forget()
        assertTrue(WebSiteMemory(storage).isOther("p1", "wb", 40000))
    }

    @Test
    fun `a v1 site becomes the migrated profile's, once`() {
        val migrated = WebSiteMemory.migrateLegacy("""["wb","40002"]""", "p1")!!
        storage.text = migrated
        assertFalse(WebSiteMemory(storage).isOther("p1", "wb", 40002))
        assertNull(WebSiteMemory.migrateLegacy(migrated, "p1"))
        assertEquals(null, WebSiteMemory.migrateLegacy("not json", "p1"))
    }
}
