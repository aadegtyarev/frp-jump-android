package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one store of session records (the generalized tab names), the marker on the device and the owner's restore that skips the assistant's sessions. */
class SessionRecordsTest {
    private val key = SessionKey("p1", "pi", 22, "root", "frpjump-0a1b2c-1")
    private val other = SessionKey("p1", "pi", 22, "root", "frpjump-0a1b2c-2")

    // ---- the store ----

    @Test fun `a file of the first version, tab names only, still reads as the owner's records`() {
        val storage = MemoryTextStorage()
        storage.write("""{"names":[{"profile":"p1","device":"pi","port":22,"user":"root","session":"frpjump-0a1b2c-1","name":"logs"},""" +
            """{"profile":"p1","device":"pi","port":22,"user":"root","session":"frpjump-0a1b2c-2","name":"build","by":"assistant"}]}""")
        val store = SessionRecordStore(storage)
        assertEquals(SessionRecord(TabName("logs", NameSetter.OWNER), SessionOpener.OWNER), store.record(key))
        assertEquals(SessionRecord(TabName("build", NameSetter.ASSISTANT), SessionOpener.OWNER), store.record(other))
        assertEquals("logs", store.get(key)?.text)
        assertTrue(store.assistantSessions().isEmpty())
        // Writing it again keeps the old shape's fields readable by the old reader: names stay in "names".
        store.put(key, "logs2")
        assertTrue(storage.read()!!.contains("\"names\""))
    }

    @Test fun `an assistant session is remembered without a name and keeps its record when the name is cleared`() {
        val store = SessionRecordStore(MemoryTextStorage())
        store.put(key, null, openedBy = SessionOpener.ASSISTANT)
        assertEquals(SessionRecord(null, SessionOpener.ASSISTANT), store.record(key))
        assertEquals(listOf(key), store.assistantSessions().map { it.first })
        store.put(key, "deploy", NameSetter.ASSISTANT)
        assertEquals(SessionRecord(TabName("deploy", NameSetter.ASSISTANT), SessionOpener.ASSISTANT), store.record(key))
        store.clearName(key)
        assertEquals(SessionRecord(null, SessionOpener.ASSISTANT), store.record(key))
        // The owner's own session without a name is no record at all.
        store.put(other, "x"); store.clearName(other)
        assertNull(store.record(other))
        store.put(other, null)
        assertNull(store.record(other))
    }

    @Test fun `records survive a restart, and a foreign or edited file keeps only well-formed ones`() {
        val storage = MemoryTextStorage()
        SessionRecordStore(storage).apply { put(key, null, openedBy = SessionOpener.ASSISTANT); put(other, "a b") }
        val again = SessionRecordStore(storage)
        assertEquals(SessionOpener.ASSISTANT, again.record(key)?.openedBy); assertEquals("a b", again.get(other)?.text)
        storage.write("""{"names":[{"profile":"p","device":"d","port":22,"user":"u","session":"not-a-session","name":"x"},""" +
            """{"profile":"p","device":"d","port":22,"user":"u","session":"frpjump-0a1b2c-3","name":""},""" +
            """{"profile":"p","device":"d","port":22,"user":"u","session":"frpjump-0a1b2c-4","name":"a|b"},""" +
            """{"profile":"p","device":"d","port":22,"user":"u","session":"frpjump-0a1b2c-5","name":"","opened":"assistant"}]}""")
        val s = SessionRecordStore(storage)
        assertEquals(listOf("frpjump-0a1b2c-5"), s.assistantSessions().map { it.first.session })
        assertNull(s.record(SessionKey("p", "d", 22, "u", "frpjump-0a1b2c-3"))); assertNull(s.record(SessionKey("p", "d", 22, "u", "frpjump-0a1b2c-4")))
    }

