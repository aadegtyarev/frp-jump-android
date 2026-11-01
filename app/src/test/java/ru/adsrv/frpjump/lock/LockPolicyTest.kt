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
}
