package ru.adsrv.frpjump.ui.mqtt

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.*
import ru.adsrv.frpjump.ui.FrpJumpTheme

@RunWith(AndroidJUnit4::class)
class WbReadonlyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val control = WbControl("text", wbControlPath("synthetic", "text"), "Synthetic control",
        ControlMeta(type = "text"), "initial", emptyList())

    @org.junit.Before fun display() {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        org.junit.Assert.assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    @Test fun readonlyChangeClosesAnAlreadyOpenInputWithoutSending() {
        val current = mutableStateOf(control)
        val sent = mutableListOf<String>()
        compose.setContent { FrpJumpTheme { ControlRow(current.value, "en", null, null, null, {}, { sent += it }) } }
        compose.onNodeWithText("initial").performClick()
        val send = compose.activity.getString(R.string.mqtt_publish_send)
        compose.onNodeWithText(send).assertExists()
        compose.runOnIdle { current.value = control.copy(meta = control.meta.copy(readonly = true)) }
        compose.onNodeWithText(send).assertDoesNotExist()
        assertEquals(emptyList<String>(), sent)
    }

    @Test fun writableMetadataChangeAlsoDiscardsTheOldEditor() {
        val current = mutableStateOf(control)
        val sent = mutableListOf<String>()
        compose.setContent { FrpJumpTheme { ControlRow(current.value, "en", null, null, null, {}, { sent += it }) } }
        compose.onNodeWithText("initial").performClick()
        compose.runOnIdle { current.value = control.copy(meta = control.meta.copy(max = java.math.BigDecimal.TEN)) }
        compose.onNodeWithText(compose.activity.getString(R.string.mqtt_publish_send)).assertDoesNotExist()
        assertEquals(emptyList<String>(), sent)
    }

    @Test fun aDifferentAcceptedClientClosesTheOldEditorInTheActualDeviceTab() {
        val state = mutableStateOf(MqttUiState(state = ru.adsrv.frpjump.mqtt.MqttState.Open(ru.adsrv.frpjump.mqtt.MqttVersion.V5),
            wbDeviceId = "synthetic", wbDevice = WbDeviceView("synthetic", "Synthetic device", false, listOf(control)),
            wbWriteOwner = ru.adsrv.frpjump.mqtt.WbWriteOwner()))
        val sent = mutableListOf<String>()
        compose.setContent { FrpJumpTheme {
            WbDevicesTab(state.value, "en", androidx.compose.foundation.layout.PaddingValues(), {}, {}, { _, value -> sent += value })
        } }
        compose.onNodeWithText("initial").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.mqtt_publish_send)).assertExists()
        compose.runOnIdle { state.value = state.value.copy(wbWriteOwner = ru.adsrv.frpjump.mqtt.WbWriteOwner()) }
        compose.onNodeWithText(compose.activity.getString(R.string.mqtt_publish_send)).assertDoesNotExist()
        assertEquals(emptyList<String>(), sent)
    }

    @Test fun writableInputStillSendsItsExactText() {
        val sent = mutableListOf<String>()
        compose.setContent { FrpJumpTheme { ControlRow(control, "en", null, null, null, {}, { sent += it }) } }
        compose.onNodeWithText("initial").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("exact value")
        compose.onNodeWithText(compose.activity.getString(R.string.mqtt_publish_send)).performClick()
        compose.runOnIdle { assertEquals(listOf("exact value"), sent) }
    }

    @Test fun readonlyValueIsShownWithoutOpeningAnEditor() {
        val sent = mutableListOf<String>()
        val readonly = control.copy(meta = control.meta.copy(readonly = true))
        compose.setContent { FrpJumpTheme { ControlRow(readonly, "en", null, null, null, {}, { sent += it }) } }
        compose.onNodeWithText("initial").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.mqtt_publish_send)).assertDoesNotExist()
        assertEquals(emptyList<String>(), sent)
    }
}
