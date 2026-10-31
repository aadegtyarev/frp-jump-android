package ru.adsrv.frpjump.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoticeQueueTest {
    private fun n(id: Long, text: String = "n$id", key: String? = null) = TopNotice(id, text, key = key)

    @Test
    fun `the first posted is shown, the next waits until it goes`() {
        val q = NoticeQueue().post(n(1)).post(n(2))
        assertEquals(1L, q.shown?.id)
        assertEquals(2L, q.dismiss(1).shown?.id)
        assertNull(q.dismiss(1).dismiss(2).shown)
    }

    @Test
    fun `the same key replaces in place, the one shown too`() {
        val q = NoticeQueue().post(n(1, "copied a", key = "copied")).post(n(2)).post(n(3, "copied b", key = "copied"))
        assertEquals(listOf(3L, 2L), q.items.map { it.id })
        assertEquals("copied b", q.shown?.text)
    }

    @Test
    fun `the same text is not shown twice`() {
        val q = NoticeQueue().post(n(1, "No connection")).post(n(2, "No connection"))
        assertEquals(listOf(2L), q.items.map { it.id })
    }

    @Test
    fun `the oldest waiting gives way when too many wait, never the one shown`() {
        var q = NoticeQueue(limit = 2)
        (1L..5L).forEach { q = q.post(n(it)) }
        assertEquals(listOf(1L, 4L, 5L), q.items.map { it.id })
    }

    @Test
    fun `dismissing one not there changes nothing`() {
        val q = NoticeQueue().post(n(1))
        assertEquals(q, q.dismiss(9))
    }

    @Test
    fun `how long one stays`() {
        assertEquals(TopNotice.SHORT_MS, n(1).durationMs)
        assertEquals(TopNotice.LONG_MS, n(1).copy(long = true).durationMs)
        assertEquals(TopNotice.ACTION_MS, n(1).copy(action = NoticeAction("Open") {}).durationMs)
    }
}
