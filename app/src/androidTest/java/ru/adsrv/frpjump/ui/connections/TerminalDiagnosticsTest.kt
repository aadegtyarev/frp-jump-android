package ru.adsrv.frpjump.ui.connections

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.ui.settings.SettingsScreen
import ru.adsrv.frpjump.ui.settings.SettingsViewModel
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
import ru.adsrv.frpjump.ssh.diagnostics.TerminalMetrics
import ru.adsrv.frpjump.ssh.diagnostics.TerminalSessionReport
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FrpJumpTheme

@RunWith(AndroidJUnit4::class)
class TerminalDiagnosticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val reports = mutableStateOf(emptyList<TerminalSessionReport>())
    private var at = 0L

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

    private fun start() = compose.setContent {
        FrpJumpTheme {
            Surface(color = AppTheme.colors.card) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) { TerminalDiagnostics(reports.value) }
            }
        }
    }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun line() = compose.onNodeWithTag("terminal-diagnostics-line")
    private fun model() = TerminalMetrics(1000) { at }.apply {
        setObserving(true)
        val epoch = connectionOpened()
        received(epoch, 1024); written(epoch, 512)
        probeReplied(token()!!, 24_000_000)
        bufferState(743, false)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching { instrumentation.uiAutomation.waitForIdle(350, 3000) }
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        val c = compose.activity.resources.configuration
        File(instrumentation.targetContext.externalCacheDir, "$name-${c.orientation}-${c.fontScale}.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    @Test fun historyChoicesAreReachableAndPersistThroughTheRealSettingsViewModel() {
        // Clean test emulator only: restore the sole preference this test changes.
        val app = compose.activity.application as FrpJumpApp
        val before = app.settings.settings.value.terminalHistoryRows
        try {
            app.settings.update { it.copy(terminalHistoryRows = 1000) }
            lateinit var vm: SettingsViewModel
            compose.activityRule.scenario.onActivity { activity ->
                vm = ViewModelProvider(activity, viewModelFactory { initializer { SettingsViewModel(app) } })[SettingsViewModel::class.java]
            }
            compose.setContent {
                FrpJumpTheme { SettingsScreen(vm, profile = null, forget = null, onEditProfile = {}, onForget = {}, onBack = null, onChat = {}) }
            }
            compose.onNodeWithText(text(R.string.terminal_history_rows)).performScrollTo().performClick()
            for (rows in listOf(500, 1000, 3000, 10000)) {
                compose.onNode(hasText(rows.toString()) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertExists()
            }
            compose.onNode(hasText("500") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performScrollTo().performClick()
            compose.waitUntil(5000) { app.settings.settings.value.terminalHistoryRows == 500 }
            compose.onNodeWithText(text(R.string.terminal_history_rows)).performScrollTo().performClick()
            compose.onNode(hasText("10000") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performScrollTo().performClick()
            compose.waitUntil(5000) { app.settings.settings.value.terminalHistoryRows == 10000 }
        } finally { app.settings.update { it.copy(terminalHistoryRows = before) } }
    }

    @Test fun unavailableIsNotZeroAndDetailsExplainMissingSessions() {
        start()
        compose.onNodeWithText("SSH —", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("↓ —", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("↑ —", useUnmergedTree = true).assertIsDisplayed()
        line().performClick()
        compose.onNodeWithText(text(R.string.diagnostics_title)).assertExists()
        compose.onNodeWithText(text(R.string.diagnostics_no_sessions)).performScrollTo().assertIsDisplayed()
    }

    @Test fun detailsTrackClosingAndRemovingSessionWithoutRetainingItsData() {
        val metrics = model()
        at = 1_000_000_000
        reports.value = listOf(TerminalSessionReport(1, metrics.snapshot()))
        start()
        line().performClick()
        compose.onNodeWithText(text(R.string.diagnostics_response)).performScrollTo().assertIsDisplayed()
        capture("diagnostics-details")
        metrics.connectionClosed()
        compose.runOnIdle { reports.value = listOf(TerminalSessionReport(1, metrics.snapshot())) }
        val closed = compose.activity.getString(R.string.diagnostics_session, 1, text(R.string.diagnostics_disconnected))
        compose.onNodeWithText(closed).assertExists()
        compose.runOnIdle { reports.value = emptyList() }
        compose.onNodeWithText(closed).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.diagnostics_no_sessions)).performScrollTo().assertIsDisplayed()
    }

    @Test fun compactValuesStayWithinTheirTappableLineAtRealDisplayConfiguration() {
        val metrics = model()
        at = 1_000_000_000
        reports.value = listOf(TerminalSessionReport(1, metrics.snapshot()))
        start()
        val bounds = line().fetchSemanticsNode().boundsInRoot
        val response = "SSH " + compose.activity.getString(R.string.diagnostics_ms, "24")
        val node = compose.onNodeWithText(response, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        assertTrue(node.boundsInRoot.left >= bounds.left - 1)
        assertTrue(node.boundsInRoot.right <= bounds.right + 1)
        capture("diagnostics-line")
        line().performClick()
        compose.onNodeWithText(text(R.string.diagnostics_draw)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.diagnostics_limits)).performScrollTo().assertIsDisplayed()
    }
}
