package ru.adsrv.frpjump.settings

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.TextStorage

class AssistantSettingsTest {
    @Test fun `new and old settings keep assistant disabled by default`() {
        assertFalse(SettingsStore(MemoryStorage()).settings.value.assistantEnabled)
        assertFalse(SettingsStore(MemoryStorage().apply { write("""{"direct":false}""") }).settings.value.assistantEnabled)
    }
    @Test fun `enable disable round trip preserves unrelated settings`() {
        val file = MemoryStorage()
        val store = SettingsStore(file)
        store.update { it.copy(direct = false, assistantEnabled = true) }
        assertTrue(SettingsStore(file).settings.value.assistantEnabled)
        store.update { it.copy(assistantEnabled = false) }
        val restored = SettingsStore(file).settings.value
        assertFalse(restored.assistantEnabled); assertFalse(restored.direct)
    }
    @Test fun `invalid new flag cannot reset unrelated settings or enable the module`() {
        for (value in listOf("null", "1", "\"true\"", "{}", "[]")) {
            val store = SettingsStore(MemoryStorage().apply { write("""{"direct":false,"assistant_enabled":$value}""") })
            assertFalse(store.settings.value.assistantEnabled)
            assertFalse(store.settings.value.direct)
        }
    }
    @Test fun `failed disable write retains committed preference`() {
        val file = object : TextStorage {
            var data: String? = """{"assistant_enabled":true}"""
            override fun read() = data
            override fun write(text: String): Unit = throw java.io.IOException("Synthetic settings failure")
            override fun delete() { data = null }
        }
        val store = SettingsStore(file)
        assertThrows(java.io.IOException::class.java) { store.update { it.copy(assistantEnabled = false) } }
        assertTrue(store.settings.value.assistantEnabled)
        assertTrue(SettingsStore(file).settings.value.assistantEnabled)
    }
}
