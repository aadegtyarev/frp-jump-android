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
    @Test fun `tools default independently off normalize malformed fields and preserve committed preferences on failure`() {
        assertEquals(AssistantTools(), SettingsStore(MemoryStorage()).assistantAvailability.tools)
        for (bad in listOf("null", "1", "\"true\"", "{}", "[]")) {
            val store = SettingsStore(MemoryStorage("""{"direct":false,"assistant_search":$bad,"assistant_read_page":true,"assistant_inspect_image":false}"""))
            assertEquals(AssistantTools(false, true, false), store.assistantAvailability.tools)
            assertFalse(store.settings.value.direct)
        }
        val file = object : TextStorage {
            var data: String? = null; var fail = false
            override fun read() = data
            override fun write(text: String) { if (fail) error("synthetic"); data = text }
            override fun delete() { data = null }
        }
        val store = SettingsStore(file)
        store.update { it.copy(assistantSearch = true) }
        store.update { it.copy(assistantSearch = false) }
        store.update { it.copy(assistantSearch = true) }
        assertEquals(0L, store.assistantAvailability.disableVersion)
        val authority = store.assistantAvailability
        file.fail = true
        assertThrows(IllegalStateException::class.java) { store.update { it.copy(assistantSearch = false) } }
        assertEquals(authority, store.assistantAvailability)
        assertTrue(store.settings.value.assistantSearch)
        assertTrue(SettingsStore(file).settings.value.assistantSearch)
    }
    @Test fun `profile information defaults off and malformed values preserve siblings`() {
        assertFalse(SettingsStore(MemoryStorage("""{"assistant_search":true}""")).assistantAvailability.tools.profileInformation)
        for (bad in listOf("null", "1", "\"true\"", "{}", "[]")) {
            val store = SettingsStore(MemoryStorage("""{"direct":false,"assistant_search":true,"assistant_profile_information":$bad}"""))
            assertEquals(AssistantTools(search = true), store.assistantAvailability.tools)
            assertFalse(store.settings.value.direct)
        }
    }
    @Test fun `each tool operation preserves sibling and unrelated committed fields without disable signal`() {
        val file = MemoryStorage()
        val store = SettingsStore(file).apply { update { it.copy(direct = false, assistantEnabled = true) } }
        var expected = AssistantTools()
        for (id in listOf("web_search", "read_page", "inspect_image", "profile_information")) for (value in listOf(true, false)) {
            store.setAssistantTool(id, value)
            expected = when (id) {
                "web_search" -> expected.copy(search = value)
                "read_page" -> expected.copy(readPage = value)
                "inspect_image" -> expected.copy(inspectImage = value)
                else -> expected.copy(profileInformation = value)
            }
            assertEquals(expected, store.assistantAvailability.tools)
            assertEquals(expected, SettingsStore(file).assistantAvailability.tools)
            assertEquals(expected, store.settings.value.assistantTools())
            assertFalse(SettingsStore(file).settings.value.direct)
            assertTrue(store.assistantAvailability.enabled)
            assertEquals(0L, store.assistantAvailability.disableVersion)
            assertEquals(0L, store.assistantDisables.value)
        }
    }

}
