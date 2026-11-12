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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.MainActivity
import ru.adsrv.frpjump.settings.AppLockAfter
import ru.adsrv.frpjump.settings.AppSettings

/**
 * The real MainActivity: a window it opens itself (here the system settings) is a handoff, so with
 * "Immediately" the return within 30 s does not lock. Needs an opaque window: the photo picker is a
 * sheet over the app and does not stop it. No prompt comes up (the app is unlocked first).
 */
@RunWith(AndroidJUnit4::class)
class AppLockHandoffTest {
    private val app = ApplicationProvider.getApplicationContext<FrpJumpApp>()
    private var original: AppSettings? = null

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

    @Test fun returnFromAWindowTheAppOpenedDoesNotLockWithImmediately() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("resumed") { scenario.state == Lifecycle.State.RESUMED }
            assertFalse(app.lock.locked.value)
            scenario.onActivity { it.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            // Out of sight: MainActivity is stopped (CREATED) behind the system settings.
            waitFor("stopped behind the settings") { scenario.state == Lifecycle.State.CREATED }
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor("back in sight") { scenario.state == Lifecycle.State.RESUMED }
            assertFalse("a handoff does not count as an absence within 30 s", app.lock.locked.value)
        }
    }
}