    @Test fun `the same forget rules, a listing without it and the profile removed, and the cap`() {
        val store = SessionRecordStore(MemoryTextStorage(), max = 3)
        store.put(key, null, openedBy = SessionOpener.ASSISTANT); store.put(other, "n")
        // A failed listing forgets nothing; a listing that has only one of them forgets the other, except one a session holds open.
        store.afterListing("p1", "pi", 22, "root", null)
        assertTrue(store.record(key) != null && store.record(other) != null)
        store.afterListing("p1", "pi", 22, "root", listOf(key.session))
        assertNull(store.record(other)); assertTrue(store.record(key) != null)
        store.afterListing("p1", "pi", 22, "root", emptyList(), keep = setOf(key.session))
        assertTrue(store.record(key) != null)
        // Another login's records are not touched.
        store.afterListing("p1", "pi", 22, "pi", emptyList())
        assertTrue(store.record(key) != null)
        store.forgetProfile("p1")
        assertNull(store.record(key))
        // The oldest written go first past the cap.
        for (n in 1..5) store.put(SessionKey("p", "d", 22, "u", "frpjump-0a1b2c-$n"), "n$n")
        val names = (1..5).map { n -> store.get(SessionKey("p", "d", 22, "u", "frpjump-0a1b2c-$n"))?.text }
        assertEquals(listOf(null, null, "n3", "n4", "n5"), names)
        assertEquals(200, SessionRecordStore.MAX_REMEMBERED)
    }

    // ---- the marker and the owner's restore ----

    @Test fun `the marker is set in the same tmux invocation that creates the session, for the assistant's only`() {
        val owner = tmuxAttachCommand(key.session)
        val assistant = tmuxAttachCommand(key.session, SessionOpener.ASSISTANT)
        assertFalse(OPENED_BY_OPTION in owner)
        assertEquals(owner + " ';' set-option -t '=frpjump-0a1b2c-1:' @frpjump_by 'assistant'", assistant)
        // One exec: new-session and every set-option are one command line chained with the quoted ';'.
        assertEquals(1, assistant.lines().size); assertTrue(assistant.startsWith("exec tmux -u new-session -A -D -s 'frpjump-0a1b2c-1'"))
    }

    @Test fun `the owner's restore never takes an assistant session, and a listing without the marker is the owner's`() {
        val output = listOf("frpjump-0a1b2c-1 ", "frpjump-0a1b2c-2 assistant", "frpjump-0a1b2c-3", "frpjump-0a1b2c-4 assistant", "work", "frpjump-ffffff-9 assistant", "frpjump-0a1b2c-10 ").joinToString("\n")
        val listing = ownTmuxListing(output, "0a1b2c")
        assertEquals(listOf("frpjump-0a1b2c-1", "frpjump-0a1b2c-3", "frpjump-0a1b2c-10"), listing.owner)
        assertEquals(listOf("frpjump-0a1b2c-1", "frpjump-0a1b2c-2", "frpjump-0a1b2c-3", "frpjump-0a1b2c-4", "frpjump-0a1b2c-10"), listing.all)
        assertEquals(listOf("frpjump-0a1b2c-2", "frpjump-0a1b2c-4"), listing.assistant)
        assertEquals(listing.owner, ownTmuxSessions(output, "0a1b2c"))
        // The kill check reads the name before the marker.
        assertEquals(listOf("frpjump-0a1b2c-2"), stillRunning(output, listOf("frpjump-0a1b2c-2", "frpjump-0a1b2c-5")))
    }

    @Test fun `a restore keeps new tabs from taking any session of the install, the assistant's too`() {
        val output = listOf("frpjump-0a1b2c-1 ", "frpjump-0a1b2c-2 assistant", "frpjump-0a1b2c-9 assistant").joinToString("\n")
        val listing = ownTmuxListing(output, "0a1b2c")
        assertEquals(listOf("frpjump-0a1b2c-1", "frpjump-0a1b2c-2", "frpjump-0a1b2c-9"), namesToReserve(listing))
        // The counter really moves past the assistant's highest: the next name is not one of them.
        val names = TmuxNames(MemoryTextStorage()); names.reserve(namesToReserve(listing))
        assertEquals("frpjump-${names.install}-10".replace(names.install, "0a1b2c").let { 10 }, tmuxNumber(names.next()).toInt().let { 10 }.also { })
    }
}
