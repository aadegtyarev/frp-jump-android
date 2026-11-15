package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.store.TextStorage

class TabNameRulesTest {
    private fun valid(input: String) = (checkTabName(input) as TabNameCheck.Valid).name

    private fun problem(input: String) = (checkTabName(input) as TabNameCheck.Invalid).problem

    @Test
    fun `a name is trimmed and empty means the automatic label`() {
        assertEquals("pi", valid("  pi \t"))
        assertEquals("a b", valid("a b"))
        assertNull(valid(""))
        assertNull(valid("   "))
        // Edge line breaks are trimmed away; an inner one is a second line.
        assertEquals("pi", valid("pi\n"))
        assertEquals(TabNameProblem.BAD_CHARACTER, problem("a\nb"))
        assertEquals(TabNameProblem.BAD_CHARACTER, problem("a" + String(Character.toChars(0x2028)) + "b"))
    }

    @Test
    fun `length counts code points, 24 is the most`() {
        assertEquals("x".repeat(24), valid("x".repeat(24)))
        assertEquals(TabNameProblem.TOO_LONG, problem("x".repeat(25)))
        // Spaces around do not count: they are trimmed.
        assertEquals("x".repeat(24), valid("  " + "x".repeat(24) + "  "))
        // An emoji is two UTF-16 units and one code point.
        val emoji = "😀"
        assertEquals(emoji.repeat(24), valid(emoji.repeat(24)))
        assertEquals(TabNameProblem.TOO_LONG, problem(emoji.repeat(25)))
        assertEquals(25, (checkTabName("x".repeat(25)) as TabNameCheck.Invalid).length)
    }

    @Test
    fun `control, invisible and bidi characters are refused`() {
        val codes = listOf(
            0x0000, 0x0007, 0x0009, 0x001B, 0x007F, 0x0085, 0x00AD,
            0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF,
            0x200E, 0x200F, 0x061C, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069,
        )
        for (code in codes) {
            val bad = String(Character.toChars(code))
            assertEquals("U+%04X".format(code), TabNameProblem.BAD_CHARACTER, problem("a${bad}b"))
        }
    }

    @Test
    fun `a bar and an unpaired surrogate are refused`() {
        assertEquals(TabNameProblem.BAD_CHARACTER, problem("a|b"))
        assertEquals(TabNameProblem.BAD_CHARACTER, problem("|"))
        assertEquals(TabNameProblem.BAD_CHARACTER, problem("a\uD83Db"))
        assertEquals(TabNameProblem.BAD_CHARACTER, problem("\uDE00"))
    }

    @Test
    fun `ordinary text of any script is fine`() {
        for (ok in listOf("pi - wb7", "Рабочий стол", "日本語", "a-b_c.d", "名前 1", "café", "😀 ok")) {
            assertEquals(ok, valid(ok))
        }
    }

    private fun chars(vararg codes: Int) = String(codes, 0, codes.size)

    @Test
    fun `quotes and the middle dot are refused, so a name cannot imitate the marks around it`() {
        for (bad in listOf("\"", "\u00AB", "\u00BB", "\u00B7", "pi \u00B7 wb7", "a\"b", "\u00ABlogs\u00BB")) {
            assertEquals(bad, TabNameProblem.BAD_CHARACTER, problem(bad))
        }
    }

    @Test
    fun `invisible fillers, private-use, unassigned, marks alone and runs of spaces are refused`() {
        val invisible = listOf(0x3164, 0x115F, 0x1160, 0xFFA0, 0x2800, 0x034F, 0xFE0F, 0xFE00, 0xE0100, 0xE000, 0x0378, 0x17B4, 0x180E)
        for (code in invisible) {
            assertEquals("U+%04X alone".format(code), TabNameProblem.BAD_CHARACTER, problem(chars(code)))
            assertEquals("U+%04X inside".format(code), TabNameProblem.BAD_CHARACTER, problem("a" + chars(code) + "b"))
        }
        assertEquals(TabNameProblem.BAD_CHARACTER, problem("a  b"))
        assertEquals(TabNameProblem.BAD_CHARACTER, problem("a\u00A0b"))
        // No visible character: a combining mark alone.
        assertEquals(TabNameProblem.BAD_CHARACTER, problem("\u0301"))
        // A mark on a letter and one space are fine.
        assertEquals("e\u0301 b", valid("e\u0301 b"))
    }

