package ru.adsrv.frpjump.ui.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A held key on a fake clock: when it fires, and that it stops on release. */
class KeyRepeatTest {
    /** The times (ms since the press) at which a key held for [heldMs] fires. */
    private fun firesWhileHeld(heldMs: Long): List<Long> {
        var now = 0L
        val fired = mutableListOf<Long>()
        runBlocking {
            try {
                repeatWhileHeld(fire = { fired += now }) { ms ->
                    // Released while waiting: the coroutine is cancelled at its next suspension.
                    if (now + ms > heldMs) throw CancellationException("released")
                    now += ms
                }
            } catch (_: CancellationException) {
            }
        }
        return fired
    }

    @Test
    fun aTapFiresOnce() {
        assertEquals(listOf(0L), firesWhileHeld(100))
    }

    @Test
    fun noRepeatBeforeTheDelay() {
        assertEquals(listOf(0L), firesWhileHeld(KEY_REPEAT_DELAY_MS - 1))
    }

    @Test
    fun repeatsAfterTheDelayAtAboutFifteenASecond() {
        val fired = firesWhileHeld(KEY_REPEAT_DELAY_MS + 1000)
        assertEquals(listOf(0L, KEY_REPEAT_DELAY_MS, KEY_REPEAT_DELAY_MS + KEY_REPEAT_INTERVAL_MS), fired.take(3))
        // One second of repeat: 15 or 16 presses, never more.
        val inTheSecond = fired.count { it >= KEY_REPEAT_DELAY_MS }
        assertTrue("fired $inTheSecond times in a second", inTheSecond in 15..16)
    }

    @Test
    fun stopsOnRelease() {
        val held = KEY_REPEAT_DELAY_MS + 3 * KEY_REPEAT_INTERVAL_MS + 10
        val fired = firesWhileHeld(held)
        assertEquals(5, fired.size)
        assertTrue(fired.all { it <= held })
    }
}
