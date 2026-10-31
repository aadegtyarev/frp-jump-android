package ru.adsrv.frpjump.restore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.TextStorage
import java.io.IOException

class RestoreRecordTest {
    private val storage = MemoryStorage()
    private fun record(on: TextStorage = storage) = RestoreRecord(on).apply { load() }

    @Test
    fun `unknown until loaded, then empty without a file`() {
        val record = RestoreRecord(storage)
        assertNull(record.profiles.value)
        assertEquals(emptyMap<String, Int>(), record.load())
        assertEquals(emptyMap<String, Int>(), record.profiles.value)
    }

    @Test
    fun `tunnels per profile survive a restart`() {
        record().apply {
            set("work", 2)
            set("home", 1)
        }
        assertEquals(mapOf("work" to 2, "home" to 1), record().profiles.value)
    }

    @Test
    fun `no tunnels left drops the profile, the last one the file`() {
        val record = record()
        record.set("work", 2)
        record.set("home", 1)
        record.set("work", 0)
        assertEquals(mapOf("home" to 1), record().profiles.value)
        record.set("home", 0)
        assertNull(storage.text)
    }

    @Test
    fun `written only when it changes`() {
        var writes = 0
        val counting = object : TextStorage by storage {
            override fun write(text: String) {
                writes++
                storage.write(text)
            }
        }
        val record = record(counting)
        record.set("work", 1)
        record.set("work", 1)
        record.set("home", 0)
        assertEquals(1, writes)
    }

    @Test
    fun `a failed write keeps memory as on disk and is tried again`() {
        var fail = true
        val flaky = object : TextStorage by storage {
            override fun write(text: String) {
                if (fail) throw IOException("disk full")
                storage.write(text)
            }
        }
        val record = record(flaky)
        record.set("work", 1)
        assertEquals(emptyMap<String, Int>(), record.profiles.value)
        fail = false
        record.set("work", 1)
        assertEquals(mapOf("work" to 1), record().profiles.value)
    }

    @Test
    fun `an unreadable record restores nothing, the next change replaces it`() {
        storage.text = "{not json"
        val record = record()
        assertEquals(emptyMap<String, Int>(), record.profiles.value)
        record.set("work", 1)
        assertEquals(mapOf("work" to 1), record().profiles.value)
    }

    @Test
    fun `profiles no longer listed are dropped`() {
        val record = record()
        record.set("work", 1)
        record.set("gone", 3)
        record.retain(setOf("work", "home"))
        assertEquals(mapOf("work" to 1), record().profiles.value)
    }
}
