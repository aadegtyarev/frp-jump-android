package ru.adsrv.frpjump.ui

import android.os.SystemClock
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.MainActivity
import ru.adsrv.frpjump.settings.AppSettings

/**
 * The floating selection toolbars (the terminal's, a web page's, a text field's) are system action modes of
 * the activity's window. The real [MainActivity] ends the open ones when the lock engages, and ends one that
 * starts while locked. The lock is engaged through `AppLock.onStart`, the call the activity makes on start
 * (a cold start locks): no screen lock or PIN is needed, so the device's credentials stay untouched.
 */
@RunWith(AndroidJUnit4::class)
class LockedActionModeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<FrpJumpApp>()
    private var original: AppSettings? = null

    @Before fun lockSettingOff() {
        original = app.settings.settings.value
        app.settings.update { it.copy(appLock = false) }
    }

    @After fun restore() {
        instrumentation.runOnMainSync { app.lock.unlock() }
        original?.let { old -> app.settings.update { old } }
    }

    private fun waitFor(what: String, ms: Long = 10_000, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + ms
        while (!check() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertEquals(what, true, check())
    }

    private class Toolbar : ActionMode.Callback2() {
        val created = AtomicInteger()
        val destroyed = AtomicInteger()
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            created.incrementAndGet()
            menu.add("Copy")
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
        override fun onDestroyActionMode(mode: ActionMode) { destroyed.incrementAndGet() }
    }

    private fun startToolbar(scenario: ActivityScenario<MainActivity>, toolbar: Toolbar) {
        scenario.onActivity { activity ->
            val view: View = activity.window.decorView
            view.startActionMode(toolbar, ActionMode.TYPE_FLOATING)
        }
    }

    @Test fun lockEndsAnOpenSelectionToolbarAndOneStartedWhileLocked() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("resumed") { scenario.state == Lifecycle.State.RESUMED }
            val open = Toolbar()
            startToolbar(scenario, open)
            waitFor("the toolbar started") { open.created.get() == 1 }
            assertEquals("a toolbar of an unlocked app stays", 0, open.destroyed.get())

            // The lock engages (a cold start of an app with the lock on does exactly this).
            instrumentation.runOnMainSync { app.lock.onStop(); app.lock.onStart(true, 0L) }
            waitFor("locked") { app.lock.locked.value }
            waitFor("the open toolbar was ended by the lock") { open.destroyed.get() == 1 }

            val late = Toolbar()
            startToolbar(scenario, late)
            waitFor("a toolbar that starts while locked is ended at once") { late.created.get() == 1 && late.destroyed.get() == 1 }
            assertTrue(app.lock.locked.value)

            // After the unlock a selection toolbar can start again and stays.
            instrumentation.runOnMainSync { app.lock.unlock() }
            waitFor("unlocked") { !app.lock.locked.value }
            val again = Toolbar()
            startToolbar(scenario, again)
            waitFor("a toolbar starts after the unlock") { again.created.get() == 1 }
            SystemClock.sleep(1_000)
            assertEquals("and is not ended", 0, again.destroyed.get())
        }
    }
}
