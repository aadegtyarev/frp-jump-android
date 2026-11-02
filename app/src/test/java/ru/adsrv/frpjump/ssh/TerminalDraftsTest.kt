package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalDraftsTest {
    private val drafts = TerminalDrafts().apply { open(1); open(2) }
    private fun value(id: Int = 1) = drafts.state.value.getValue(id)

    @Test fun `keys is the initial mode and tabs have separate drafts`() {
        assertFalse(value().textMode)
        drafts.edit(1, "first", 2, 4)
        drafts.edit(2, "second", 6, 6)
        assertEquals("first", value().text)
        assertEquals(2, value().selectionStart)
        assertEquals(4, value().selectionEnd)
        assertEquals("second", value(2).text)
    }

    @Test fun `mode switches and expand collapse keep text and selection`() {
        drafts.edit(1, "draft", 4, 1)
        drafts.textMode(1, true)
        drafts.expand(1, true)
        assertTrue(value().expanded)
        drafts.expand(1, false)
        assertTrue(value().textMode)
        drafts.textMode(1, false)
        assertFalse(value().expanded)
        assertEquals("draft", value().text)
        assertEquals(4, value().selectionStart)
        assertEquals(1, value().selectionEnd)
    }

    @Test fun `closing releases only that draft and stale edits cannot recreate it`() {
        drafts.edit(1, "secret", 6, 6)
        drafts.edit(2, "keep", 4, 4)
        drafts.close(1)
        drafts.edit(1, "late IME callback", 0, 0)
        drafts.textMode(1, true)
        assertNull(drafts.submission(1))
        assertEquals("keep", value(2).text)
    }

    @Test fun `submission captures its text and exact destination`() {
        drafts.edit(1, "hello\nworld\n", 12, 12)
        val sent = drafts.submission(1)!!
        drafts.edit(2, "other", 5, 5)
        assertEquals(1, sent.sessionId)
        assertEquals("hello\nworld\n", sent.text)
        drafts.inserted(sent)
        assertEquals("", value().text)
        assertEquals(0, value().selectionStart)
        assertEquals("other", value(2).text)
    }

    @Test fun `a canceled or failed submission does not mutate the draft`() {
        drafts.edit(1, "keep me", 3, 3)
        val before = value()
        drafts.submission(1)
        assertEquals(before, value())
    }

    @Test fun `sending a captured version does not clear a newer edit`() {
        drafts.edit(1, "old", 3, 3)
        val sent = drafts.submission(1)!!
        drafts.edit(1, "new", 3, 3)
        drafts.inserted(sent)
        assertEquals("new", value().text)
    }

    @Test fun `even editing away and back prevents clearing newer intent`() {
        drafts.edit(1, "same", 4, 4)
        val sent = drafts.submission(1)!!
        drafts.edit(1, "different", 9, 9)
        drafts.edit(1, "same", 4, 4)
        drafts.inserted(sent)
        assertEquals("same", value().text)
    }

    @Test fun `selection only edits do not block clearing submitted text`() {
        drafts.edit(1, "text", 4, 4)
        val sent = drafts.submission(1)!!
        drafts.edit(1, "text", 1, 2)
        drafts.inserted(sent)
        assertEquals("", value().text)
    }

    @Test fun `late success does not clear a reopened draft or different tab`() {
        drafts.edit(1, "old", 3, 3)
        val sent = drafts.submission(1)!!
        drafts.close(1)
        drafts.open(1)
        drafts.edit(1, "replacement", 11, 11)
        drafts.inserted(sent)
        assertEquals("replacement", value().text)
        assertEquals("", value(2).text)
    }

    @Test fun `outgoing compact editor cannot collapse selection in expanded editor`() {
        drafts.textMode(1, true)
        drafts.edit(1, "selection", 2, 5)
        drafts.expand(1, true)
        assertFalse(drafts.editFromSurface(1, "selection", 5, 5, surfaceExpanded = false, selectedId = 1))
        assertEquals(2, value().selectionStart)
        assertEquals(5, value().selectionEnd)
        assertTrue(drafts.editFromSurface(1, "selection!", 10, 10, surfaceExpanded = true, selectedId = 1))
        assertEquals("selection!", value().text)
    }

    @Test fun `callbacks from previously selected tab are ignored`() {
        drafts.textMode(1, true)
        drafts.edit(1, "old tab", 2, 4)
        assertFalse(drafts.editFromSurface(1, "late callback", 0, 0, surfaceExpanded = false, selectedId = 2))
        assertEquals("old tab", value().text)
        assertEquals(2, value().selectionStart)
    }

    @Test fun `leaving text mode invalidates editor callbacks`() {
        drafts.textMode(1, true)
        drafts.edit(1, "keep", 1, 3)
        drafts.textMode(1, false)
        assertFalse(drafts.editFromSurface(1, "keep", 3, 3, surfaceExpanded = false, selectedId = 1))
        assertEquals(1, value().selectionStart)
        assertEquals(3, value().selectionEnd)
    }

    @Test fun `selection is clamped to text and reopening an existing tab is harmless`() {
        drafts.edit(1, "abc", -4, 99)
        drafts.open(1)
        assertEquals("abc", value().text)
        assertEquals(0, value().selectionStart)
        assertEquals(3, value().selectionEnd)
    }
}
