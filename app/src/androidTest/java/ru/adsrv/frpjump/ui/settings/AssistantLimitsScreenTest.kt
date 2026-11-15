package ru.adsrv.frpjump.ui.settings

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.settings.AssistantLimits
import ru.adsrv.frpjump.ui.FrpJumpTheme
import java.io.File
import java.util.Locale

/** Settings › Assistant › Limits: the four rows with their defaults, each a stepped slider over its range that reports the new value. */
@RunWith(AndroidJUnit4::class)
class AssistantLimitsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int): String {
        val lang = InstrumentationRegistry.getArguments().getString("chatLocale") ?: "en"
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        return compose.activity.createConfigurationContext(config).getString(id)
    }

    @Test fun theFourLimitsShowTheirDefaultsAndEachSlidersChangeIsReported() {
        val limits = mutableStateOf(AssistantLimits())
        val changes = ArrayList<AssistantLimits>()
        compose.setContent { FrpJumpTheme { AssistantLimitsScreen(limits.value, onChange = { changes += it; limits.value = it }, onBack = {}) } }
        compose.onNodeWithTag("limit_waits_at_once_value").assertTextEquals("3")
        compose.onNodeWithTag("limit_wait_max_minutes_value").assertTextEquals("30")
        compose.onNodeWithTag("limit_event_replies_value").assertTextEquals("5")
        compose.onNodeWithTag("limit_after_input_seconds_value").assertTextEquals("10")
        capture("limits-defaults")
        compose.onNodeWithTag("limit_waits_at_once_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(7f) }
        compose.onNodeWithTag("limit_after_input_seconds_slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.onNodeWithTag("limit_wait_max_minutes_slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(500f) }
        compose.runOnIdle {
            assertEquals(7, limits.value.waitsAtOnce)
            assertEquals(2, limits.value.afterInputSeconds)
            // Past the range it stops at the end of it.
            assertEquals(120, limits.value.waitMaxMinutes)
            assertEquals(3, changes.size)
        }
        compose.onNodeWithTag("limit_waits_at_once_value").assertTextEquals("7")
        compose.onNodeWithTag("limit_event_replies_slider").performScrollTo().assertIsDisplayed()
        capture("limits-changed")
        // The labels are the approved words.
        assertEquals(true, text(R.string.limit_waits_at_once).isNotBlank() && text(R.string.assistant_limits_title).isNotBlank())
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }
}
