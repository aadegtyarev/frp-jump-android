package ru.adsrv.frpjump.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class DirectOfferedStoreTest {
    private val storage = MemoryStorage()
    private fun store() = DirectOfferedStore(storage).apply { load() }
    private val wb = OfferedDirect("work", "wb", "wifi:a")

    @Test
    fun `offers made survive a restart until removed`() {
        store().add(wb)
        assertTrue(wb in store())
        store().removeIf { it.device == "wb" }
        assertFalse(wb in store())
        assertNull("nothing left, no file", storage.text)
    }

    @Test
    fun `an unreadable file remembers nothing`() {
        storage.text = "{not json"
        assertFalse(wb in store())
    }
}
