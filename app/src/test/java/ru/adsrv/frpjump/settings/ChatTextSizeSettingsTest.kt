package ru.adsrv.frpjump.settings

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class ChatTextSizeSettingsTest {
    private fun store(json: String?) = SettingsStore(MemoryStorage(json))

    @Test fun `new and old settings files read as 100 percent`() {
        assertEquals(100, store(null).settings.value.assistantChatTextSize)
        assertEquals(100, store("""{"direct":false}""").settings.value.assistantChatTextSize)
        assertEquals(1f, ChatTextSize.factor(100), 0f)
    }

    @Test fun `every step reads back as itself`() {
        for (step in ChatTextSize.STEPS) assertEquals(step, store("""{"assistant_chat_text_size":$step}""").settings.value.assistantChatTextSize)
        assertEquals(listOf(90, 100, 115, 130, 150, 175, 200), ChatTextSize.STEPS)
    }

    @Test fun `malformed values read as 100 and cannot reset unrelated settings`() {
        val bad = listOf("null", "\"130\"", "\"\"", "true", "[]", "[130]", "{}", """{"v":130}""", "\"abc\"")
        for (value in bad) {
            val s = store("""{"direct":false,"assistant_search":true,"assistant_chat_text_size":$value}""")
            assertEquals(value, 100, s.settings.value.assistantChatTextSize)
            assertFalse(value, s.settings.value.direct); assertTrue(value, s.settings.value.assistantSearch)
        }
    }

    @Test fun `out of range and in between numbers snap to the nearest step`() {
        val cases = mapOf("0" to 90, "-50" to 90, "50" to 90, "89" to 90, "94" to 90, "96" to 100, "107" to 100, "108" to 115,
            "120" to 115, "123" to 130, "140" to 130, "141" to 150, "160" to 150, "163" to 175, "187" to 175, "188" to 200, "190" to 200, "500" to 200,
            "99999999999" to 200, "130.4" to 130, "1e2" to 100, "1e9" to 200)
        for ((raw, step) in cases) assertEquals(raw, step, store("""{"assistant_chat_text_size":$raw}""").settings.value.assistantChatTextSize)
    }

    @Test fun `the setter snaps and persists across restart without touching siblings`() {
        val file = MemoryStorage()
        val s = SettingsStore(file).apply { update { it.copy(direct = false, assistantEnabled = true) } }
        s.setAssistantChatTextSize(130)
        assertEquals(130, s.settings.value.assistantChatTextSize)
        assertTrue(file.text!!.contains(""""assistant_chat_text_size":130"""))
        val again = SettingsStore(file)
        assertEquals(130, again.settings.value.assistantChatTextSize)
        assertFalse(again.settings.value.direct); assertTrue(again.settings.value.assistantEnabled)
        again.setAssistantChatTextSize(137)
        assertEquals(130, again.settings.value.assistantChatTextSize)
        again.setAssistantChatTextSize(5000)
        assertEquals(200, SettingsStore(file).settings.value.assistantChatTextSize)
        again.setAssistantChatTextSize(100)
        assertFalse("the default is omitted from the file", file.text!!.contains("assistant_chat_text_size"))
        assertEquals(100, SettingsStore(file).settings.value.assistantChatTextSize)
    }

    @Test fun `the display size touches no assistant authority or availability`() {
        val s = SettingsStore(MemoryStorage()).apply { update { it.copy(assistantEnabled = true, assistantSshCommand = true) } }
        val before = s.assistantAvailability
        s.setAssistantChatTextSize(175)
        assertEquals(before, s.assistantAvailability)
        assertEquals(0L, s.assistantDisables.value)
        assertEquals(ConfirmationMode.ASK, s.assistantConfirmations.global)
    }

    @Test fun `a direct update with a value that is not a step is refused`() {
        val s = store(null)
        assertThrows(IllegalArgumentException::class.java) { s.update { it.copy(assistantChatTextSize = 120) } }
        assertEquals(100, s.settings.value.assistantChatTextSize)
    }

    @Test fun `factor is the percent over 100 and snaps first`() {
        assertEquals(0.9f, ChatTextSize.factor(90), 1e-6f)
        assertEquals(2f, ChatTextSize.factor(200), 1e-6f)
        assertEquals(1.3f, ChatTextSize.factor(131), 1e-6f)
    }
}
