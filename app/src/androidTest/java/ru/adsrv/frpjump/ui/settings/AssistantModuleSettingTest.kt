package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.FrpJumpTheme

@RunWith(AndroidJUnit4::class)
class AssistantModuleSettingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun display() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }
    private fun toggle() = compose.onNodeWithText(compose.activity.getString(R.string.assistant_enabled))

    @Test fun switchWaitsForCommitAndSupportsEnableThenDisable() {
        val checked = mutableStateOf(false)
        var requested: Boolean? = null
        var complete: ((String?) -> Unit)? = null
        compose.setContent { FrpJumpTheme {
            AssistantModuleSetting(checked.value) { next, done -> requested = next; complete = done }
        } }
        toggle().assertIsOff().performClick()
        toggle().assertIsOff().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(true, requested); checked.value = true; complete!!(null) }
        toggle().assertIsOn().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(false, requested); checked.value = false; complete!!(null) }
        toggle().assertIsOff().assertIsEnabled()
    }

    @Test fun failedWriteKeepsTheCommittedSettingAndAllowsRetry() {
        var complete: ((String?) -> Unit)? = null
        var saves = 0
        compose.setContent { FrpJumpTheme { AssistantModuleSetting(false) { _, done -> saves++; complete = done } } }
        toggle().performClick()
        toggle().assertIsNotEnabled()
        compose.runOnIdle { complete!!("Synthetic write failed") }
        compose.onNodeWithText("Synthetic write failed").assertExists()
        toggle().assertIsOff().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, saves) }
    }

    @Test fun disposedAndDuplicateCompletionsCannotFinishANewerSave() {
        val shown = mutableStateOf(true)
        val callbacks = mutableListOf<(String?) -> Unit>()
        compose.setContent { FrpJumpTheme {
            if (shown.value) AssistantModuleSetting(false) { _, done -> callbacks += done }
        } }
        toggle().performClick()
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { shown.value = true }
        toggle().performClick()
        compose.runOnIdle { callbacks[0]("Old failure"); callbacks[0](null) }
        compose.onNodeWithText("Old failure").assertDoesNotExist()
        toggle().assertIsNotEnabled()
        compose.runOnIdle { callbacks[1](null); callbacks[1]("Duplicate failure") }
        toggle().assertIsEnabled()
        compose.onNodeWithText("Duplicate failure").assertDoesNotExist()
    }
}
