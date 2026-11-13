package ru.adsrv.frpjump.lock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.MainActivity
import ru.adsrv.frpjump.settings.AppLockAfter
import ru.adsrv.frpjump.settings.AppSettings

/**
 * The real MainActivity with "Immediately": a window it opens itself (here the system settings) is a
 * handoff, so the return within 30 s does not lock; a plain absence (Home) does. Needs an opaque window:
 * the photo picker is a sheet over the app and does not stop it.
 *
 * What "locks" looks like depends on the phone: with a screen lock, [AppLock.locked] turns true and the
 * prompt comes up; without one, MainActivity turns the setting off ("no screen lock any more"). Both are
 * checked, so the test cannot pass vacuously.
 */
@RunWith(AndroidJUnit4::class)
class AppLockHandoffTest {
    private val app = ApplicationProvider.getApplicationContext<FrpJumpApp>()
    private var original: AppSettings? = null
    private val screenLock get() = AppLock.hasScreenLock(app)

    @Before fun settingsOn() {
        original = app.settings.settings.value
        app.settings.update { it.copy(appLock = true, appLockAfter = AppLockAfter.IMMEDIATELY) }
        app.lock.unlock()
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

    private fun globalAction(action: Int) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(action)
    }

    /** The lock engaged after an absence: [AppLock.locked], or without a screen lock the setting went off. */
    private fun lockEngaged(): Boolean =
        if (screenLock) app.lock.locked.value else !app.settings.settings.value.appLock

    @Test fun returnFromAWindowTheAppOpenedDoesNotLockWithImmediately() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("resumed") { scenario.state == Lifecycle.State.RESUMED }
            assertFalse(app.lock.locked.value)
            scenario.onActivity { it.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            // Out of sight: MainActivity is stopped (CREATED) behind the system settings.
            waitFor("stopped behind the settings") { scenario.state == Lifecycle.State.CREATED }
            SystemClock.sleep(1_500)
            globalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor("back in sight") { scenario.state == Lifecycle.State.RESUMED }
            // The "no screen lock" write is asynchronous: let it land before looking.
            SystemClock.sleep(1_500)
            assertFalse("a handoff does not count as an absence within 30 s", lockEngaged())
            assertTrue("the setting stays on", app.settings.settings.value.appLock)
            assertFalse(app.lock.locked.value)
        }
    }

    @Test fun aPlainAbsenceLocksWithImmediately() = absenceLocks(failedLaunchFirst = false)

    @Test fun aLaunchThatFailsDoesNotMarkAHandoff() = absenceLocks(failedLaunchFirst = true)

    private fun absenceLocks(failedLaunchFirst: Boolean) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("resumed") { scenario.state == Lifecycle.State.RESUMED }
            assertFalse(app.lock.locked.value)
            if (failedLaunchFirst) {
                var thrown = false
                scenario.onActivity {
                    try {
                        it.startActivity(Intent("ru.adsrv.frpjump.test.NO_SUCH_ACTION"))
                    } catch (e: android.content.ActivityNotFoundException) {
                        thrown = true
                    }
                }
                assertTrue("the launch must fail", thrown)
            }
            globalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            waitFor("stopped") { scenario.state == Lifecycle.State.CREATED }
            SystemClock.sleep(1_500)
            // Back to the app: start the same task again.
            val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(launch)
            waitFor("back in sight") { scenario.state == Lifecycle.State.RESUMED }
            waitFor("the lock engaged after a plain absence") { lockEngaged() }
        }
    }
}
