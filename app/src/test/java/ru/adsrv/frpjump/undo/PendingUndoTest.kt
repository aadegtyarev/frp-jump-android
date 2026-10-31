package ru.adsrv.frpjump.undo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Undo instead of confirm: hidden at once, done when the notice goes, all back on Undo. */
class PendingUndoTest {
    /** The one timer running, fired by the test. */
    private var timer: (() -> Unit)? = null
    private var timerDelay = 0L
    private val done = mutableListOf<String>()
    private val restored = mutableListOf<String>()

    /** What the notice says now: the count, or null when hidden. */
    private var shown: Int? = null
    private var undoOnNotice: (() -> Unit)? = null

    private val pending = PendingUndo(
        timer = { ms, block ->
            timerDelay = ms
            timer = block
            val cancel: () -> Unit = { if (timer === block) timer = null }
            cancel
        },
        run = { commit -> runBlocking { commit() } },
        announce = { count, undo ->
            shown = count
            undoOnNotice = undo.takeIf { count != null }
        },
    )

    private fun delete(key: String) = pending.enqueue(key, commit = { done += key }, undo = { restored += key })

    private fun expire() = checkNotNull(timer) { "no timer running" }.invoke()

    @Test
    fun `a delete is hidden at once and nothing is done until the notice goes`() {
        delete("snippet:a")
        assertEquals(setOf("snippet:a"), pending.hidden.value)
        assertEquals(1, shown)
        assertEquals(PendingUndo.DELAY_MS, timerDelay)
        assertTrue(done.isEmpty())
    }

    @Test
    fun `Undo restores everything and does none of it`() {
        delete("snippet:a")
        delete("snippet:b")
        undoOnNotice!!.invoke()
        assertEquals(emptySet<String>(), pending.hidden.value)
        assertEquals(listOf("snippet:a", "snippet:b"), restored)
        assertNull(shown)
        assertNull(timer)
        assertTrue(done.isEmpty())
        // Nothing left for the shutdown either.
        pending.commitAll()
        assertTrue(done.isEmpty())
    }

    @Test
    fun `when the notice's time is up it is done and shown no more`() {
        delete("snippet:a")
        expire()
        assertEquals(listOf("snippet:a"), done)
        assertEquals(emptySet<String>(), pending.hidden.value)
        assertNull(shown)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `deletes in a row share one notice that counts them, its time starting again`() {
        delete("snippet:a")
        val first = timer
        delete("snippet:b")
        delete("snippet:c")
        assertEquals(3, shown)
        assertEquals(3, pending.count)
        assertTrue("the earlier timer was cancelled", first !== timer)
        expire()
        assertEquals(listOf("snippet:a", "snippet:b", "snippet:c"), done)
        // A new delete after that starts a new notice from 1.
        delete("snippet:d")
        assertEquals(1, shown)
    }

    @Test
    fun `the same key twice is one delete`() {
        delete("snippet:a")
        delete("snippet:a")
        assertEquals(1, shown)
        expire()
        assertEquals(listOf("snippet:a"), done)
    }

    @Test
    fun `the app going away does everything waiting at once`() {
        delete("connection:p1/1")
        delete("snippet:a")
        pending.commitAll()
        assertEquals(listOf("connection:p1/1", "snippet:a"), done)
        assertNull(timer)
        assertNull(shown)
        assertEquals(emptySet<String>(), pending.hidden.value)
    }

    @Test
    fun `different kinds wait together, each hidden where it shows, and go together`() {
        delete("connection:p1/1")
        delete("connection:p2/1")
        delete("snippet:a")
        assertEquals(3, shown)
        assertEquals(setOf("1"), PendingUndo.connectionIds(pending.hidden.value, "p1"))
        assertEquals(setOf("1"), PendingUndo.connectionIds(pending.hidden.value, "p2"))
        assertTrue(PendingUndo.snippetKey("a") in pending.hidden.value)
        expire()
        assertEquals(listOf("connection:p1/1", "connection:p2/1", "snippet:a"), done)
    }

    @Test
    fun `a key stays hidden while its commit runs and shows again if it fails`() {
        val running = mutableListOf<suspend () -> Unit>()
        val slow = PendingUndo(timer = { _, _ -> {} }, run = { running += it }, announce = { _, _ -> })
        slow.enqueue("snippet:a", commit = { error("disk full") })
        slow.commitAll()
        assertEquals(setOf("snippet:a"), slow.hidden.value)
        runCatching { runBlocking { running.single()() } }
        assertEquals(emptySet<String>(), slow.hidden.value)
    }
}
