package ru.adsrv.frpjump.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyTest {
    private val policy = LockPolicy(timeoutMs = 30_000)

    /** Started and unlocked at [at]. */
    private fun unlockedAt(at: Long) {
        assertTrue(policy.onStart(at, enabled = true))
        policy.authStarted()
        policy.authFinished(success = true, now = at)
        assertFalse(policy.locked)
    }

    @Test
    fun `off never locks`() {
        assertFalse(policy.onStart(0, enabled = false))
        policy.onStop(1_000)
        assertFalse(policy.onStart(100_000, enabled = false))
    }

    @Test
    fun `a cold start locks`() {
        assertTrue(policy.onStart(0, enabled = true))
    }

    @Test
    fun `back within 30 s stays unlocked, after more than 30 s locks`() {
        unlockedAt(0)
        policy.onStop(1_000)
        assertFalse(policy.onStart(31_000, enabled = true))
        policy.onStop(40_000)
        assertTrue(policy.onStart(70_001, enabled = true))
    }

    @Test
    fun `cancelled stays locked, a retry that succeeds unlocks`() {
        policy.onStart(0, enabled = true)
        policy.authStarted()
        policy.authFinished(success = false, now = 100)
        assertTrue(policy.locked)
        policy.authStarted()
        policy.authFinished(success = true, now = 200)
        assertFalse(policy.locked)
    }

    @Test
    fun `stops and starts while the prompt is up are not leaving`() {
        unlockedAt(0)
        policy.onStop(1_000)
        assertTrue(policy.onStart(100_000, enabled = true))
        policy.authStarted()
        // The screen lock opens as an activity of its own and takes a while.
        policy.onStop(100_500)
        policy.onStart(200_000, enabled = true)
        policy.authFinished(success = true, now = 200_050)
        assertFalse(policy.locked)
        assertFalse(policy.onStart(200_100, enabled = true))
    }

    @Test
    fun `time spent away while locked does not count after unlocking`() {
        policy.onStart(0, enabled = true)
        policy.onStop(1_000)
        assertTrue(policy.onStart(500_000, enabled = true))
        policy.authStarted()
        policy.authFinished(success = true, now = 500_050)
        policy.onStop(500_100)
        assertFalse(policy.onStart(510_000, enabled = true))
    }

    @Test
    fun `turning it on after authenticating does not lock until the next absence`() {
        assertFalse(policy.onStart(0, enabled = false))
        policy.authStarted()
        policy.authFinished(success = true, now = 500)
        policy.onStop(1_000)
        assertFalse(policy.onStart(2_000, enabled = true))
        policy.onStop(3_000)
        assertTrue(policy.onStart(40_000, enabled = true))
    }

    @Test
    fun `turning it off unlocks at once`() {
        assertTrue(policy.onStart(0, enabled = true))
        assertFalse(policy.onStart(10, enabled = false))
        assertEquals(false, policy.locked)
    }

    @Test
    fun `a prompt that ends while the app is away counts the absence from then`() {
        // Turning it on: the owner leaves while the prompt is up and it succeeds out of sight.
        assertFalse(policy.onStart(0, enabled = false))
        policy.authStarted()
        policy.onStop(1_000)
        policy.authFinished(success = true, now = 2_000)
        assertFalse(policy.onStart(20_000, enabled = true))
        policy.authStarted()
        policy.onStop(21_500)
        policy.authFinished(success = true, now = 22_000)
        assertTrue(policy.onStart(52_001, enabled = true))
    }

    @Test
    fun `a prompt cancelled while the app is away still lets the 30 s rule apply`() {
        unlockedAt(0)
        policy.onStop(1_000)
        assertFalse(policy.onStart(2_000, enabled = true))
        // A prompt over an unlocked screen (turning the lock on again), cancelled after the owner left.
        policy.authStarted()
        policy.onStop(3_000)
        policy.authFinished(success = false, now = 4_000)
        assertFalse(policy.onStart(20_000, enabled = true))
        policy.authStarted()
        policy.onStop(21_000)
        policy.authFinished(success = false, now = 21_000)
        assertTrue(policy.onStart(51_001, enabled = true))
    }

    // Settings › Lock after.

    @Test
    fun `each interval locks only after more than that long`() {
        for ((after, limit) in listOf(30_000L to 30_000L, 60_000L to 60_000L, 300_000L to 300_000L, 900_000L to 900_000L)) {
            val p = LockPolicy()
            p.onStart(0, enabled = true, afterMs = after)
            p.authStarted()
            p.authFinished(success = true, now = 0)
            p.onStop(1_000)
            assertFalse("$after at the limit", p.onStart(1_000 + limit, enabled = true, afterMs = after))
            p.onStop(500_000)
            assertTrue("$after past the limit", p.onStart(500_000 + limit + 1, enabled = true, afterMs = after))
        }
    }

    @Test
    fun `immediately locks on any absence, even a very short one`() {
        unlockedAt(0)
        assertFalse(policy.onStart(10, enabled = true, afterMs = 0))
        policy.onStop(20)
        assertTrue(policy.onStart(20, enabled = true, afterMs = 0))
    }

    @Test
    fun `the interval is read at each start`() {
        unlockedAt(0)
        policy.onStop(1_000)
        assertFalse(policy.onStart(61_000, enabled = true, afterMs = 300_000))
        policy.onStop(62_000)
        assertTrue(policy.onStart(100_000, enabled = true, afterMs = 5_000))
    }

    @Test
    fun `immediately still ignores the prompt's own stops and starts`() {
        assertTrue(policy.onStart(0, enabled = true, afterMs = 0))
        policy.authStarted()
        policy.onStop(100)
        assertTrue(policy.onStart(5_000, enabled = true, afterMs = 0))
        policy.authFinished(success = true, now = 5_100)
        assertFalse(policy.locked)
    }

    @Test
    fun `immediately does not lock when the screen never went out of sight`() {
        unlockedAt(0)
        assertFalse(policy.onStart(100_000, enabled = true, afterMs = 0))
    }

    @Test
    fun `a window the app opened counts as an absence only after 30 s with immediately`() {
        unlockedAt(0)
        policy.handoff(1_000)
        policy.onStop(1_500)
        assertFalse(policy.onStart(31_500, enabled = true, afterMs = 0))
        policy.handoff(40_000)
        policy.onStop(40_100)
        assertTrue(policy.onStart(70_101, enabled = true, afterMs = 0))
    }

    @Test
    fun `after a handoff a longer interval is kept`() {
        unlockedAt(0)
        policy.handoff(1_000)
        policy.onStop(1_100)
        assertFalse(policy.onStart(200_000, enabled = true, afterMs = 300_000))
        policy.handoff(201_000)
        policy.onStop(201_100)
        assertTrue(policy.onStart(201_100 + 300_001, enabled = true, afterMs = 300_000))
    }

    @Test
    fun `a stale handoff mark does not count after its window`() {
        unlockedAt(0)
        policy.handoff(1_000)
        // The window never opened; the screen goes out of sight for another reason.
        policy.onStop(1_000 + LockPolicy.HANDOFF_WINDOW_MS + 1)
        assertTrue(policy.onStart(1_000 + LockPolicy.HANDOFF_WINDOW_MS + 11, enabled = true, afterMs = 0))
    }

    @Test
    fun `a handoff mark within its window still counts at its edge`() {
        unlockedAt(0)
        policy.handoff(1_000)
        policy.onStop(1_000 + LockPolicy.HANDOFF_WINDOW_MS)
        assertFalse(policy.onStart(1_000 + LockPolicy.HANDOFF_WINDOW_MS + 10, enabled = true, afterMs = 0))
    }

    @Test
    fun `a handoff mark is used once`() {
        unlockedAt(0)
        policy.handoff(1_000)
        policy.onStop(1_100)
        assertFalse(policy.onStart(2_000, enabled = true, afterMs = 0))
        policy.onStop(3_000)
        assertTrue(policy.onStart(3_010, enabled = true, afterMs = 0))
    }

    @Test
    fun `a handoff while locked changes nothing`() {
        assertTrue(policy.onStart(0, enabled = true, afterMs = 0))
        policy.handoff(10)
        policy.onStop(20)
        assertTrue(policy.onStart(30, enabled = true, afterMs = 0))
    }

    // An abandoned prompt.

    @Test
    fun `an abandoned prompt leaves a locked app locked and the next start asks again`() {
        assertTrue(policy.onStart(0, enabled = true))
        policy.authStarted()
        policy.onStop(100)
        policy.authAbandoned(now = 150)
        assertTrue(policy.locked)
        assertTrue(policy.onStart(200, enabled = true))
        // Not "authenticating" any more: the stops and starts are counted again once unlocked.
        policy.authStarted()
        policy.authFinished(success = true, now = 300)
        assertFalse(policy.locked)
    }

    @Test
    fun `an abandoned prompt does not unlock, also when started after an unlocked state`() {
        unlockedAt(0)
        policy.onStop(1_000)
        assertTrue(policy.onStart(100_000, enabled = true))
        policy.authStarted()
        policy.authAbandoned(now = 100_100)
        assertTrue(policy.locked)
    }

    @Test
    fun `an abandoned prompt of an unlocked app counts the absence from then`() {
        // A prompt over an unlocked app (turning the setting on): its owner is destroyed out of sight.
        unlockedAt(0)
        policy.authStarted()
        policy.onStop(1_000)
        policy.authAbandoned(now = 2_000)
        assertFalse(policy.locked)
        assertFalse(policy.onStart(32_000, enabled = true))
        policy.onStop(33_000)
        assertTrue(policy.onStart(63_001, enabled = true))
    }
}
