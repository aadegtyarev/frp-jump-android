package ru.adsrv.frpjump.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.settings.ThemeMode
import java.io.File

/** Shared Web/terminal full-screen exit overlay, against both kinds of content. */
@RunWith(AndroidJUnit4::class)
class FullscreenExitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val args get() = InstrumentationRegistry.getArguments()

    @Before fun display() {
        val landscape = args.getString("composerOrientation") == "landscape"
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        val deadline = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        args.getString("chatLocale")?.let { lang ->
            val res = compose.activity.resources
            @Suppress("DEPRECATION") res.updateConfiguration(Configuration(res.configuration).apply { setLocale(java.util.Locale.forLanguageTag(lang)) }, res.displayMetrics)
        }
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    @Test fun targetSizeDragAndRestorationKeepOneTapExit() {
        val clicks = mutableIntStateOf(0)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FrpJumpTheme(ThemeMode.DARK) { Box(Modifier.fillMaxSize().background(Color.White)) { CollapseButton { clicks.intValue++ } } } }
        val target = compose.onNodeWithTag("fullscreen_exit")
        target.assertIsDisplayed().assertHasClickAction().assertContentDescriptionEquals(compose.activity.getString(R.string.fullscreen_exit))
        val bounds = target.fetchSemanticsNode().boundsInRoot
        val tile = compose.onNodeWithTag("fullscreen_exit_tile", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertEquals(48f * density, bounds.width, 1f); assertEquals(48f * density, bounds.height, 1f)
        assertEquals(40f * density, tile.width, 1f); assertEquals(40f * density, tile.height, 1f)
        assertEquals(bounds.center.x, tile.center.x, 1f); assertEquals(bounds.center.y, tile.center.y, 1f)
        target.performTouchInput { down(center); moveBy(Offset(-80f * density, 80f * density)); up() }
        compose.runOnIdle { assertEquals(0, clicks.intValue) }
        val moved = target.fetchSemanticsNode().boundsInRoot
        assertTrue(moved.left < bounds.left - 40f * density); assertTrue(moved.top > bounds.top + 40f * density)
        restoration.emulateSavedInstanceStateRestore()
        val restored = target.fetchSemanticsNode().boundsInRoot
        assertEquals(moved.left, restored.left, 1f); assertEquals(moved.top, restored.top, 1f)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        target.performTouchInput { down(center); moveBy(Offset(-root.width * 2, root.height * 2)); up() }
        val clamped = target.fetchSemanticsNode().boundsInRoot
        assertTrue(clamped.left >= root.left); assertTrue(clamped.top >= root.top)
        assertTrue(clamped.right <= root.right); assertTrue(clamped.bottom <= root.bottom)
        compose.runOnIdle { assertEquals(0, clicks.intValue) }
        target.performTouchInput { click(Offset(2f * density, center.y)) }; compose.runOnIdle { assertEquals(1, clicks.intValue) }
    }

    @Test fun darkThemeOverWebAndTerminal() = preview(ThemeMode.DARK, "dark")
    @Test fun lightThemeOverWebAndTerminal() = preview(ThemeMode.LIGHT, "light")

    private fun preview(mode: ThemeMode, name: String) {
        compose.setContent {
            FrpJumpTheme(mode) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color.White)) {
                        Text("Web", color = Color(0xFF14201C)); CollapseButton { }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF141311))) {
                        Text("terminal", color = Color(0xFFD9EFE9)); CollapseButton { }
                    }
                }
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("fullscreen_exit", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        compose.waitForIdle(); SystemClock.sleep(300); compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val tag = listOf(args.getString("composerOrientation") ?: "portrait", args.getString("composerFontScale") ?: "1.0", args.getString("chatLocale") ?: "en").joinToString("-")
        File(compose.activity.getExternalFilesDir(null), "fullscreen-exit-$name-$tag.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
