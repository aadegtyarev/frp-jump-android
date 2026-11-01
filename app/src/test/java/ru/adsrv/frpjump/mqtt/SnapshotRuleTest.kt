package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotRuleTest {
    @Test
    fun `ends one second after the last snapshot message, counted from the SUBACK`() {
        assertNull(SnapshotRule.end(subackAt = 0, lastSnapshotMessageAt = null, now = 999, limitReached = false))
        assertEquals(SnapshotEnd.QUIET, SnapshotRule.end(0, null, 1_000, false))
        assertNull(SnapshotRule.end(0, lastSnapshotMessageAt = 500, now = 1_499, limitReached = false))
        assertEquals(SnapshotEnd.QUIET, SnapshotRule.end(0, 500, 1_500, false))
        assertEquals(1_500L, SnapshotRule.nextCheckAt(0, 500))
    }

    @Test
    fun `ends ten seconds after the SUBACK while new topics keep coming`() {
        assertNull(SnapshotRule.end(0, lastSnapshotMessageAt = 9_900, now = 9_999, limitReached = false))
        assertEquals(SnapshotEnd.TIMED_OUT, SnapshotRule.end(0, 9_900, 10_000, false))
        assertEquals(10_000L, SnapshotRule.nextCheckAt(0, 9_900))
        // Quiet before the ten seconds were up, looked at late: still quiet.
        assertEquals(SnapshotEnd.QUIET, SnapshotRule.end(0, 8_000, 12_000, false))
    }

    @Test
    fun `ends at the topic limit at once`() {
        assertEquals(SnapshotEnd.LIMIT, SnapshotRule.end(0, 10, 20, limitReached = true))
    }
}
