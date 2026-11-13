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

    @Test fun `invisible tag characters and bidi controls are removed but joiners stay`() {
        val tag = "󠁁" // U+E0041, a tag letter
        val value = "a${tag}b‮c‪d‬e⁦f⁩g‍h‌i󠁿"
        assertEquals("abcdefg‍h‌i", AssistantOwnerInstructions.normalize(value))
        // Just outside the removed ranges: U+E0080 and U+2065 and U+202F stay.
        assertEquals("󠂀⁥ ", AssistantOwnerInstructions.normalize("󠂀⁥ "))
        assertEquals("ab", read("""{"assistant_owner_instructions":"a‮b󠁁"}""").assistantOwnerInstructions)
        val store = SettingsStore(MemoryStorage())
        assertThrows(IllegalArgumentException::class.java) { store.setAssistantOwnerInstructions("a‮b") }
        assertThrows(IllegalArgumentException::class.java) { store.setAssistantOwnerInstructions("a${tag}b") }
    }

    @Test fun `normalize is idempotent even when removing a character joins a surrogate pair`() {
        // A control character between the halves of a tag character: after it goes, the halves form the tag character, which goes too.
        val corrupt = "\uDB40\u0000\uDC41"
        assertEquals("", AssistantOwnerInstructions.normalize(corrupt))
        assertEquals("ab", AssistantOwnerInstructions.normalize("a\uDB40\u0000‮\uDC41b"))
        val random = java.util.Random(20261113)
        val alphabet = listOf("a", "b", "\n", "\u0000", "\t", "‮", "⁦", "‍", "\uDB40", "\uDC41", "\uD83D", "\uDE00", "󠁁", "\u007F")
        repeat(2000) {
            val text = buildString { repeat(random.nextInt(40)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            val once = AssistantOwnerInstructions.normalize(text)
            assertEquals("not idempotent for ${text.map { it.code.toString(16) }}", once, AssistantOwnerInstructions.normalize(once))
            assertEquals(once, AssistantOwnerInstructions.sanitize(once))
        }
        val long = "x".repeat(1999) + "\uD83D\u0000\uDE00" + "tail"
        assertEquals(AssistantOwnerInstructions.normalize(long), AssistantOwnerInstructions.normalize(AssistantOwnerInstructions.normalize(long)))
    }

    @Test fun `a corrupted stored value is repaired on read and later settings updates do not throw`() {
        val file = MemoryStorage("""{"direct":true,"assistant_owner_instructions":"\udb40\u0000\udc41ok"}""")
        val store = SettingsStore(file)
        assertEquals("ok", store.settings.value.assistantOwnerInstructions)
        store.update { it.copy(direct = false) }
        store.setAssistantOwnerInstructions("fine")
        assertFalse(SettingsStore(file).settings.value.direct)
        assertEquals("fine", SettingsStore(file).settings.value.assistantOwnerInstructions)
        // A value that repairs to nothing leaves an empty string and unrelated writes still work.
        val empty = SettingsStore(MemoryStorage("""{"assistant_owner_instructions":"\udb40\u0000\udc41"}"""))
        assertEquals("", empty.settings.value.assistantOwnerInstructions)
        empty.update { it.copy(direct = false) }
        assertFalse(empty.settings.value.direct)
    }

    @Test fun `limitEdit result is always a fixed point of normalize`() {
        // Inserting a control character between two tag halves that sit around it must not leave a tag character behind.
        val edit = AssistantOwnerInstructions.limitEdit("󠁁", "\uDB40\u0000\uDC41", 2)
        assertEquals(AssistantOwnerInstructions.normalize(edit.text), edit.text)
    }

    @Test fun `limitEdit keeps the text around an insertion and cuts only the inserted part`() {
        val full = "x".repeat(1997) + "END"
        // Typing at the start of a full field changes nothing; the last character survives.
        val atStart = AssistantOwnerInstructions.limitEdit(full, "Z$full", 1)
        assertEquals(full, atStart.text); assertEquals(0, atStart.cursor)
        // A paste into the middle keeps both ends and the cursor goes right after the kept part.
        val old = "S" + "m".repeat(1996) + "E"
        val pasted = AssistantOwnerInstructions.limitEdit(old, "S" + "P".repeat(100) + old.substring(1), 101)
        assertEquals(2000, pasted.text.length)
        assertTrue(pasted.text.startsWith("SPP") && pasted.text.endsWith("mE"))
        assertEquals("S" + "P".repeat(2) + old.substring(1), pasted.text)
        assertEquals(3, pasted.cursor)
        // Under the limit nothing changes and the cursor is the keyboard's.
        assertEquals(AssistantOwnerInstructions.Edit("abXc", 3), AssistantOwnerInstructions.limitEdit("abc", "abXc", 3))
        assertEquals(AssistantOwnerInstructions.Edit("ab", 1), AssistantOwnerInstructions.limitEdit("abc", "ab", 1))
        // Exactly at the limit is allowed.
        val atLimit = AssistantOwnerInstructions.limitEdit("x".repeat(1999), "x".repeat(1999) + "y", 2000)
        assertEquals(2000, atLimit.text.length); assertEquals(2000, atLimit.cursor)
        assertEquals("x".repeat(2000), AssistantOwnerInstructions.limitEdit("x".repeat(2000), "x".repeat(2000) + "y", 2001).text)
    }

    @Test fun `limitEdit sanitizes the insertion and puts the cursor after it`() {
        val edit = AssistantOwnerInstructions.limitEdit("ab", "a\u0000‮xb", 4)
        assertEquals("axb", edit.text); assertEquals(2, edit.cursor)
        val tag = "󠁁"
        assertEquals(AssistantOwnerInstructions.Edit("ab", 1), AssistantOwnerInstructions.limitEdit("ab", "a${tag}b", 3))
    }

    @Test fun `limitEdit never splits a surrogate pair`() {
        val pair = "😀"
        val old = "x".repeat(1999)
        // One unit of room: the pair does not fit, none of it is kept.
        val one = AssistantOwnerInstructions.limitEdit(old, pair + old, 2)
        assertEquals(old, one.text)
        // Two units of room: the pair fits whole.
        val two = AssistantOwnerInstructions.limitEdit("x".repeat(1998), pair + "x".repeat(1998), 2)
        assertEquals(pair + "x".repeat(1998), two.text); assertEquals(2, two.cursor)
        // A pair after the common start is kept or dropped whole when cut in the middle of a paste.
        val cut = AssistantOwnerInstructions.limitEdit("x".repeat(1997) + "E", "x".repeat(1997) + pair + pair + "E", 1999)
        assertEquals("x".repeat(1997) + pair + "E", cut.text)
        assertTrue(cut.text.none { it.isHighSurrogate() } || cut.text.windowed(2).none { it.length == 2 && it[0].isHighSurrogate() && !it[1].isLowSurrogate() })
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
