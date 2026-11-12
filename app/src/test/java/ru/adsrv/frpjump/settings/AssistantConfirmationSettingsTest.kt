package ru.adsrv.frpjump.settings

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class AssistantConfirmationSettingsTest {
    private fun store(json: String?) = SettingsStore(MemoryStorage(json))

    @Test fun `new and old settings ask by default`() {
        assertEquals(ConfirmationMode.ASK, store(null).assistantConfirmations.global)
        assertEquals(ConfirmationMode.ASK, store("""{"direct":false}""").assistantConfirmations.global)
        assertEquals(ConfirmationPolicy(), store(null).settings.value.assistantConfirmations)
    }

    @Test fun `only a version 1 object with global off turns requests off`() {
        val off = store("""{"assistant_confirmations":{"version":1,"global":"off"}}""")
        assertEquals(ConfirmationMode.OFF, off.assistantConfirmations.global)
        assertEquals(ConfirmationMode.OFF, off.settings.value.assistantConfirmations.global)
    }

    @Test fun `every malformed value asks and cannot reset unrelated settings`() {
        val bad = listOf("null", "1", "true", "\"off\"", "[]", "{}", """{"global":"off"}""", """{"version":2,"global":"off"}""",
            """{"version":"1","global":"off"}""", """{"version":1,"global":"OFF"}""", """{"version":1,"global":true}""",
            """{"version":1,"global":null}""", """{"version":1.5,"global":"off"}""", """{"version":1,"global":"never"}""",
            """{"version":1,"global":["off"]}""", """{"version":1}""")
        for (value in bad) {
            val s = store("""{"direct":false,"assistant_search":true,"assistant_confirmations":$value}""")
            assertEquals(value, ConfirmationMode.ASK, s.assistantConfirmations.global)
            assertEquals(value, ConfirmationMode.ASK, s.settings.value.assistantConfirmations.global)
            assertFalse(value, s.settings.value.direct); assertTrue(value, s.settings.value.assistantSearch)
        }
    }

    @Test fun `unknown extra keys and not yet supported overrides are ignored`() {
        val s = store("""{"assistant_confirmations":{"version":1,"global":"off","extra":1,"overrides":[{"scope":{"kind":"device","id":"d"},"mode":"ask"}]}}""")
        assertEquals(ConfirmationPolicy(global = ConfirmationMode.OFF), s.assistantConfirmations)
    }

    @Test fun `the switch persists across restart without touching the disable signal or siblings`() {
        val file = MemoryStorage()
        val s = SettingsStore(file).apply { update { it.copy(direct = false, assistantEnabled = true, assistantSshCommand = true) } }
        s.setAssistantConfirmations(ConfirmationMode.OFF)
        assertEquals(ConfirmationMode.OFF, s.assistantConfirmations.global)
        assertEquals(0L, s.assistantAvailability.disableVersion); assertEquals(0L, s.assistantDisables.value)
        assertTrue(s.assistantAvailability.enabled); assertTrue(s.assistantAvailability.tools.sshCommand)
        val again = SettingsStore(file)
        assertEquals(ConfirmationMode.OFF, again.assistantConfirmations.global); assertFalse(again.settings.value.direct)
        s.setAssistantConfirmations(ConfirmationMode.ASK)
        assertEquals(ConfirmationMode.ASK, SettingsStore(file).assistantConfirmations.global)
        assertEquals(0L, s.assistantAvailability.disableVersion)
    }

    @Test fun `other updates keep the value and a failed write keeps the committed one`() {
        val file = object : ru.adsrv.frpjump.store.TextStorage {
            var data: String? = """{"assistant_confirmations":{"version":1,"global":"off"}}"""
            var fail = false
            override fun read() = data
            override fun write(text: String) { if (fail) throw java.io.IOException("Synthetic"); data = text }
            override fun delete() { data = null }
        }
        val s = SettingsStore(file)
        s.update { it.copy(direct = false) }
        assertEquals(ConfirmationMode.OFF, s.assistantConfirmations.global)
        file.fail = true
        assertThrows(java.io.IOException::class.java) { s.setAssistantConfirmations(ConfirmationMode.ASK) }
        assertEquals(ConfirmationMode.OFF, s.assistantConfirmations.global)
        assertEquals(ConfirmationMode.OFF, SettingsStore(file).assistantConfirmations.global)
    }

    @Test fun `the value is read live and a disable does not reset it`() {
        val s = SettingsStore(MemoryStorage()).apply { update { it.copy(assistantEnabled = true) } }
        s.setAssistantConfirmations(ConfirmationMode.OFF)
        s.update { it.copy(assistantEnabled = false) }
        assertEquals(ConfirmationMode.OFF, s.assistantConfirmations.global)
        assertEquals(1L, s.assistantAvailability.disableVersion)
    }

    @Test fun `golden JSON ask is omitted so old files stay byte-identical and off is the versioned object`() {
        assertEquals("{}", ru.adsrv.frpjump.api.WireJson.encodeToString(AppSettings.serializer(), AppSettings()))
        assertEquals("""{"assistant_confirmations":{"version":1,"global":"off"}}""",
            ru.adsrv.frpjump.api.WireJson.encodeToString(AppSettings.serializer(), AppSettings(assistantConfirmations = ConfirmationPolicy(global = ConfirmationMode.OFF))))
        val file = MemoryStorage("""{"direct":false,"assistant_enabled":true}""")
        SettingsStore(file).update { it.copy(assistantSearch = true) }
        assertFalse(file.text!!.contains("assistant_confirmations"))
        val off = MemoryStorage(); val s = SettingsStore(off)
        s.setAssistantConfirmations(ConfirmationMode.OFF); s.setAssistantConfirmations(ConfirmationMode.ASK)
        assertEquals("{}", off.text)
    }
}
