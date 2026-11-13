package ru.adsrv.frpjump.lock

import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AppLock]'s rules on a fake prompt host: no Android. */
class AppLockTest {
    private class FakeHost : PromptHost {
        var stateSaved = false
        var shown = 0
        var failShow: Exception? = null
        val listeners = mutableListOf<PromptListener>()
        override fun isStateSaved(owner: Any) = stateSaved
        override fun show(owner: Any, title: String, subtitle: String, listener: PromptListener) {
            shown++
            failShow?.let { throw it }
            listeners += listener
        }
    }

    private var now = 0L
    private val host = FakeHost()
    private val lock = AppLock(clock = { now }, host = host)
    private val owner = Any()
    private val results = mutableListOf<AuthResult>()

    private fun ask(who: Any = owner) = lock.authenticateFor(who, "t", "s") { results += it }

    /** A cold start with the setting on: locked, and the prompt is up. */
    private fun coldStartAsking() {
        lock.onStart(enabled = true)
        assertTrue(lock.locked.value)
        ask()
        assertEquals(1, host.listeners.size)
    }

    @Test
    fun `success unlocks and reports Success`() {
        coldStartAsking()
        host.listeners[0].succeeded()
        assertFalse(lock.locked.value)
        assertEquals(listOf<AuthResult>(AuthResult.Success), results)
    }

