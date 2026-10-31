package ru.adsrv.frpjump.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class DirectKeptStoreTest {
    private val storage = MemoryStorage()
    private fun store() = DirectKeptStore(storage).apply { load() }

    @Test
    fun `kept per profile, device and network`() {
        val kept = store()
        kept.keep("work", "wb", "wifi:aa")
        assertTrue(kept.isKept("work", "wb", "wifi:aa"))
        assertFalse("another network", kept.isKept("work", "wb", "mobile"))
        assertFalse("another device", kept.isKept("work", "beelink", "wifi:aa"))
        assertFalse("another profile", kept.isKept("home", "wb", "wifi:aa"))
        assertFalse("no network", kept.isKept("work", "wb", null))
    }

    @Test
    fun `survives a restart and a retry releases it on that network only`() {
        store().apply {
            keep("work", "wb", "wifi:aa")
            keep("work", "wb", "mobile")
        }
        val reread = store()
        assertTrue(reread.isKept("work", "wb", "mobile"))
        reread.release("work", "wb", "mobile")
        assertFalse(store().isKept("work", "wb", "mobile"))
        assertTrue(store().isKept("work", "wb", "wifi:aa"))
    }

    @Test
    fun `without a network nothing is kept`() {
        val kept = store()
        kept.keep("work", "wb", null)
        assertEquals(emptyList<KeptThroughServer>(), kept.kept.value)
        assertEquals(null, storage.text)
    }

    @Test
    fun `forgetting a profile drops its choices`() {
        val kept = store()
        kept.keep("work", "wb", "mobile")
        kept.keep("home", "wb", "mobile")
        kept.removeProfile("work")
        assertEquals(listOf(KeptThroughServer("home", "wb", "mobile")), store().kept.value)
    }

    @Test
    fun `an unreadable file keeps nothing`() {
        storage.text = "{not json"
        assertEquals(emptyList<KeptThroughServer>(), store().kept.value)
    }
}