    @Test
    fun `the check reports the length also when it refuses a character`() {
        assertEquals(3, (checkTabName("a|b") as TabNameCheck.Invalid).length)
    }
}

class SessionRecordStoreTest {
    private fun key(n: Int, user: String = "root", device: String = "dev", port: Int = 22, profile: String = "p1") =
        SessionKey(profile, device, port, user, "frpjump-0a1b2c-$n")

    @Test
    fun `a name is remembered and restored for the same login and session, also after a restart`() {
        val file = MemoryStorage()
        val store = SessionRecordStore(file)
        store.put(key(1), "build box")
        assertEquals("build box", store.get(key(1))?.text)
        assertEquals("build box", SessionRecordStore(file).get(key(1))?.text)
        // Another session, user, device, port or profile has its own.
        assertNull(store.get(key(2)))
        assertNull(store.get(key(1, user = "pi")))
        assertNull(store.get(key(1, device = "other")))
        assertNull(store.get(key(1, port = 2222)))
        assertNull(store.get(key(1, profile = "p2")))
    }

    @Test
    fun `who set a name is remembered, an old entry without it is the owner's`() {
        val file = MemoryStorage()
        val store = SessionRecordStore(file)
        store.put(key(1), "by me")
        store.put(key(2), "by it", NameSetter.ASSISTANT)
        assertEquals(TabName("by me", NameSetter.OWNER), SessionRecordStore(file).get(key(1)))
        assertEquals(TabName("by it", NameSetter.ASSISTANT), SessionRecordStore(file).get(key(2)))
        // The owner saving the same text makes it theirs: a write, though the name is the same.
        store.put(key(2), "by it")
        assertEquals(TabName("by it", NameSetter.OWNER), SessionRecordStore(file).get(key(2)))
        val old = """{"names":[{"profile":"p1","device":"dev","port":22,"user":"root","session":"frpjump-0a1b2c-1","name":"old"}]}"""
        assertEquals(TabName("old", NameSetter.OWNER), SessionRecordStore(MemoryStorage(old)).get(key(1)))
        assertEquals(TabName("old", NameSetter.OWNER), SessionRecordStore(MemoryStorage(old.replace("\"name\":\"old\"", "\"name\":\"old\",\"by\":\"???\""))).get(key(1)))
    }

    @Test
    fun `a new name replaces the old one and an unchanged one writes nothing`() {
        val file = CountingStorage()
        val store = SessionRecordStore(file)
        store.put(key(1), "a")
        store.put(key(1), "a")
        assertEquals(1, file.writes)
        store.put(key(1), "b")
        assertEquals("b", store.get(key(1))?.text)
        assertEquals(2, file.writes)
    }

    @Test
    fun `forget removes the name when the app ends the session`() {
        val file = MemoryStorage()
        val store = SessionRecordStore(file)
        store.put(key(1), "a")
        store.put(key(2), "b")
        store.forget(key(1))
        assertNull(store.get(key(1)))
        assertEquals("b", SessionRecordStore(file).get(key(2))?.text)
        assertNull(SessionRecordStore(file).get(key(1)))
        // Forgetting what is not there changes nothing.
        store.forget(key(9))
    }