    @Test
    fun `cancel codes are Cancelled, others Failed with the message`() {
        lock.onStart(enabled = true)
        for (code in listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED)) {
            ask()
            host.listeners.last().error(code, "x")
            assertEquals(AuthResult.Cancelled, results.last())
        }
        ask()
        host.listeners.last().error(BiometricPrompt.ERROR_LOCKOUT, "Too many attempts")
        assertEquals(AuthResult.Failed("Too many attempts"), results.last())
        assertTrue(lock.locked.value)
    }

    @Test
    fun `owner destroyed with the prompt up abandons it, stays locked and a new owner asks again`() {
        coldStartAsking()
        now = 100
        lock.onDestroy(owner)
        assertTrue(lock.locked.value)
        // The new activity: its start does not think a prompt is still up.
        val next = Any()
        lock.onStart(enabled = true)
        assertTrue(lock.locked.value)
        lock.authenticateFor(next, "t", "s") { results += it }
        assertEquals(2, host.listeners.size)
        host.listeners[1].succeeded()
        assertFalse(lock.locked.value)
        assertEquals(listOf<AuthResult>(AuthResult.Success), results)
    }

    @Test
    fun `abandoning does not report a result and does not unlock`() {
        coldStartAsking()
        lock.onDestroy(owner)
        assertTrue(results.isEmpty())
        assertTrue(lock.locked.value)
    }

    @Test
    fun `a late answer to an abandoned prompt does nothing`() {
        coldStartAsking()
        lock.onDestroy(owner)
        host.listeners[0].succeeded()
        assertTrue(lock.locked.value)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `a late error of an abandoned prompt does not end the next prompt`() {
        coldStartAsking()
        lock.onDestroy(owner)
        val next = Any()
        lock.authenticateFor(next, "t", "s") { results += it }
        host.listeners[0].error(BiometricPrompt.ERROR_CANCELED, "x")
        assertTrue(results.isEmpty())
        host.listeners[1].succeeded()
        assertEquals(listOf<AuthResult>(AuthResult.Success), results)
    }

    @Test
    fun `a stale callback of a replaced prompt does nothing`() {
        lock.onStart(enabled = true)
        ask()
        ask()
        // The replaced one was ended as Cancelled; its own late answer does nothing.
        assertEquals(listOf<AuthResult>(AuthResult.Cancelled), results)
        host.listeners[0].succeeded()
        assertTrue(lock.locked.value)
        assertEquals(listOf<AuthResult>(AuthResult.Cancelled), results)
        host.listeners[1].succeeded()
        assertFalse(lock.locked.value)
    }

    @Test
    fun `the answer comes once`() {
        coldStartAsking()
        host.listeners[0].succeeded()
        host.listeners[0].error(BiometricPrompt.ERROR_CANCELED, "x")
        assertEquals(1, results.size)
    }

    @Test
    fun `another activity's destroy leaves the prompt alone`() {
        coldStartAsking()
        lock.onDestroy(Any())
        host.listeners[0].succeeded()
        assertEquals(listOf<AuthResult>(AuthResult.Success), results)
    }

    @Test
    fun `a destroy without a prompt does nothing`() {
        lock.onStart(enabled = true)
        lock.onDestroy(owner)
        assertTrue(lock.locked.value)
    }

    @Test
    fun `state saved gives Cancelled at once without showing, and stays locked`() {
        lock.onStart(enabled = true)
        host.stateSaved = true
        ask()
        assertEquals(listOf<AuthResult>(AuthResult.Cancelled), results)
        assertEquals(0, host.shown)
        assertTrue(lock.locked.value)
        // Not "authenticating": the next start still decides by itself and a later prompt works.
        host.stateSaved = false
        ask()
        host.listeners[0].succeeded()
        assertFalse(lock.locked.value)
    }

    @Test
    fun `a failure to show ends the prompt with Failed`() {
        lock.onStart(enabled = true)
        host.failShow = IllegalStateException("saved")
        ask()
        assertEquals(listOf<AuthResult>(AuthResult.Failed("saved")), results)
        assertTrue(lock.locked.value)
    }

    @Test
    fun `immediately locks after any absence, a handoff waits 30 s`() {
        lock.onStart(enabled = true, afterMs = 0)
        ask()
        host.listeners[0].succeeded()
        lock.onStop()
        now = 10
        lock.onStart(enabled = true, afterMs = 0)
        assertTrue(lock.locked.value)
        lock.unlock()
        lock.handoff()
        lock.onStop()
        now = 20_000
        lock.onStart(enabled = true, afterMs = 0)
        assertFalse(lock.locked.value)
    }

    @Test
    fun `a prompt that replaces an open one ends the old one as Cancelled`() {
        lock.onStart(enabled = true)
        val first = mutableListOf<AuthResult>()
        lock.authenticateFor(owner, "t", "s") { first += it }
        lock.authenticateFor(Any(), "t", "s") { results += it }
        assertEquals(listOf<AuthResult>(AuthResult.Cancelled), first)
        assertTrue(results.isEmpty())
        assertTrue(lock.locked.value)
        host.listeners[1].succeeded()
        assertFalse(lock.locked.value)
        assertEquals(listOf<AuthResult>(AuthResult.Success), results)
        assertEquals(1, first.size)
    }

    @Test
    fun `abandoning with the screen out of sight counts the absence from then`() {
        // Abandon must go through the policy: with the setting off first, nothing is locked yet.
        lock.onStart(enabled = false)
        ask()
        lock.onStop()
        now = 1_000
        lock.onDestroy(owner)
        // A new activity after more than the interval (Immediately): asks, as the absence began at the abandon.
        now = 2_000
        lock.onStart(enabled = true, afterMs = 0)
        assertTrue(lock.locked.value)
        // Without the abandon the policy still thinks a prompt is up, and the start would return "unlocked".
    }

    // Held launches: nothing the app opens may appear over the lock screen.

    private var ran = 0
    private fun hold(action: String? = "android.intent.action.VIEW", who: Any = owner) = lock.holdLaunch(who, action) { ran++ }
    private val credential = "android.app.action.CONFIRM_DEVICE_CREDENTIAL"

    @Test
    fun `a launch is held while locked and runs once after a successful unlock`() {
        coldStartAsking()
        assertTrue(hold())
        assertEquals(0, ran)
        host.listeners[0].succeeded()
        assertEquals(1, ran)
        // Not again: a later success (turning the setting on, say) has nothing held, with no stop in between.
        ask()
        host.listeners.last().succeeded()
        assertEquals(1, ran)
    }

    @Test
    fun `a launch is not held while unlocked`() {
        lock.onStart(enabled = true)
        lock.unlock()
        assertFalse(hold())
        assertEquals(0, ran)
    }

    @Test
    fun `only the latest held launch runs`() {
        coldStartAsking()
        val order = mutableListOf<String>()
        assertTrue(lock.holdLaunch(owner, "a") { order += "first" })
        assertTrue(lock.holdLaunch(owner, "b") { order += "second" })
        host.listeners[0].succeeded()
        assertEquals(listOf("second"), order)
    }

    @Test
    fun `a failed or cancelled prompt runs nothing, the next success does`() {
        coldStartAsking()
        hold()
        host.listeners[0].error(BiometricPrompt.ERROR_USER_CANCELED, "x")
        assertEquals(0, ran)
        ask()
        host.listeners[1].succeeded()
        assertEquals(1, ran)
    }

    @Test
    fun `a stop while locked drops the held launch`() {
        lock.onStart(enabled = true)
        assertTrue(hold())
        lock.onStop()
        lock.onStart(enabled = true)
        ask()
        host.listeners[0].succeeded()
        assertEquals(0, ran)
    }

    @Test
    fun `the prompt's own stop keeps the held launch`() {
        coldStartAsking()
        assertTrue(hold())
        lock.onStop()
        lock.onStart(enabled = true)
        host.listeners[0].succeeded()
        assertEquals(1, ran)
    }

    @Test
    fun `destroy of its owner drops the held launch, another owner's destroy does not`() {
        coldStartAsking()
        assertTrue(hold())
        lock.onDestroy(Any())
        host.listeners[0].succeeded()
        assertEquals(1, ran)
        ran = 0
        lock.onStart(enabled = true, afterMs = 0)
        lock.onStop()
        lock.onStart(enabled = true, afterMs = 0)
        assertTrue(lock.locked.value)
        // The prompt belongs to another owner, so only the held launch (of [owner]) goes.
        ask(Any())
        assertTrue(hold())
        lock.onDestroy(owner)
        host.listeners.last().succeeded()
        assertEquals(0, ran)
    }

    @Test
    fun `no screen lock any more drops the held launch`() {
        lock.onStart(enabled = true)
        assertTrue(hold())
        lock.unlock()
        ask()
        host.listeners[0].succeeded()
        assertEquals(0, ran)
    }

    @Test
    fun `the prompt's own credential screen is never held, an app launch with that action is when no prompt is open`() {
        coldStartAsking()
        assertFalse(hold(credential))
        assertFalse(hold("android.app.action.CONFIRM_DEVICE_CREDENTIAL_WITH_USER"))
        // Any other launch while the prompt is open is held.
        assertTrue(hold("android.intent.action.VIEW"))
        assertTrue(hold(null))
        host.listeners[0].error(BiometricPrompt.ERROR_USER_CANCELED, "x")
        // No prompt open: the action alone is not enough.
        assertTrue(hold(credential))
    }

    @Test
    fun `a held launch that throws at the unlock does not break the unlock`() {
        coldStartAsking()
        lock.holdLaunch(owner, "a") { throw IllegalStateException("no activity") }
        host.listeners[0].succeeded()
        assertFalse(lock.locked.value)
        assertEquals(listOf<AuthResult>(AuthResult.Success), results)
    }
}
