package ru.adsrv.frpjump.ui.settings

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

/** Settings › Assistant › Limits: the four rows (waits at once, longest wait, event replies, assistant sessions) with their defaults, each a stepped slider over its range that reports the new value. */
@RunWith(AndroidJUnit4::class)
class AssistantLimitsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** The screen is composed in the activity's own locale, so the strings it shows are the activity's. */
    private fun text(id: Int): String = compose.activity.getString(id)

    @Test fun theFourLimitsShowTheirDefaultsAndEachStepperChangesItsValueWithinItsRange() {
        val limits = mutableStateOf(AssistantLimits())
        val changes = ArrayList<AssistantLimits>()
        compose.setContent { FrpJumpTheme { AssistantLimitsScreen(limits.value, onChange = { changes += it; limits.value = it }, onBack = {}) } }
        compose.onNodeWithTag("limit_waits_at_once_value").assertTextEquals("3")
        compose.onNodeWithTag("limit_wait_max_minutes_value").assertTextEquals("30")
        compose.onNodeWithTag("limit_event_replies_value").assertTextEquals("5")
        compose.onNodeWithTag("limit_sessions_at_once_value").performScrollTo().assertTextEquals("3")
        compose.onNode(hasContentDescription(text(R.string.limit_sessions_at_once) + ": 3")).assertExists()
        // TalkBack reads the label and the value; the buttons read Decrease and Increase.
        compose.onNode(hasContentDescription(text(R.string.limit_waits_at_once) + ": 3")).assertExists()
        compose.onNodeWithTag("limit_waits_at_once_plus").assertContentDescriptionEquals(text(R.string.limit_increase))
        compose.onNodeWithTag("limit_waits_at_once_minus").assertContentDescriptionEquals(text(R.string.limit_decrease))
        capture("limits-defaults")
        compose.onNodeWithTag("limit_waits_at_once_plus").performClick()
        compose.onNodeWithTag("limit_event_replies_minus").performScrollTo().performClick()
        compose.onNodeWithTag("limit_wait_max_minutes_plus").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(4, 4, 31), listOf(limits.value.waitsAtOnce, limits.value.eventReplies, limits.value.waitMaxMinutes)); assertEquals(3, changes.size) }
        compose.onNodeWithTag("limit_waits_at_once_value").assertTextEquals("4")
        compose.onNodeWithTag("limit_sessions_at_once_plus").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(4, limits.value.sessionsAtOnce) }
        compose.onNodeWithTag("limit_sessions_at_once_minus").performClick(); compose.onNodeWithTag("limit_sessions_at_once_minus").performClick()
        compose.runOnIdle { assertEquals(2, limits.value.sessionsAtOnce) }
        // A long press repeats until the end of the range, where the button is off.
        compose.onNodeWithTag("limit_waits_at_once_plus").performTouchInput { down(center); advanceEventTime(2_500); up() }
        compose.runOnIdle { assertEquals(AssistantLimits.WAITS_AT_ONCE_RANGE.last, limits.value.waitsAtOnce) }
        compose.onNodeWithTag("limit_waits_at_once_plus").assertIsNotEnabled()
        compose.onNodeWithTag("limit_waits_at_once_minus").assertIsEnabled()
        compose.onNodeWithTag("limit_waits_at_once_value").assertTextEquals("10")
        capture("limits-at-max")
        val before = changes.size
        compose.onNodeWithTag("limit_waits_at_once_plus").performClick()
        compose.runOnIdle { assertEquals("a button at its end does nothing", before, changes.size) }
        // The longest wait stops at its end too.
        compose.onNodeWithTag("limit_wait_max_minutes_plus").performScrollTo().performTouchInput { down(center); advanceEventTime(12_000); up() }
        compose.runOnIdle { assertEquals(AssistantLimits.WAIT_MAX_MINUTES_RANGE.last, limits.value.waitMaxMinutes) }
        compose.onNodeWithTag("limit_wait_max_minutes_plus").assertIsNotEnabled()
    }

    private fun capture(name: String) {
        // The first row is drawn and the frame settled before the screen is taken (a screenshot right after setContent can be blank).
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("limit_waits_at_once_value").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        android.os.SystemClock.sleep(300)
        compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }
}
