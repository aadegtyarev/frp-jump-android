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
import ru.adsrv.frpjump.settings.LIMITS
import ru.adsrv.frpjump.ui.FrpJumpTheme
import java.io.File

/** Settings › Assistant › Limits: the sixteen rows in four sections (assistant, waits, terminal, tunnels) with their defaults, each a stepped slider over its range that reports the new value. */
@RunWith(AndroidJUnit4::class)
class AssistantLimitsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** The screen is composed in the activity's own locale, so the strings it shows are the activity's. */
    private fun text(id: Int): String = compose.activity.getString(id)

    @Test fun theWaitLimitsShowTheirDefaultsAndEachStepperChangesItsValueWithinItsRange() {
        val limits = mutableStateOf(AssistantLimits())
        val changes = ArrayList<AssistantLimits>()
        compose.setContent { FrpJumpTheme { AssistantLimitsScreen(limits.value, onChange = { changes += it; limits.value = it }, onBack = {}) } }
        scrolled("limit_waits_at_once_value").assertTextEquals("3")
        scrolled("limit_wait_max_minutes_value").assertTextEquals("30")
        scrolled("limit_event_replies_value").assertTextEquals("5")
        scrolled("limit_sessions_at_once_value").assertTextEquals("3")
        compose.onNode(hasContentDescription(text(R.string.limit_sessions_at_once) + ": 3")).assertExists()
        // TalkBack reads the label and the value; the buttons read Decrease and Increase.
        compose.onNode(hasContentDescription(text(R.string.limit_waits_at_once) + ": 3")).assertExists()
        scrolled("limit_waits_at_once_plus").assertContentDescriptionEquals(text(R.string.limit_increase))
        scrolled("limit_waits_at_once_minus").assertContentDescriptionEquals(text(R.string.limit_decrease))
        scrolled("limit_tool_timeout_s_value")
        capture("limits-defaults")
        scrolled("limit_waits_at_once_plus").performClick()
        scrolled("limit_event_replies_minus").performClick()
        scrolled("limit_wait_max_minutes_plus").performClick()
        compose.runOnIdle { assertEquals(listOf(4, 4, 31), listOf(limits.value.waitsAtOnce, limits.value.eventReplies, limits.value.waitMaxMinutes)); assertEquals(3, changes.size) }
        scrolled("limit_waits_at_once_value").assertTextEquals("4")
        scrolled("limit_sessions_at_once_plus").performClick()
        compose.runOnIdle { assertEquals(4, limits.value.sessionsAtOnce) }
        scrolled("limit_sessions_at_once_minus").performClick(); scrolled("limit_sessions_at_once_minus").performClick()
        compose.runOnIdle { assertEquals(2, limits.value.sessionsAtOnce) }
        // A long press repeats until the end of the range, where the button is off.
        scrolled("limit_waits_at_once_plus").performTouchInput { down(center); advanceEventTime(2_500); up() }
        compose.runOnIdle { assertEquals(AssistantLimits.WAITS_AT_ONCE_RANGE.last, limits.value.waitsAtOnce) }
        scrolled("limit_waits_at_once_plus").assertIsNotEnabled()
        scrolled("limit_waits_at_once_minus").assertIsEnabled()
        scrolled("limit_waits_at_once_value").assertTextEquals("10")
        capture("limits-at-max")
        val before = changes.size
        scrolled("limit_waits_at_once_plus").performClick()
        compose.runOnIdle { assertEquals("a button at its end does nothing", before, changes.size) }
        // The longest wait stops at its end too.
        scrolled("limit_wait_max_minutes_plus").performTouchInput { down(center); advanceEventTime(12_000); up() }
        compose.runOnIdle { assertEquals(AssistantLimits.WAIT_MAX_MINUTES_RANGE.last, limits.value.waitMaxMinutes) }
        scrolled("limit_wait_max_minutes_plus").assertIsNotEnabled()
    }

    /** The node of [tag], scrolled into view (the screen is longer than the window). */
    private fun scrolled(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    @Test fun everyLimitShowsItsDefaultAndStepsByItsOwnStepWithinItsRangeInItsSection() {
        val limits = mutableStateOf(AssistantLimits())
        compose.setContent { FrpJumpTheme { AssistantLimitsScreen(limits.value, onChange = { limits.value = it }, onBack = {}) } }
        for (group in ru.adsrv.frpjump.settings.LimitGroup.entries) scrolled("limit_${LIMITS.first { it.group == group }.key}_value")
        for (spec in LIMITS) {
            val tag = "limit_${spec.key}"
            scrolled("${tag}_value").assertTextEquals(spec.get(AssistantLimits()).toString())
            scrolled("${tag}_plus").performClick()
            compose.runOnIdle { assertEquals(spec.key, minOf(spec.get(AssistantLimits()) + spec.step, spec.range.last), spec.get(limits.value)) }
            scrolled("${tag}_minus").performClick(); scrolled("${tag}_minus").performClick()
            compose.runOnIdle { assertEquals(spec.key, maxOf(spec.get(AssistantLimits()) - spec.step, spec.range.first), spec.get(limits.value)) }
        }
        compose.runOnIdle { assertEquals("all sixteen rows are covered", 16, LIMITS.size) }
        // The last row, at the foot of the screen, and every limit at its largest.
        scrolled("limit_reconnect_minutes_value")
        capture("limits-defaults-scrolled")
        limits.value = LIMITS.fold(AssistantLimits()) { l, spec -> spec.set(l, spec.range.last) }
        scrolled("limit_tool_timeout_s_value")
        capture("limits-at-max-top")
        scrolled("limit_reconnect_minutes_value")
        capture("limits-at-max-scrolled")
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
