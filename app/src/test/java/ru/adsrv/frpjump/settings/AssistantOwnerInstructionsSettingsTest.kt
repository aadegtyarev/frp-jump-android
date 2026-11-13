package ru.adsrv.frpjump.settings

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

/** The owner's own assistant instructions: stored text, normalization on read, and that nothing else resets. */
class AssistantOwnerInstructionsSettingsTest {
    private fun read(json: String) = SettingsStore(MemoryStorage(json)).settings.value

    @Test fun `default is empty and an old file without the field reads as empty`() {
        assertEquals("", AppSettings().assistantOwnerInstructions)
        assertEquals("", SettingsStore(MemoryStorage()).assistantAvailability.ownerInstructions)
        assertEquals("", read("""{"direct":false}""").assistantOwnerInstructions)
    }

    @Test fun `text round trips through the file and the captured availability`() {
        val file = MemoryStorage()
        val store = SettingsStore(file)
        store.update { it.copy(direct = false) }
        store.setAssistantOwnerInstructions("Prefer tmux.\nWB7 controllers.")
        assertEquals("Prefer tmux.\nWB7 controllers.", store.assistantAvailability.ownerInstructions)
        assertTrue(file.text!!.contains("\"assistant_owner_instructions\""))
        val again = SettingsStore(file)
        assertEquals("Prefer tmux.\nWB7 controllers.", again.settings.value.assistantOwnerInstructions)
        assertEquals("Prefer tmux.\nWB7 controllers.", again.assistantAvailability.ownerInstructions)
        assertFalse(again.settings.value.direct)
        again.setAssistantOwnerInstructions("")
        assertEquals("", SettingsStore(file).settings.value.assistantOwnerInstructions)
    }

    @Test fun `control characters other than a line break are removed on read`() {
        val value = read("""{"assistant_owner_instructions":"a\u0000b\tc\r\nd\u001be\u007ff\u0085g"}""").assistantOwnerInstructions
        assertEquals("abc\ndefg", value)
    }

    @Test fun `text is cut to 2000 UTF-16 units without splitting a surrogate pair`() {
        val plain = "x".repeat(2500)
        assertEquals(2000, read("""{"assistant_owner_instructions":"$plain"}""").assistantOwnerInstructions.length)
        val pair = "😀"
        // The pair straddles the limit: both halves go.
        val straddle = "x".repeat(1999) + pair + "tail"
        assertEquals("x".repeat(1999), AssistantOwnerInstructions.normalize(straddle))
        // The pair ends exactly at the limit: it stays whole.
        val fits = "x".repeat(1998) + pair + "tail"
        assertEquals("x".repeat(1998) + pair, AssistantOwnerInstructions.normalize(fits))
        assertEquals(plain.take(2000), AssistantOwnerInstructions.normalize(plain))
        assertEquals("x".repeat(2000), AssistantOwnerInstructions.normalize("x".repeat(2000)))
    }

    @Test fun `a malformed value is empty and other settings are kept`() {
        for (bad in listOf("null", "1", "true", "{}", "[]", "[\"a\"]")) {
            val settings = read("""{"direct":false,"assistant_enabled":true,"assistant_search":true,"assistant_owner_instructions":$bad}""")
            assertEquals(bad, "", settings.assistantOwnerInstructions)
            assertFalse(settings.direct); assertTrue(settings.assistantEnabled); assertTrue(settings.assistantSearch)
        }
    }

    @Test fun `the store refuses a value that is not normalized and keeps the committed one`() {
        val store = SettingsStore(MemoryStorage())
        store.setAssistantOwnerInstructions("ok")
        assertThrows(IllegalArgumentException::class.java) { store.setAssistantOwnerInstructions("x".repeat(2001)) }
        assertThrows(IllegalArgumentException::class.java) { store.setAssistantOwnerInstructions("a\u0000b") }
        assertEquals("ok", store.settings.value.assistantOwnerInstructions)
        assertEquals("ok", store.assistantAvailability.ownerInstructions)
    }

    @Test fun `turning the assistant off or changing tools keeps the text and never signals a disable`() {
        val store = SettingsStore(MemoryStorage())
        store.update { it.copy(assistantEnabled = true) }
        store.setAssistantOwnerInstructions("keep me")
        store.update { it.copy(assistantEnabled = false) }
        assertEquals("keep me", store.settings.value.assistantOwnerInstructions)
        assertEquals(1L, store.assistantAvailability.disableVersion)
        val before = store.assistantAvailability.disableVersion
        store.setAssistantOwnerInstructions("changed")
        assertEquals(before, store.assistantAvailability.disableVersion)
    }

    @Test fun `effective text treats whitespace only as none`() {
        assertEquals("", AssistantOwnerInstructions.effective(" \n "))
        assertEquals("a ", AssistantOwnerInstructions.effective("a "))
    }
}
