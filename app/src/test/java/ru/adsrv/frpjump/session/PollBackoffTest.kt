package ru.adsrv.frpjump.session

import org.junit.Assert.assertEquals
import org.junit.Test

class PollBackoffTest {
    @Test
    fun backsOffThenResetsOnSuccess() {
        val b = PollBackoff()
        assertEquals(2_000L, b.next(succeeded = true, intervalMs = 2_000))
        assertEquals(10_000L, b.next(succeeded = false, intervalMs = 2_000))
        assertEquals(30_000L, b.next(succeeded = false, intervalMs = 2_000))
        assertEquals(30_000L, b.next(succeeded = false, intervalMs = 2_000))
        assertEquals(2_000L, b.next(succeeded = true, intervalMs = 2_000))
        assertEquals(10_000L, b.next(succeeded = false, intervalMs = 2_000))
    }
}