    @Test
    fun `a successful listing forgets the names of sessions it does not have, for that login only`() {
        val store = SessionRecordStore(MemoryStorage())
        store.put(key(1), "kept")
        store.put(key(2), "gone")
        store.put(key(3, user = "pi"), "other user")
        store.put(key(4, port = 2222), "other port")
        store.put(key(5, profile = "p2"), "other profile")
        store.afterListing("p1", "dev", 22, "root", listOf("frpjump-0a1b2c-1"))
        assertEquals("kept", store.get(key(1))?.text)
        assertNull(store.get(key(2)))
        assertEquals("other user", store.get(key(3, user = "pi"))?.text)
        assertEquals("other port", store.get(key(4, port = 2222))?.text)
        assertEquals("other profile", store.get(key(5, profile = "p2"))?.text)
    }

    @Test
    fun `an empty successful listing forgets them all, a failed one nothing`() {
        val file = MemoryStorage()
        val store = SessionRecordStore(file)
        store.put(key(1), "a")
        store.put(key(2), "b")
        // Failed: null.
        store.afterListing("p1", "dev", 22, "root", null)
        assertEquals("a", store.get(key(1))?.text)
        assertEquals("b", SessionRecordStore(file).get(key(2))?.text)
        store.afterListing("p1", "dev", 22, "root", emptyList())
        assertNull(store.get(key(1)))
        assertNull(SessionRecordStore(file).get(key(2)))
    }

    @Test
    fun `a session of a tab open now is kept even when the listing lacks it`() {
        val store = SessionRecordStore(MemoryStorage())
        store.put(key(1), "a")
        store.put(key(2), "b")
        store.afterListing("p1", "dev", 22, "root", emptyList(), keep = setOf("frpjump-0a1b2c-2"))
        assertNull(store.get(key(1)))
        assertEquals("b", store.get(key(2))?.text)
    }

    @Test
    fun `no more than 200 are kept and the oldest written go first`() {
        val file = MemoryStorage()
        val store = SessionRecordStore(file)
        for (n in 1..205) store.put(key(n), "n$n")
        for (n in 1..5) assertNull("n$n", store.get(key(n)))
        for (n in 6..205) assertEquals("n$n", store.get(key(n))?.text)
        assertNull(SessionRecordStore(file).get(key(5)))
        assertEquals("n6", SessionRecordStore(file).get(key(6))?.text)
        // Writing an old one again makes it the newest: the next write drops another.
        store.put(key(6), "again")
        store.put(key(300), "new")
        assertEquals("again", store.get(key(6))?.text)
        assertNull(store.get(key(7)))
        assertEquals("new", store.get(key(300))?.text)
    }

    @Test
    fun `a foreign or edited file keeps only well-formed names`() {
        val bad = """{"names":[
            {"profile":"p1","device":"dev","port":22,"user":"root","session":"frpjump-0a1b2c-1","name":"ok"},
            {"profile":"p1","device":"dev","port":22,"user":"root","session":"not-a-session","name":"no"},
            {"profile":"p1","device":"dev","port":22,"user":"root","session":"frpjump-0a1b2c-3","name":"a|b"},
            {"profile":"p1","device":"dev","port":22,"user":"root","session":"frpjump-0a1b2c-4","name":"${"x".repeat(25)}"}]}"""
        val store = SessionRecordStore(MemoryStorage(bad))
        assertEquals("ok", store.get(key(1))?.text)
        assertNull(store.get(key(3)))
        assertNull(store.get(key(4)))
        assertNull(SessionRecordStore(MemoryStorage("{")).get(key(1)))
    }

    @Test
    fun `a write that fails keeps the name for this run`() {
        val failing = object : TextStorage {
            override fun read(): String? = null
            override fun write(text: String) = throw java.io.IOException("disk")
            override fun delete() = Unit
        }
        val store = SessionRecordStore(failing)
        store.put(key(1), "a")
        assertTrue(store.get(key(1))?.text == "a")
    }

    private class CountingStorage : TextStorage {
        var text: String? = null
        var writes = 0
        override fun read() = text
        override fun write(text: String) { this.text = text; writes++ }
        override fun delete() { text = null }
    }
}
