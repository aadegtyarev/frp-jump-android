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
import ru.adsrv.frpjump.settings.MqttIngressLimits
import ru.adsrv.frpjump.settings.MqttOverflow
import ru.adsrv.frpjump.ui.FrpJumpTheme

/** Actual settings entry + form; emulator preferences restored, no enrolled profile or broker. */
@RunWith(AndroidJUnit4::class)
class MqttIngressSettingsUiTest {
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
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    @After fun cleanup() {
        compose.runOnIdle { models.clear(); original?.let { old -> app.settings.update { old } } }
    }

    @Test fun actualSettingsEntrySavesAllValuesAndReopensCommittedChoices() {
        lateinit var vm: SettingsViewModel
        compose.runOnUiThread {
            original = app.settings.settings.value
            app.settings.update { it.copy(mqttIngress = MqttIngressLimits()) }
            vm = SettingsViewModel(app)
            models.put("mqtt-settings-test", vm)
        }
        compose.setContent {
            FrpJumpTheme { SettingsScreen(vm, null, null, {}, {}, onBack = null) }
        }
        compose.onNodeWithText(text(R.string.mqtt_ingress_title)).performScrollTo().performClick()
        edit("mqtt_limit_payload", "2048")
        edit("mqtt_limit_queue", "8192")
        edit("mqtt_limit_messages", "17")
        compose.onNodeWithTag("mqtt_overflow_STOP_SESSION").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText(text(R.string.save)).performClick()
        val expected = MqttIngressLimits(2048, 8192, 17, MqttOverflow.STOP_SESSION)
        compose.waitUntil(5000) { app.settings.settings.value.mqttIngress == expected }
        compose.onNodeWithTag("mqtt_limit_payload").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mqtt_ingress_title)).performScrollTo().performClick()
        field("mqtt_limit_payload").assertTextContains("2048")
        field("mqtt_limit_queue").assertTextContains("8192")
        field("mqtt_limit_messages").assertTextContains("17")
        compose.onNodeWithTag("mqtt_overflow_STOP_SESSION").assertIsSelected()
        compose.onNodeWithText(text(R.string.mqtt_ingress_apply_hint)).assertExists()
    }

    @Test fun invalidAndInconsistentNumbersCannotSave() {
        var saves = 0
        compose.setContent { FrpJumpTheme { MqttIngressScreen(MqttIngressLimits(), { _, _ -> saves++ }, {}) } }
        edit("mqtt_limit_payload", "9999999999")
        compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
        edit("mqtt_limit_payload", "2048")
        edit("mqtt_limit_queue", "2048") // fixed charge must also fit
        compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
        edit("mqtt_limit_queue", "8192")
        edit("mqtt_limit_messages", "0")
        compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
        edit("mqtt_limit_messages", "1")
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun saveWaitsForCommitAndFailureKeepsFormEditableForRetry() {
        var completion: ((String?) -> Unit)? = null
        var dismissed = 0
        var saved: MqttIngressLimits? = null
        compose.setContent {
            FrpJumpTheme { MqttIngressScreen(MqttIngressLimits(), { value, done -> saved = value; completion = done }, { dismissed++ }) }
        }
        edit("mqtt_limit_messages", "7")
        compose.onNodeWithText(text(R.string.save)).performClick()
        field("mqtt_limit_messages").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, dismissed); completion!!("synthetic write failure") }
        compose.onNodeWithText("synthetic write failure").assertExists()
        field("mqtt_limit_messages").assertIsEnabled()
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle {
            assertEquals(7, saved!!.queueMessages)
            assertEquals(0, dismissed)
            completion!!(null)
            completion!!(null)
            assertEquals(1, dismissed)
        }
    }

    @Test fun disposedSaveCannotDismissAReplacementForm() {
        val generation = mutableStateOf(0)
        var old: ((String?) -> Unit)? = null
        var latest: ((String?) -> Unit)? = null
        var dismissed = 0
        compose.setContent {
            FrpJumpTheme {
                key(generation.value) {
                    val owner = generation.value
                    MqttIngressScreen(MqttIngressLimits(), { _, done -> if (owner == 0) old = done else latest = done }, { dismissed++ })
                }
            }
        }
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle { generation.value++ }
        compose.runOnIdle { old!!(null); assertEquals(0, dismissed) }
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled().performClick()
        compose.runOnIdle { latest!!(null); assertEquals(1, dismissed) }
    }
}
