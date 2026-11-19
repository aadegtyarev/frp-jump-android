package ru.adsrv.frpjump.ui.connections

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.getBoundsInRoot
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.DeviceGroup
import ru.adsrv.frpjump.connections.connectionRows
import ru.adsrv.frpjump.connections.groupByDevice
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.settings.ThemeMode
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.tunnel.VisitorState
import ru.adsrv.frpjump.ui.FrpJumpTheme
import ru.adsrv.frpjump.ui.AppTheme
import java.io.File

/** Actual Tunnels content with synthetic rows; no profile runtime or provider is started. */
class TunnelsLayoutVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val args get() = InstrumentationRegistry.getArguments()

    @org.junit.Before fun orientation() {
        val landscape = args.getString("composerOrientation") == "landscape"
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val expected = if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
        val until = android.os.SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && android.os.SystemClock.uptimeMillis() < until) android.os.SystemClock.sleep(50)
        args.getString("chatLocale")?.let { language ->
            @Suppress("DEPRECATION")
            compose.activity.resources.updateConfiguration(android.content.res.Configuration(compose.activity.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }, compose.activity.resources.displayMetrics)
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(800)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            val suffix = "${args.getString("chatLocale") ?: "en"}-${args.getString("composerOrientation") ?: "portrait"}-font${args.getString("composerFontScale") ?: "1.0"}"
            File(compose.activity.getExternalFilesDir(null), "$name-$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    private fun host(multi: Boolean) {
        val device = DeviceRow("beelink", online = true, lastSeen = null, disabled = false, label = "красный")
        val connections = listOf(Connection("ssh", "beelink", 22, PortKind.SSH, favorite = true, user = "adegtyarev"), Connection("web", "beelink", 18765, PortKind.WEB, customName = "Макеты"))
        val tunnels = listOf(22, 18765).map { Tunnel("beelink", it, "synthetic-$it", 40000 + it, VisitorState(TunnelStatus.DIRECT)) }
        val rows = connectionRows("work", connections, TunnelsState(tunnels = tunnels))
        val groups = groupByDevice(rows, mapOf("work" to listOf(device)))
        val more = (1..16).map { DeviceGroup(if (multi && it > 8) "home" else "work", "device-${it.toString().padStart(2, '0')}", null, emptyList()) }
        val devices = listOf(DeviceGroup("work", device.name, device, emptyList())) + more
        val names = if (multi) mapOf("work" to "way.adsrv.ru", "home" to "Дом") else mapOf("work" to "way.adsrv.ru")
        val state = ConnectionsUiState(groups = groups, devices = devices, profileNames = names, loaded = true)
        compose.setContent {
            FrpJumpTheme(mode = ThemeMode.DARK) {
                val ui = remember { ConnectionUi() }
                val actions = remember { RowActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { false }, {}, {}) }
                Box(Modifier.widthIn(max = 400.dp).fillMaxHeight().background(AppTheme.colors.ground)) {
                    ConnectionsContent(state, emptyList(), emptyMap(), ui, actions, null, {}, false, {}, {}, { it() }, {}, {}, {})
                }
            }
        }
    }

    @Test fun singleProfileCardsAndLongDevicePicker() {
        host(false)
        shot("tunnels-layout-single")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Макеты"))
        compose.onNodeWithText("Макеты").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("красный", substring = true))
        compose.onNodeWithContentDescription("красный", substring = true).assertIsDisplayed()
        shot("tunnels-layout-single-scrolled")
        compose.onNodeWithTag("tunnels_devices_filter").performClick()
        compose.onNodeWithTag("tunnels_filter_device_work/beelink").assertIsDisplayed()
        compose.onNodeWithTag("tunnels_filter_done").assertIsDisplayed()
        shot("tunnels-picker-single")
    }

    @Test fun expandedSearchKeepsControlsAccessibleWithKeyboard() {
        host(false)
        compose.onNodeWithTag(TUNNELS_SEARCH_TAG).performClick()
        compose.onNodeWithTag(TUNNELS_SEARCH_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag("tunnels_devices_filter").assertIsDisplayed()
        compose.onNodeWithTag(TUNNELS_FILTER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TUNNELS_STOP_ALL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TUNNELS_SEARCH_FIELD_TAG).performTextInput("SSH")
        compose.onNodeWithTag(TUNNELS_SEARCH_CLEAR_TAG).assertIsDisplayed()
        if (args.getString("composerOrientation") != "landscape" && (args.getString("composerFontScale") ?: "1.0").toFloat() < 1.5f) {
            val field = compose.onNodeWithTag(TUNNELS_SEARCH_FIELD_TAG).getBoundsInRoot()
            assertTrue("search must leave usable typing width", (field.right - field.left).value >= 40f)
        }
        android.os.SystemClock.sleep(500)
        shot("tunnels-layout-search-keyboard")
    }

    @Test fun multipleProfilesUseOneSectionHeadingPerProfile() {
        host(true)
        shot("tunnels-layout-multi")
        compose.onNodeWithTag("tunnels_devices_filter").performClick()
        shot("tunnels-picker-multi")
        compose.onNodeWithTag("tunnels_filter_list").performScrollToNode(hasText("Дом"))
        compose.onNodeWithText("Дом").assertIsDisplayed()
        compose.onNodeWithTag("tunnels_filter_list").performScrollToNode(hasText("way.adsrv.ru"))
        compose.onNodeWithText("way.adsrv.ru").assertIsDisplayed()
    }
}
