package ru.adsrv.frpjump.lock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.MainActivity
import ru.adsrv.frpjump.settings.AppLockAfter
import ru.adsrv.frpjump.settings.AppSettings

/**
 * While the lock is engaged, a window the app launches (system settings here) does not open over the lock
 * screen: it is held, runs once after the unlock, or is dropped (activity destroyed).
 *
 * Needs a phone with a screen lock and its PIN as `-e lockPin <digits>` (a synthetic PIN on the emulator),
 * and a cold process (one test method per `am instrument` run: the first unlock lasts the process).
 * Without them the tests that type the PIN are skipped.
 */
@RunWith(AndroidJUnit4::class)
class LockedLaunchGateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<FrpJumpApp>()
    private var original: AppSettings? = null
    private val pin: String? get() = InstrumentationRegistry.getArguments().getString("lockPin")

    @Before fun settingsOn() {
        original = app.settings.settings.value
        app.settings.update { it.copy(appLock = true, appLockAfter = AppLockAfter.SECONDS_30) }
    }

    @After fun restore() {
        app.lock.unlock()
        original?.let { old -> app.settings.update { old } }
    }

    private fun waitFor(what: String, ms: Long = 10_000, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + ms
        while (!check() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertEquals(what, true, check())
    }

    private fun key(code: Int) {
        val ui = instrumentation.uiAutomation
        val now = SystemClock.uptimeMillis()
        ui.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true)
        ui.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), true)
    }

    /** The prompt's PIN field has the focus: digits, then Enter. */
    private fun typePin() {
        for (c in pin!!) key(KeyEvent.KEYCODE_0 + (c - '0'))
        SystemClock.sleep(300)
        key(KeyEvent.KEYCODE_ENTER)
    }

    private fun lockedWithPrompt(scenario: ActivityScenario<MainActivity>) {
        assumeTrue("needs a screen lock and -e lockPin", AppLock.hasScreenLock(app) && pin != null)
        waitFor("resumed") { scenario.state == Lifecycle.State.RESUMED }
        waitFor("locked on a cold start") { app.lock.locked.value }
        SystemClock.sleep(2_500)
    }

    @Test fun aLaunchWhileLockedIsHeldAndRunsOnceAfterTheUnlock() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lockedWithPrompt(scenario)
            scenario.onActivity { it.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            SystemClock.sleep(2_500)
            assertEquals("nothing opened over the lock screen", Lifecycle.State.RESUMED, scenario.state)
            typePin()
            waitFor("unlocked") { !app.lock.locked.value }
            waitFor("the held launch ran: the app is behind the system settings") { scenario.state == Lifecycle.State.CREATED }
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor("back in sight") { scenario.state == Lifecycle.State.RESUMED }
            SystemClock.sleep(1_000)
            // It was marked as an app-opened window: the return does not lock (30 s default anyway).
            assertFalse(app.lock.locked.value)
        }
    }

    @Test fun aHeldLaunchIsDroppedWhenTheActivityIsDestroyed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lockedWithPrompt(scenario)
            scenario.onActivity { it.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            SystemClock.sleep(1_000)
            scenario.recreate()
            waitFor("resumed again") { scenario.state == Lifecycle.State.RESUMED }
            assertTrue("still locked", app.lock.locked.value)
            SystemClock.sleep(2_500)
            typePin()
            waitFor("unlocked") { !app.lock.locked.value }
            SystemClock.sleep(3_000)
            assertEquals("the dropped launch never ran", Lifecycle.State.RESUMED, scenario.state)
        }
    }

    /** The discriminator for the biometric fallback: the platform's own action, which the app never launches. */
    @Test fun theConfirmCredentialIntentIsTheOneTheGateExempts() {
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        assumeTrue("needs a screen lock", keyguard.isDeviceSecure)
        val intent = keyguard.createConfirmDeviceCredentialIntent("t", "d")
        assertNotNull(intent)
        assertTrue("action ${intent.action}", intent.action in AppLock.CREDENTIAL_ACTIONS)
    }
}
