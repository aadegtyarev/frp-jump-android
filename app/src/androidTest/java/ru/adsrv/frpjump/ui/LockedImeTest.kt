package ru.adsrv.frpjump.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.MainActivity
import ru.adsrv.frpjump.lock.AppLock
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.ui.settings.SettingsViewModel
import ru.adsrv.frpjump.ui.ssh.TerminalTextComposer

/**
 * The real [MainActivity] and its lock: a window the screens make disappears when the lock engages (the wiring of
 * `LocalAppLocked`), no keyboard may attach to the window while locked (`FLAG_ALT_FOCUSABLE_IM`), and the owner
 * can type again after EVERY way out of the lock: the flag is cleared, the keyboard comes up, typed text is still there.
 *
 * No compose test rule here: it takes over the activity's coroutines and breaks its navigation off the main thread. What
 * the tests need from the screens comes from a probe composed inside the activity ([MainActivity.windowProbe]), and
 * taps are real touches.
 *
 * The lock is engaged through `AppLock.onStop` + `onStart(true, 0)`, the calls the activity makes when it is left and
 * entered again with "Lock after: Immediately"; no screen lock or PIN is needed. The test of the prompt itself (PIN typed)
 * needs `-e lockPin <digits>` and a cold process, one test per run, and is skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class LockedImeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<FrpJumpApp>()
    private var original: AppSettings? = null
    private val pin: String? get() = InstrumentationRegistry.getArguments().getString("lockPin")

    @Before fun settingsOff() {
        original = app.settings.settings.value
        app.settings.update { it.copy(appLock = false) }
    }

    @After fun restore() {
        MainActivity.windowProbe = null
        instrumentation.runOnMainSync { app.lock.unlock() }
        original?.let { old -> app.settings.update { old } }
    }

    private fun waitFor(what: String, ms: Long = 10_000, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + ms
        while (!check() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertEquals(what, true, check())
    }

    private fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { s -> waitFor("resumed") { s.state == Lifecycle.State.RESUMED } }

    private fun engageLock() {
        instrumentation.runOnMainSync { app.lock.onStop(); app.lock.onStart(true, 0L) }
        waitFor("locked") { app.lock.locked.value }
    }

    private fun unlock() = instrumentation.runOnMainSync { app.lock.unlock() }

    private fun imeFlag(s: ActivityScenario<MainActivity>): Boolean {
        var set = false
        s.onActivity { set = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0 }
        return set
    }

    private fun imeVisible(s: ActivityScenario<MainActivity>): Boolean {
        var up = false
        s.onActivity { up = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        return up
    }

    private fun hideIme(s: ActivityScenario<MainActivity>) {
        s.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        waitFor("keyboard down") { !imeVisible(s) }
    }

    private fun showIme(s: ActivityScenario<MainActivity>) {
        s.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.ime()) }
    }

    private fun tap(x: Float, y: Float) {
        val automation = instrumentation.uiAutomation
        val down = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 50)) {
            val event = MotionEvent.obtain(down, at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            automation.injectInputEvent(event, true)
            event.recycle()
        }
    }

    // ---- the wiring: a wrapped window of the real activity leaves with the lock

    @Test fun aWrappedWindowOfTheRealActivityLeavesWithTheLockAndComesBack() {
        val alive = AtomicInteger()
        MainActivity.windowProbe = {
            AppAlertDialog(
                onDismissRequest = {},
                text = {
                    DisposableEffect(Unit) { alive.incrementAndGet(); onDispose { alive.decrementAndGet() } }
                    Text("Probe dialog")
                },
                confirmButton = {},
            )
        }
        launch().use {
            waitFor("the probe dialog is up") { alive.get() == 1 }
            engageLock()
            waitFor("no dialog under the lock") { alive.get() == 0 }
            unlock()
            waitFor("the dialog is back") { alive.get() == 1 }
        }
    }

    // ---- the flag follows the lock

    @Test fun theImeFlagIsSetWhileLockedAndClearedByTheUnlock() {
        launch().use { s ->
            assertFalse("no flag while unlocked", imeFlag(s))
            engageLock()
            waitFor("flag set") { imeFlag(s) }
            unlock()
            waitFor("flag cleared by the unlock") { !imeFlag(s) }
        }
    }

    @Test fun turningTheSettingOffClearsTheFlag() {
        launch().use { s ->
            engageLock()
            waitFor("flag set") { imeFlag(s) }
            // Settings › Unlock with fingerprint, turned off (SettingsViewModel.turnOffAppLock): unlocks and clears the setting.
            instrumentation.runOnMainSync { SettingsViewModel(app).turnOffAppLock() }
            waitFor("unlocked") { !app.lock.locked.value }
            waitFor("flag cleared") { !imeFlag(s) }
        }
    }

    @Test fun aPhoneWithoutScreenLockUnlocksItselfAndClearsTheFlag() {
        assumeTrue("needs a phone with no screen lock", !AppLock.hasScreenLock(app))
        app.settings.update { it.copy(appLock = true) }
        launch().use { s ->
            engageLock()
            waitFor("flag set") { imeFlag(s) }
            // Out of sight and back: onResume asks to unlock, and with no screen lock the app unlocks and turns the setting off.
            s.moveToState(Lifecycle.State.CREATED)
            s.moveToState(Lifecycle.State.RESUMED)
            waitFor("unlocked by promptUnlock") { !app.lock.locked.value }
            waitFor("flag cleared") { !imeFlag(s) }
        }
    }

    @Test fun aRecreationWhileLockedDoesNotLeaveTheFlagStuck() {
        assumeTrue("needs a phone with no screen lock (the new activity then unlocks itself)", !AppLock.hasScreenLock(app))
        app.settings.update { it.copy(appLock = true) }
        launch().use { s ->
            engageLock()
            waitFor("flag set") { imeFlag(s) }
            s.recreate()
            waitFor("resumed again") { s.state == Lifecycle.State.RESUMED }
            // The new window follows the lock: set while locked, clear once unlocked (here by promptUnlock), never stuck.
            waitFor("unlocked") { !app.lock.locked.value }
            waitFor("flag cleared on the new window") { !imeFlag(s) }
        }
    }

    // ---- the way back: the keyboard comes up again

    private var inserted by mutableIntStateOf(0)

    @Composable
    private fun Composer(value: TextFieldValue, onValue: (TextFieldValue) -> Unit) {
        val focus = remember { FocusRequester() }
        TerminalTextComposer(
            sessionId = 1, value = value, onValueChange = onValue, focus = focus, canInsert = true,
            onInsert = { inserted++ }, onExpand = {}, onKeys = {}, onInsertAndEnter = { inserted++ },
        )
    }

    @Test fun theTerminalTextComposerBringsTheKeyboardBackAfterUnlockAndKeepsItsDraft() {
        var value by mutableStateOf(TextFieldValue(""))
        inserted = 0
        MainActivity.windowProbe = { Composer(value) { value = it } }
        launch().use { s ->
            waitFor("the composer's keyboard") { imeVisible(s) }
            // The draft typed before the lock (the keyboard's typing itself is not under test).
            instrumentation.runOnMainSync { value = TextFieldValue("ls -la") }
            hideIme(s)
            engageLock()
            waitFor("flag set") { imeFlag(s) }
            // Locked: asked again (a rotation or a session change would), the keyboard still stays down.
            showIme(s)
            SystemClock.sleep(1500)
            assertFalse("the keyboard came up over the lock screen", imeVisible(s))
            unlock()
            waitFor("flag cleared") { !imeFlag(s) }
            waitFor("the keyboard is back after unlock") { imeVisible(s) }
            assertEquals("the draft is still there", "ls -la", value.text)
            assertEquals("nothing was sent by itself", 0, inserted)
        }
    }

    @Test fun anOrdinaryFormFieldTakesTheKeyboardAfterUnlock() {
        var at: androidx.compose.ui.geometry.Offset? = null
        MainActivity.windowProbe = {
            var text by remember { mutableStateOf("") }
            TextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.onGloballyPositioned { c -> at = c.positionOnScreen() + androidx.compose.ui.geometry.Offset(c.size.width / 2f, c.size.height / 2f) },
            )
        }
        launch().use { s ->
            waitFor("the probe field is placed") { at != null }
            engageLock()
            waitFor("flag set") { imeFlag(s) }
            unlock()
            waitFor("flag cleared") { !imeFlag(s) }
            val p = at!!
            tap(p.x, p.y)
            waitFor("the keyboard came up for the form field") { imeVisible(s) }
        }
    }

    // ---- the prompt itself (needs a screen lock with the PIN in -e lockPin, and a cold process)

    private fun key(code: Int) {
        val ui = instrumentation.uiAutomation
        val now = SystemClock.uptimeMillis()
        ui.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true)
        ui.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), true)
    }

    @Test fun aSuccessfulPinPromptClearsTheFlagAndTheKeyboardWorks() {
        assumeTrue("needs a screen lock and -e lockPin", AppLock.hasScreenLock(app) && pin != null)
        app.settings.update { it.copy(appLock = true) }
        var at: androidx.compose.ui.geometry.Offset? = null
        MainActivity.windowProbe = {
            var text by remember { mutableStateOf("") }
            TextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.onGloballyPositioned { c -> at = c.positionOnScreen() + androidx.compose.ui.geometry.Offset(c.size.width / 2f, c.size.height / 2f) },
            )
        }
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            waitFor("locked on a cold start") { app.lock.locked.value }
            waitFor("flag set") { imeFlag(s) }
            SystemClock.sleep(2_500)
            for (c in pin!!) key(KeyEvent.KEYCODE_0 + (c - '0'))
            SystemClock.sleep(300)
            key(KeyEvent.KEYCODE_ENTER)
            waitFor("unlocked") { !app.lock.locked.value }
            waitFor("flag cleared after the prompt") { !imeFlag(s) }
            waitFor("the probe field is placed") { at != null }
            val p = at!!
            tap(p.x, p.y)
            waitFor("the keyboard came up after the prompt") { imeVisible(s) }
        }
    }
}
