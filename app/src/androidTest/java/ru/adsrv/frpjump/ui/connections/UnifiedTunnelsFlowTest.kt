package ru.adsrv.frpjump.ui.connections

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.adsrv.frpjump.connections.*
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.*
import ru.adsrv.frpjump.ui.FrpJumpTheme
import ru.adsrv.frpjump.ui.Routes
import ru.adsrv.frpjump.ui.TUNNEL_SCOPE
import ru.adsrv.frpjump.ui.openDeviceTunnels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

/** Production screen content and sheet, with local navigation and synthetic state/actions. */
class UnifiedTunnelsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun display() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val expected = if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
        val until = android.os.SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && android.os.SystemClock.uptimeMillis() < until) android.os.SystemClock.sleep(50)
        args.getString("chatLocale")?.let { lang ->
            @Suppress("DEPRECATION")
            compose.activity.resources.updateConfiguration(android.content.res.Configuration(compose.activity.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(lang)) }, compose.activity.resources.displayMetrics)
        }
    }

    private val ssh = Connection("work-ssh", "router", 22, PortKind.SSH, customName = "Work SSH")
    private val tunnel = Tunnel("router", 22, "synthetic", 40022, VisitorState(TunnelStatus.DIRECT))
    private val rows = connectionRows("work", listOf(ssh), TunnelsState(tunnels = listOf(tunnel)))
    private val groups = groupByDevice(rows, emptyMap())
    private val devices = listOf(DeviceGroup("work", "router", null, emptyList()), DeviceGroup("home", "router", null, emptyList()))
    private val state = ConnectionsUiState(groups = groups, profileNames = mapOf("work" to "Work", "home" to "Home"), devices = devices, loaded = true)
    private var added: String? = null
    private var stopped = emptySet<TunnelRef>()
    private var onlyOn by mutableStateOf(false)
    private var filesOpened = 0

    private fun shot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { shot ->
            val args = InstrumentationRegistry.getArguments()
            val suffix = "${args.getString("chatLocale") ?: "en"}-${args.getString("composerOrientation") ?: "portrait"}-font${args.getString("composerFontScale") ?: "1.0"}"
            File(compose.activity.getExternalFilesDir(null), "$name-$suffix.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
        }
    }

    private fun host() = compose.setContent {
        FrpJumpTheme {
            val nav = rememberNavController()
            val ui = remember { ConnectionUi() }
            val actions = RowActions({}, {}, {}, {}, {}, {}, {}, { filesOpened++; nav.navigate("files") }, {}, {}, {}, {}, { false }, {}, {})
            NavHost(nav, Routes.DEVICES) {
                composable(Routes.DEVICES) {
                    Column {
                        Button(onClick = { nav.openDeviceTunnels("work", "router") }) { Text("Work device") }
                        Button(onClick = { nav.openDeviceTunnels("home", "router") }) { Text("Home device") }
                    }
                }
                composable(Routes.CONNECTIONS) { entry ->
                    val request by entry.savedStateHandle.getStateFlow<ArrayList<String>?>(TUNNEL_SCOPE, null).collectAsStateWithLifecycle()
                    ConnectionsContent(state, emptyList(), emptyMap(), ui, actions, request, { entry.savedStateHandle[TUNNEL_SCOPE] = null }, onlyOn, { onlyOn = it }, {}, { it() },
                        { stopped = it }, {}, { added = it.profile })
                }
                composable("files") { Button(onClick = { nav.popBackStack() }) { Text("Back from Files") } }
            }
        }
    }

    @Test fun selectedDeviceAndFilesBackKeepExactScope() {
        host()
        compose.onNodeWithText("Work device").performClick()
        compose.onNodeWithText("Work SSH").assertIsDisplayed()
        compose.onNodeWithText("Home").assertDoesNotExist()
        shot("unified-tunnels-screen")
        compose.onNodeWithTag("card_menu").performClick()
        compose.onNodeWithText(compose.activity.getString(ru.adsrv.frpjump.R.string.files)).assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("card_files").performClick()
        compose.onNodeWithText("Back from Files").performClick()
        compose.onNodeWithText("Work SSH").assertIsDisplayed()
        assertEquals(1, filesOpened)
        compose.onNodeWithTag("tunnel_card_work-ssh").performTouchInput { longClick() }
        compose.onNodeWithTag("tunnels_selection").assertIsDisplayed()
        shot("unified-tunnels-selection")
        compose.onNodeWithTag("tunnels_stop_selected").performClick()
        compose.runOnIdle { assertEquals(setOf(TunnelRef("work", "router", 22)), stopped) }
    }

    @Test fun searchAndActiveFilterKeepScopedStopAvailable() {
        host()
        compose.onNodeWithText("Work device").performClick()
        compose.onNodeWithTag(TUNNELS_SEARCH_TAG).performClick()
        compose.onNodeWithTag(TUNNELS_SEARCH_FIELD_TAG).performTextInput("SSH")
        compose.onNodeWithTag(TUNNELS_FILTER_TAG).performClick()
        compose.onNodeWithTag(TUNNELS_STOP_ALL_TAG).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(true, onlyOn)
            assertEquals(setOf(TunnelRef("work", "router", 22)), stopped)
        }
    }

    @Test fun emptySelectedDeviceOffersOneAddAction() {
        host()
        compose.onNodeWithText("Home device").performClick()
        compose.onNodeWithText("Work SSH").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(ru.adsrv.frpjump.R.string.new_connection)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("home", added) }
        shot("unified-tunnels-empty-device")
    }
}
