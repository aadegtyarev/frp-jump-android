package ru.adsrv.frpjump.ui.devices

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.devices.DeviceIcon
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.devices.DisplayChange
import ru.adsrv.frpjump.settings.ThemeMode
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FrpJumpTheme

/** Synthetic device rows only; no enrollment, server calls or production display overrides. */
@RunWith(AndroidJUnit4::class)
class DeviceIconsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun configureDisplay() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    @Test fun pickerSendsExistingPcValue() = pick(R.string.device_icon_pc, "pc")
    @Test fun pickerSendsExistingWirenBoardValue() = pick(R.string.device_icon_wirenboard, "wirenboard")

    private fun pick(label: Int, wire: String) {
        var saved: DisplayChange? = null
        compose.setContent {
            FrpJumpTheme {
                DeviceDisplaySheet(
                    DeviceRow("synthetic", true, null, false, kind = "linux"), focusLabel = false,
                    onSave = { change, done -> saved = change; done(null) }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(compose.activity.getString(label)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.save)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(wire, saved?.icon?.value); assertNull(saved?.label) }
    }

    @Test fun compactGlyphsFitTilesAndStatusDotsInBothThemes() {
        val theme = mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            FrpJumpTheme(theme.value) {
                Column(Modifier.fillMaxSize().background(AppTheme.colors.card).safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (icon in listOf(DeviceIcon.WIRENBOARD, DeviceIcon.PC)) {
                        Text(icon.wire, color = AppTheme.colors.ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (size in listOf(24, 32, 40)) {
                                Box(Modifier.testTag("${icon.wire}-$size")) {
                                    DeviceTile(icon, dot = AppTheme.colors.accent, size = size.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.runOnIdle { theme.value = mode }
            for (icon in listOf("wirenboard", "pc")) for (size in listOf(24, 32, 40)) {
                val node = compose.onNodeWithTag("$icon-$size").assertIsDisplayed().fetchSemanticsNode()
                assertEquals(size * compose.activity.resources.displayMetrics.density, node.boundsInRoot.width, 1f)
            }
            capture("device-icons-${mode.name.lowercase()}")
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching { instrumentation.uiAutomation.waitForIdle(350, 3000) }
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        val c = compose.activity.resources.configuration
        File(instrumentation.targetContext.externalCacheDir, "$name-${c.orientation}-${c.fontScale}.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}
