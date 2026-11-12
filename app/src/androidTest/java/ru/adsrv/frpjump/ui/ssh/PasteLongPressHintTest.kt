package ru.adsrv.frpjump.ui.ssh

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FrpJumpTheme

/**
 * The small picture in Paste's top-end corner: the hint of its long press. It is always drawn, muted while the long press is
 * not on offer, and never touches the paste glyph. Screenshots go to the app's external cache dir.
 */
@RunWith(AndroidJUnit4::class)
class PasteLongPressHintTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val offer = mutableStateOf(true)
    private var onTerm = Color.Unspecified
    private var muted = Color.Unspecified
    private var keyColor = Color.Unspecified

    @Before fun configureDisplay() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    private fun start(narrow: Boolean) {
        compose.setContent {
            FrpJumpTheme {
                onTerm = AppTheme.colors.onTerm; muted = AppTheme.colors.termMuted; keyColor = AppTheme.colors.termKey
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Spacer(Modifier.weight(1f))
                    val panel = if (narrow) Modifier.width(320.dp) else Modifier
                    Box(panel) {
                        ExtraKeys(remember { Modifiers() }, onKeyboard = {}, onPaste = {}, onKey = {}, textMode = false,
                            onTextMode = {}, textEditor = {}, canOfferImage = offer.value, onInsertImage = {})
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** The mark's strongest pixel, taken over the key's own colour: which of the two tints it is. */
    private fun tintOf(tag: String): Color {
        val bitmap = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val bg = keyColor.toArgb()
        fun dist(a: Int, b: Int) = (0..2).sumOf { val d = ((a shr (it * 8)) and 255) - ((b shr (it * 8)) and 255); d * d }
        var best = bg
        for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) {
            val p = bitmap.getPixel(x, y)
            if (dist(p, bg) > dist(best, bg)) best = p
        }
        assertTrue("the mark is not drawn over the key: $tag", dist(best, bg) > 0)
        return if (dist(best, onTerm.toArgb()) < dist(best, muted.toArgb())) onTerm else muted
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = compose.activity.resources.configuration
        runCatching { instrumentation.uiAutomation.waitForIdle(350, 3_000) }
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return
        val orientation = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        File(instrumentation.targetContext.externalCacheDir, "hint-paste-$name-$orientation-${config.fontScale}.png")
            .outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    private fun checkHint(narrow: Boolean) {
        start(narrow)
        for ((available, state) in listOf(true to "available", false to "unavailable")) {
            offer.value = available
            compose.waitForIdle()
            val hint = bounds("terminal_paste_hint"); val glyph = bounds("terminal_paste_glyph")
            assertTrue("the hint is drawn", hint.width > 0f && hint.height > 0f)
            assertTrue("the hint does not overlap the paste glyph: $hint $glyph", !hint.overlaps(glyph))
            assertTrue("the paste glyph stays the larger mark", glyph.width > hint.width && glyph.height > hint.height)
            assertTrue("the hint is in the top-end corner", hint.top <= glyph.top && hint.right >= glyph.right)
            assertEquals("the hint is ${if (available) "full" else "muted"}", if (available) onTerm else muted, tintOf("terminal_paste_hint"))
            capture(if (narrow) "$state-320dp" else state)
        }
    }

    @Test fun theHintIsDrawnAndMutedWithoutTheLongPress() = checkHint(false)

    @Test fun theHintFitsTheNarrowPanel() = checkHint(true)
}
