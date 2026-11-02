package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class PasteAttemptTest {
    @Test fun `explicit execution requires the same selection token and destination`() {
        val selection = Any()
        assertEquals(true, pasteExecutionSelected(selection, selection, 1, 1))
        assertEquals(false, pasteExecutionSelected(selection, selection, 1, 2))
        assertEquals(false, pasteExecutionSelected(null, selection, 1, 1))
    }

    @Test fun `switching away and back cannot revive an old execute confirmation`() {
        val captured = Pair("tab", 1)
        val reselected = Pair("tab", 1)
        assertEquals(captured, reselected)
        var writes = 0
        val drafts = TerminalDrafts().apply { open(1); textMode(1, true); edit(1, "keep", 4, 4) }
        val before = drafts.state.value
        val result = attemptPaste({ pasteExecutionSelected(captured, reselected, 1, 1) }) { writes++; true }
        assertEquals(PasteAttempt.UNAVAILABLE, result)
        assertEquals(0, writes)
        assertEquals(before, drafts.state.value)
    }

    @Test fun `changed selection does not retarget a captured paste`() {
        val captured = Any()
        val otherTab = Any()
        val live = mapOf(1 to captured, 2 to otherTab)
        val nowSelected = 2
        val writes = mutableListOf<Any>()
        val result = attemptPaste(
            available = { live[1] === captured },
            paste = { writes += captured; true },
        )
        assertEquals(PasteAttempt.INSERTED, result)
        assertEquals(listOf(captured), writes)
        assertEquals(otherTab, live[nowSelected])
    }

    @Test fun `unavailable destination does not dispatch`() {
        var calls = 0
        assertEquals(PasteAttempt.UNAVAILABLE, attemptPaste({ false }) { calls++; true })
        assertEquals(0, calls)
    }

    @Test fun `a writer declining after revalidation does not clear the draft`() {
        val drafts = TerminalDrafts().apply { open(1); edit(1, "keep", 4, 4) }
        val submission = drafts.submission(1)!!
        val result = attemptPaste({ true }) { false }
        if (result == PasteAttempt.INSERTED) drafts.inserted(submission)
        assertEquals(PasteAttempt.UNAVAILABLE, result)
        assertEquals("keep", drafts.state.value.getValue(1).text)
    }

    @Test fun `an exception is not retried or reported as inserted`() {
        var calls = 0
        assertEquals(PasteAttempt.FAILED, attemptPaste({ true }) { calls++; throw IllegalStateException() })
        assertEquals(1, calls)
    }

    @Test fun `only accepted local insertion clears the captured revision`() {
        val drafts = TerminalDrafts().apply { open(1); edit(1, "old", 3, 3) }
        val submission = drafts.submission(1)!!
        val result = attemptPaste({ true }) {
            drafts.edit(1, "newer", 5, 5)
            true
        }
        if (result == PasteAttempt.INSERTED) drafts.inserted(submission)
        assertEquals(PasteAttempt.INSERTED, result)
        assertEquals("newer", drafts.state.value.getValue(1).text)
    }
}
