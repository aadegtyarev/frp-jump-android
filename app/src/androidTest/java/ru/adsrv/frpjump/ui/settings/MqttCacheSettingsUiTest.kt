package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.settings.MqttCacheLimits
import ru.adsrv.frpjump.settings.MqttOverflow
import ru.adsrv.frpjump.ui.FrpJumpTheme

/** Actual Settings row/VM/store on an unenrolled emulator; all original preferences restored. */
@RunWith(AndroidJUnit4::class)
class MqttCacheSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var original: AppSettings? = null
    private val models = ViewModelStore()
    private val app get() = compose.activity.application as FrpJumpApp
    private fun text(id: Int) = compose.activity.getString(id)
    private fun field(tag: String) = compose.onNode(
        SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.EditableText) and hasAnyAncestor(hasTestTag(tag)))
    private fun edit(tag: String, value: String) { field(tag).performScrollTo().performTextReplacement(value) }
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
    @After fun cleanup() { compose.runOnIdle { models.clear(); original?.let { old -> app.settings.update { old } } } }

    @Test fun actualSettingsEntrySavesAndReopensEveryCacheChoice() {
        lateinit var vm: SettingsViewModel
        compose.runOnUiThread {
            original = app.settings.settings.value
            app.settings.update { it.copy(mqttCache = MqttCacheLimits()) }
            vm = SettingsViewModel(app)
            models.put("mqtt-cache-settings", vm)
        }
        compose.setContent { FrpJumpTheme { SettingsScreen(vm, null, null, {}, {}, onBack = null, onChat = {}) } }
        compose.onNodeWithText(text(R.string.mqtt_cache_title)).performScrollTo().performClick()
        edit("mqtt_cache_bytes", "8192")
        edit("mqtt_cache_topics", "17")
        edit("mqtt_cache_history", "3")
        compose.onNodeWithTag("mqtt_cache_overflow_STOP_SESSION").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.waitUntil(5000) { app.settings.settings.value.mqttCache == MqttCacheLimits(8192, 17, 3, MqttOverflow.STOP_SESSION) }
        compose.onNodeWithTag("mqtt_cache_bytes").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mqtt_cache_title)).performScrollTo().performClick()
        field("mqtt_cache_bytes").assertTextContains("8192")
        field("mqtt_cache_topics").assertTextContains("17")
        field("mqtt_cache_history").assertTextContains("3")
        compose.onNodeWithTag("mqtt_cache_overflow_STOP_SESSION").assertIsSelected()
        compose.onNodeWithText(text(R.string.mqtt_cache_apply_hint)).assertExists()
    }

    @Test fun invalidRangesCannotQueueAWrite() {
        var saves = 0
        compose.setContent { FrpJumpTheme { MqttCacheScreen(MqttCacheLimits(), { _, _ -> saves++ }, {}) } }
        edit("mqtt_cache_bytes", "4095")
        compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
        edit("mqtt_cache_bytes", "8192")
        edit("mqtt_cache_topics", "0")
        compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
        edit("mqtt_cache_topics", "10")
        edit("mqtt_cache_history", "201")
        compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
        edit("mqtt_cache_history", "2")
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun failureAndPendingWriteKeepTheFormUntilOneSuccessfulCompletion() {
        var completion: ((String?) -> Unit)? = null
        var dismissed = 0
        compose.setContent { FrpJumpTheme { MqttCacheScreen(MqttCacheLimits(), { _, done -> completion = done }, { dismissed++ }) } }
        compose.onNodeWithText(text(R.string.save)).performClick()
        field("mqtt_cache_history").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, dismissed); completion!!("synthetic cache write failure") }
        compose.onNodeWithText("synthetic cache write failure").assertExists()
        field("mqtt_cache_history").assertIsEnabled()
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle { completion!!(null); completion!!(null); assertEquals(1, dismissed) }
    }

    @Test fun anOldSaveCannotCloseAReplacementCacheForm() {
        val generation = mutableStateOf(0)
        var old: ((String?) -> Unit)? = null
        var latest: ((String?) -> Unit)? = null
        var dismissed = 0
        compose.setContent {
            FrpJumpTheme { key(generation.value) {
                val owner = generation.value
                MqttCacheScreen(MqttCacheLimits(), { _, done -> if (owner == 0) old = done else latest = done }, { dismissed++ })
            } }
        }
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle { generation.value++ }
        compose.runOnIdle { old!!(null); assertEquals(0, dismissed) }
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle { latest!!(null); assertEquals(1, dismissed) }
    }
}
