package ru.adsrv.frpjump.ports

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.store.TextStorage

/** Unknown shared reservations must not become an empty map during a fallible multi-store save/plan. */
class LocalPortRecoveryTest {
    private class FaultStorage : TextStorage {
        var value: String? = """{"A/device:22":40000}"""
        var failRead = false
        var writes = 0
        override fun read(): String? {
            if (failRead) throw IOException("Synthetic port-map read failure")
            return value
        }
        override fun write(text: String) { writes++; value = text }
        override fun delete() { value = null }
    }

    @Test fun `unread reservation map cannot run a save callback or overwrite other profiles`() {
        val file = FaultStorage()
        val original = file.value
        val store = LocalPortStore(file)
        file.failRead = true
        var called = false
        assertThrows(IOException::class.java) {
            store.update { ports -> called = true; ports["B/other:22"] = 40001 }
        }
        assertFalse("A callback may durably save another file; do not run it on unknown map state", called)
        assertEquals(0, file.writes)
        assertEquals(original, file.value)
    }

    @Test fun `tolerant reads do not cache an IO failure and a later mutation preserves the old map`() {
        val file = FaultStorage()
        val store = LocalPortStore(file)
        file.failRead = true
        assertTrue(store.load().isEmpty())
        assertThrows(IOException::class.java) { store.update { it["B/other:22"] = 40001 } }
        file.failRead = false
        store.update { it["B/other:22"] = 40001 }
        assertEquals(mapOf("A/device:22" to 40000, "B/other:22" to 40001), store.load())
    }

    @Test fun `malformed JSON fallback is unchanged from the existing recovery policy`() {
        val file = FaultStorage().apply { value = "not-json" }
        val store = LocalPortStore(file)
        assertTrue(store.load().isEmpty())
        store.update { it["B/other:22"] = 40001 }
        assertEquals(mapOf("B/other:22" to 40001), store.load())
    }

    @Test fun `successful updates retain unrelated reservations and return the callback result`() {
        val file = FaultStorage()
        val store = LocalPortStore(file)
        assertEquals("saved", store.update { ports -> ports["B/other:22"] = 40001; "saved" })
        assertEquals(mapOf("A/device:22" to 40000, "B/other:22" to 40001), store.load())
        assertEquals(1, file.writes)
    }
}
