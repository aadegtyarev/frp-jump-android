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
    private fun hold(
        action: String? = "android.intent.action.VIEW",
        who: Any = owner,
        forResult: Boolean = true,
        enabled: Boolean = true,
        afterMs: Long = 30_000L,
        onDrop: (() -> Unit)? = null,
    ) = lock.holdLaunch(who, action, forResult, enabled, afterMs, onDrop) { ran++ }
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
        assertTrue(lock.holdLaunch(owner, "a", true, true, 30_000L) { order += "first" })
        assertTrue(lock.holdLaunch(owner, "b", true, true, 30_000L) { order += "second" })
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
        lock.holdLaunch(owner, "a", true, true, 30_000L) { throw IllegalStateException("no activity") }
        host.listeners[0].succeeded()
        assertFalse(lock.locked.value)
        assertEquals(listOf<AuthResult>(AuthResult.Success), results)
    }

    // B1: the owner leaves while the prompt is up.

    @Test
    fun `a launch held before the owner left with the prompt up is dropped when the prompt is cancelled out of sight`() {
        coldStartAsking()
        assertTrue(hold())
        lock.onStop()
        host.listeners[0].error(BiometricPrompt.ERROR_CANCELED, "x")
        lock.onStart(enabled = true)
        ask()
        host.listeners[1].succeeded()
        assertEquals(0, ran)
    }

    @Test
    fun `a launch held before the owner left with the prompt up is dropped when it succeeds out of sight`() {
        coldStartAsking()
        assertTrue(hold())
        lock.onStop()
        host.listeners[0].succeeded()
        assertEquals(0, ran)
        assertFalse(lock.locked.value)
    }

    // N3: the lock never goes false with a launch still held, other than by the prompt's success.

    @Test
    fun `the setting going off drops the held launch`() {
        lock.onStart(enabled = true)
        assertTrue(hold())
        lock.onStart(enabled = false)
        assertFalse(lock.locked.value)
        lock.onStart(enabled = true)
        ask()
        host.listeners[0].succeeded()
        assertEquals(0, ran)
    }

    // N1: a dropped launch tells its owner.

    @Test
    fun `a dropped launch calls onDrop, one that runs does not`() {
        var drops = 0
        coldStartAsking()
        assertTrue(hold(onDrop = { drops++ }))
        assertTrue(hold(onDrop = { drops += 10 }))
        // The replaced one is dropped at once.
        assertEquals(1, drops)
        lock.onDestroy(owner)
        assertEquals(11, drops)
        // One that runs: no drop.
        drops = 0
        lock.onStart(enabled = true)
        ask()
        assertTrue(hold(onDrop = { drops++ }))
        host.listeners.last().succeeded()
        assertEquals(1, ran)
        assertEquals(0, drops)
    }

    @Test
    fun `unlock and a stop call onDrop of the held launch`() {
        var drops = 0
        lock.onStart(enabled = true)
        assertTrue(hold(onDrop = { drops++ }))
        lock.unlock()
        assertEquals(1, drops)
        lock.onStart(enabled = true, afterMs = 0)
        lock.onStop()
        lock.onStart(enabled = true, afterMs = 0)
        assertTrue(hold(onDrop = { drops++ }))
        lock.onStop()
        assertEquals(2, drops)
    }

    @Test
    fun `a failing onDrop does not break the drop`() {
        lock.onStart(enabled = true)
        assertTrue(hold(onDrop = { throw IllegalStateException("registry gone") }))
        lock.unlock()
        assertFalse(lock.locked.value)
    }

    // N2: only RuntimeException is contained.

    @Test
    fun `an Error from a held launch is not swallowed`() {
        coldStartAsking()
        lock.holdLaunch(owner, "a", true, true, 30_000L) { throw StackOverflowError() }
        org.junit.Assert.assertThrows(StackOverflowError::class.java) { host.listeners[0].succeeded() }
    }

    // S1: the exemption is for the result path only.

    @Test
    fun `the credential screen is exempt on the result path only`() {
        coldStartAsking()
        assertFalse(hold(credential, forResult = true))
        assertTrue(hold(credential, forResult = false))
    }

    // R21: out of sight, where the lock would engage on return.

    /** Unlocked at 0, out of sight from 1_000. */
    private fun unlockedAndAway() {
        lock.onStart(enabled = true)
        ask()
        host.listeners[0].succeeded()
        now = 1_000
        lock.onStop()
    }

    @Test
    fun `out of sight before the interval has elapsed a launch is not held`() {
        unlockedAndAway()
        now = 31_000
        assertFalse(hold(afterMs = 30_000L))
    }

    @Test
    fun `out of sight after the interval a launch is held, and runs after the next unlock`() {
        unlockedAndAway()
        now = 31_001
        assertTrue(hold(afterMs = 30_000L))
        assertEquals(0, ran)
        now = 32_000
        lock.onStart(enabled = true, afterMs = 30_000L)
        assertTrue(lock.locked.value)
        ask()
        host.listeners[1].succeeded()
        assertEquals(1, ran)
    }

    @Test
    fun `in sight and unlocked a launch is not held, even if the lock was never engaged in this process`() {
        // The setting was off at the start (so never unlocked here); it is on now, the screen is in sight.
        lock.onStart(enabled = false)
        assertFalse(lock.locked.value)
        assertFalse(hold(enabled = true, afterMs = 0))
    }

    @Test
    fun `with Immediately a launch out of sight is held at once`() {
        unlockedAndAway()
        assertTrue(hold(afterMs = 0))
    }

    @Test
    fun `with the setting off a launch is never held`() {
        unlockedAndAway()
        now = 1_000_000
        assertFalse(hold(enabled = false, afterMs = 0))
    }

    @Test
    fun `a launch held out of sight is dropped when the owner is destroyed`() {
        unlockedAndAway()
        now = 100_000
        assertTrue(hold(afterMs = 30_000L))
        lock.onDestroy(owner)
        lock.onStart(enabled = true, afterMs = 30_000L)
        ask()
        host.listeners[1].succeeded()
        assertEquals(0, ran)
    }

    // The credential action from the system, with the platform constants as the fallback.

    private fun lockWith(action: String?, calls: IntArray = IntArray(1)) =
        AppLock(clock = { now }, host = host, systemCredentialAction = { calls[0]++; action })

    @Test
    fun `the system's own credential action is the one exempted, the constants only without it`() {
        val l = lockWith("vendor.action.CONFIRM")
        l.onStart(enabled = true)
        l.authenticateFor(owner, "t", "s") {}
        assertFalse(l.holdLaunch(owner, "vendor.action.CONFIRM", true, true, 30_000L) {})
        assertTrue(l.holdLaunch(owner, credential, true, true, 30_000L) {})
    }

    @Test
    fun `without a system action the platform constants are exempted`() {
        val l = lockWith(null)
        l.onStart(enabled = true)
        l.authenticateFor(owner, "t", "s") {}
        assertFalse(l.holdLaunch(owner, credential, true, true, 30_000L) {})
        assertFalse(l.holdLaunch(owner, "android.app.action.CONFIRM_DEVICE_CREDENTIAL_WITH_USER", true, true, 30_000L) {})
        assertTrue(l.holdLaunch(owner, "vendor.action.CONFIRM", true, true, 30_000L) {})
    }

    @Test
    fun `the system is asked once, and only when a prompt is active`() {
        val calls = IntArray(1)
        val l = lockWith(null, calls)
        l.onStart(enabled = true)
        l.holdLaunch(owner, credential, true, true, 30_000L) {}
        assertEquals(0, calls[0])
        l.authenticateFor(owner, "t", "s") {}
        l.holdLaunch(owner, credential, true, true, 30_000L) {}
        l.holdLaunch(owner, credential, true, true, 30_000L) {}
        assertEquals(1, calls[0])
    }
}
