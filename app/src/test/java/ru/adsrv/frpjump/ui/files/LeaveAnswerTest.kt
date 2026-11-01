package ru.adsrv.frpjump.ui.files

import org.junit.Assert.assertEquals
import org.junit.Test

class LeaveAnswerTest {
    @Test
    fun `the question stays while transfers go`() {
        assertEquals(LeaveAnswer.ASK, leaveAnswer(running = 2, failedAtAsk = 0, failedNow = 1))
    }

    @Test
    fun `all finished well while asked leaves as asked`() {
        assertEquals(LeaveAnswer.LEAVE, leaveAnswer(running = 0, failedAtAsk = 0, failedNow = 0))
        // Failures from before the question do not hold the screen.
        assertEquals(LeaveAnswer.LEAVE, leaveAnswer(running = 0, failedAtAsk = 1, failedNow = 1))
    }

    @Test
    fun `a transfer that failed while asked keeps the screen so the failure is seen`() {
        assertEquals(LeaveAnswer.STAY, leaveAnswer(running = 0, failedAtAsk = 1, failedNow = 2))
    }
}
