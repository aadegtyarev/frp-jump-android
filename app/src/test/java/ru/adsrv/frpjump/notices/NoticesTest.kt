package ru.adsrv.frpjump.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a message goes: the top of the app, a toast while no screen is up, nowhere while locked. */
class NoticesTest {
    private var foreground = true
    private var locked = false
    private val toasts = mutableListOf<Pair<String, Boolean>>()
    private val notices = Notices({ foreground }, { locked }, { text, long -> toasts += text to long })

    @Test
    fun `in the foreground it waits to be shown at the top`() {
        notices.show("Copied", NoticeKind.DONE)
        assertEquals("Copied", notices.queue.value.shown?.text)
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun `with no screen up it is a toast`() {
        foreground = false
        notices.show("No network to keep it on", long = true)
        assertNull(notices.queue.value.shown)
        assertEquals(listOf("No network to keep it on" to true), toasts)
    }

    @Test
    fun `while locked it is dropped, and locking clears what waits`() {
        notices.show("before")
        locked = true
        notices.show("Copied secret")
        assertTrue(toasts.isEmpty())
        notices.clear()
        assertNull(notices.queue.value.shown)
    }

    @Test
    fun `going to the background turns what was not seen into toasts`() {
        notices.show("one")
        notices.show("two", long = true)
        notices.toBackground()
        assertNull(notices.queue.value.shown)
        assertEquals(listOf("one" to false, "two" to true), toasts)
    }

    @Test
    fun `the innermost host shows it`() {
        val screen = Any()
        val sheet = Any()
        notices.attach(screen)
        notices.attach(sheet)
        assertEquals(sheet, notices.hosts.value.last())
        notices.detach(sheet)
        assertEquals(screen, notices.hosts.value.last())
    }
}
