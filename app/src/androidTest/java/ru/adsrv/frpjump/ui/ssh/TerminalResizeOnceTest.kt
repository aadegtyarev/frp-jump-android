package ru.adsrv.frpjump.ui.ssh

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.RemoteTerminal
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.ssh.WindowChangeSender
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FrpJumpTheme

/**
 * The terminal grid is resized once per expansion of the key panel, the composer or the keyboard,
 * not on every frame of their animation. A real Termux view in the production layout (the column
 * with imePadding, SettledSizeBox, ExtraKeys, the composer), fed with synthetic output; no SSH.
 * Each expansion prints its frame statistics and counters as instrumentation status lines.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TerminalResizeOnceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heightChanges = 0
    private var rowChanges = 0
    private var emulatorSets = 0
    private var lastRows = -1
    private var windowChanges = 0
    private var cursorRow = -1
    private var boxHeight = 0
    private lateinit var remote: RemoteTerminal
    private lateinit var input: TerminalInput
    private val sender = WindowChangeSender(ui, send = { _, _ -> windowChanges++; true })
    private var textMode by mutableStateOf(false)

    @Before fun setUp() {
        val arguments = InstrumentationRegistry.getArguments()
        val landscape = arguments.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        val fontScale = arguments.getString("composerFontScale")?.toFloat() ?: 1f
        assertEquals(fontScale, compose.activity.resources.configuration.fontScale, 0.01f)
        instrumentation.runOnMainSync {
            remote = RemoteTerminal(proxy(TerminalSessionClient::class.java) { _, _ -> null })
            val view = TerminalView(compose.activity, null).apply { isFocusable = true; isFocusableInTouchMode = true }
            input = TerminalInput(compose.activity, view)
            view.setTerminalViewClient(proxy(TerminalViewClient::class.java) { name, _ ->
                if (name == "onEmulatorSet") {
                    emulatorSets++
                    val rows = remote.rows
                    if (rows != lastRows) { if (lastRows >= 0) rowChanges++; lastRows = rows }
                    cursorRow = remote.session.emulator.cursorRow
                    sender.request(remote.columns, rows)
                }
                null
            })
            input.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (b - t != ob - ot && ob - ot != 0 || (b - t != ob - ot && r - l != or - ol)) heightChanges++
            }
            view.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, compose.activity.resources.displayMetrics).toInt())
            view.attachSession(remote.session)
            val text = StringBuilder()
            for (i in 1..300) text.append("line $i synthetic output for the terminal grid\r\n")
            val bytes = text.toString().toByteArray()
            remote.session.emulator.append(bytes, bytes.size)
        }
        compose.setContent {
            FrpJumpTheme {
                val focus = remember { FocusRequester() }
                val modifiers = remember { Modifiers() }
                var value by remember { mutableStateOf(TextFieldValue("")) }
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    SettledSizeBox(expectKey = textMode, modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black).onSizeChanged { boxHeight = it.height }) {
                        AndroidView(factory = { input }, modifier = Modifier.fillMaxSize())
                    }
                    ExtraKeys(
                        modifiers, onKeyboard = {}, onPaste = {}, onKey = {},
                        textMode = textMode, onTextMode = { textMode = it },
                        textEditor = {
                            TerminalTextComposer(
                                1, value, { value = it }, focus, true, {},
                                onExpand = {}, onKeys = { textMode = false }, onInsertAndEnter = {},
                            )
                        },
                    )
                }
            }
        }
        settle()
    }

    @After fun tearDown() {
        sender.close()
        ui.cancel()
        instrumentation.runOnMainSync { remote.finish() }
    }

    private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            val result = when (method.name) {
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "Synthetic${type.simpleName}"
                else -> handler(method.name, args)
            }
            result ?: when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Float.TYPE -> 1f
                java.lang.Integer.TYPE -> 0
                else -> null
            }
        } as T
    }

    private fun settle() {
        compose.waitForIdle()
        SystemClock.sleep(1_200)
        compose.waitForIdle()
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
    }

    private fun imeVisible() = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun showIme() {
        compose.runOnUiThread {
            input.terminal.requestFocus()
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun hideIme() {
        compose.runOnUiThread { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
    }

    private fun measure(name: String, shrinks: Boolean, exact: Boolean = true, action: () -> Unit) {
        val pkg = "ru.adsrv.frpjump"
        shell("dumpsys gfxinfo $pkg reset")
        heightChanges = 0; rowChanges = 0; windowChanges = 0; emulatorSets = 0
        val rowsBefore = lastRows
        val boxBefore = boxHeight
        var samples = 0
        var gaps = 0
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (sampling.get()) {
                instrumentation.runOnMainSync {
                    samples++
                    if (!shrinks && input.height != boxHeight) gaps++
                }
                SystemClock.sleep(5)
            }
        }
        sampler.start()
        action()
        settle()
        sampling.set(false)
        sampler.join()
        SystemClock.sleep(300)
        val stats = shell("dumpsys gfxinfo $pkg")
        val total = Regex("Total frames rendered: (\\d+)").find(stats)?.groupValues?.get(1)
        val janky = Regex("Janky frames: (\\d+)").find(stats)?.groupValues?.get(1)
        val p90 = Regex("90th percentile: (\\d+)ms").find(stats)?.groupValues?.get(1)
        val summary = "$name: janky $janky/$total p90 ${p90}ms; terminal height changes $heightChanges; " +
            "emulator sets $emulatorSets; row changes $rowChanges ($rowsBefore->$lastRows, cursor row $cursorRow); window-changes $windowChanges; ime ${imeVisible()}; box $boxBefore->$boxHeight view ${input.height}"
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "RESIZE_ONCE $summary\n") })
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "RESIZE_ONCE $name: sampled $samples, frames with a gap $gaps\n") })
        if (!shrinks) assertEquals("the view equals the box on every sampled frame, $summary", 0, gaps)
        if (exact) assertEquals("emulator resizes, $summary", 1, emulatorSets) else assertTrue("emulator resizes, $summary", emulatorSets >= 1)
        // A collapse follows the animation; one frame stall longer than the sender's 100 ms wait may split its send.
        if (exact) assertEquals("window-changes, $summary", 1, windowChanges) else assertTrue("window-changes 1..2, $summary", windowChanges in 1..2)
        if (exact) assertEquals("row changes, $summary", 1, rowChanges)
        assertTrue("rows ${if (shrinks) "shrink" else "grow"}, $summary", if (shrinks) lastRows < rowsBefore else lastRows > rowsBefore)
        assertEquals("the view takes the final height of its slot, $summary", boxHeight, input.height)
        assertEquals("the cursor stays on the last row, $summary", lastRows - 1, cursorRow)
    }

    @Test fun keyPanelExpandResizesOnce() {
        measure("key panel expand", true) { compose.onNodeWithTag("terminal_more_keys").performClick() }
        measure("key panel collapse", false, exact = false) { compose.onNodeWithTag("terminal_more_keys").performClick() }
    }

    @Test fun composerOpenResizesOnce() {
        measure("composer open", true) { compose.onNodeWithTag("terminal_keyboard").performTouchInput { longClick() } }
        measure("composer close", false, exact = false) { textMode = false }
    }

    @Test fun keyboardShowAndHideResizeOnce() {
        measure("ime show", true) { showIme() }
        measure("ime hide", false, exact = false) { hideIme() }
    }
}
