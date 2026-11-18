package ru.adsrv.frpjump.ui.connections

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.SessionCount
import ru.adsrv.frpjump.ui.FrpJumpTheme
import java.io.File

/** The connection card's "Assistant: no questions, for the tunnel's lifetime" line with Forget: shown only while the grant exists; synthetic data only. */
@RunWith(AndroidJUnit4::class)
class ConnectionCardAssistantTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val runArgs get() = InstrumentationRegistry.getArguments()

    /** The run's orientation and language (the same arguments the other suites read); the font scale is the device's. */
    @org.junit.Before fun display() {
        val landscape = runArgs.getString("composerOrientation") == "landscape"
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val expected = if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
        val until = android.os.SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && android.os.SystemClock.uptimeMillis() < until) android.os.SystemClock.sleep(50)
        runArgs.getString("chatLocale")?.let { lang ->
            @Suppress("DEPRECATION") compose.activity.resources.updateConfiguration(android.content.res.Configuration(compose.activity.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(lang)) }, compose.activity.resources.displayMetrics)
        }
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
    private val row = ConnectionRow("synthetic-profile", Connection("c1", "synthetic-device", 22, PortKind.SSH, customName = "Pi shell", user = "pi"), saved = true, tunnel = null, pending = null, error = null)
    private val actions = RowActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { false }, {}, {})

    private fun shot(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(300); compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(compose.activity.getExternalFilesDir(null), "$name-${runArgs.getString("chatLocale") ?: "en"}-${runArgs.getString("composerOrientation") ?: "portrait"}-font${runArgs.getString("composerFontScale") ?: "1.0"}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    @Test fun theLineAppearsWithTheGrantAndForgetRemovesItAndItsTargetAndLabelAreRight() {
        var granted by mutableStateOf(true)
        var forgotten = 0
        compose.setContent {
            FrpJumpTheme {
                ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions,
                    assistantGrant = granted, onForgetAssistant = { forgotten++; granted = false })
            }
        }
        compose.onNodeWithTag("connection_assistant_grant", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("connection_assistant_forget", useUnmergedTree = true).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(text(R.string.connection_assistant_grant), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.connection_assistant_forget_description, "Pi shell"), useUnmergedTree = true).assertIsDisplayed()
        shot("connection-card-assistant-grant")
        compose.onNodeWithTag("connection_assistant_forget", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(1, forgotten)
        compose.onNodeWithTag("connection_assistant_grant", useUnmergedTree = true).assertDoesNotExist()
        shot("connection-card-assistant-forgotten")
    }

    @Test fun noLineWithoutAGrant() {
        compose.setContent { FrpJumpTheme { ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions) } }
        compose.onNodeWithTag("connection_assistant_grant", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("connection_assistant_forget", useUnmergedTree = true).assertDoesNotExist()
    }

    private val dateMillis = java.time.LocalDate.of(2026, 11, 13).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun date(): String = compose.activity.resources.configuration.locales[0].let { locale ->
        java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale).format(java.time.Instant.ofEpochMilli(dateMillis).atZone(java.time.ZoneId.systemDefault()))
    }

    @Test fun theRememberedLineSaysSoWithTheDateAndForgetIsReadWithTheConnectionsName() {
        var remembered by mutableStateOf<Long?>(dateMillis); var granted by mutableStateOf(true); var forgotten = 0
        compose.setContent {
            FrpJumpTheme {
                ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions,
                    assistantGrant = granted, assistantRemembered = remembered, onForgetAssistant = { forgotten++; granted = false; remembered = null })
            }
        }
        compose.onNodeWithTag("connection_assistant_grant", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.connection_assistant_remembered, date()), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.connection_assistant_grant), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("connection_assistant_forget", useUnmergedTree = true).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(text(R.string.connection_assistant_forget_description, "Pi shell"), useUnmergedTree = true).assertIsDisplayed()
        shot("connection-card-assistant-remembered")
        compose.onNodeWithTag("connection_assistant_forget", useUnmergedTree = true).performClick(); compose.waitForIdle()
        assertEquals(1, forgotten); compose.onNodeWithTag("connection_assistant_grant", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aSessionGrantAloneKeepsItsOwnTextAndTheLineWrapsWithoutCuttingForget() {
        compose.setContent { FrpJumpTheme { ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions, assistantGrant = true, assistantRemembered = null) } }
        compose.onNodeWithText(text(R.string.connection_assistant_grant), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("connection_assistant_forget", useUnmergedTree = true).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        shot("connection-card-assistant-session")
    }

    @Test fun aDateFromAnotherYearCarriesTheYear() {
        val old = java.time.LocalDate.of(java.time.LocalDate.now().year - 1, 11, 13).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        compose.setContent { FrpJumpTheme { ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions, assistantGrant = true, assistantRemembered = old) } }
        val locale = compose.activity.resources.configuration.locales[0]
        val expected = java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMy"), locale).format(java.time.Instant.ofEpochMilli(old).atZone(java.time.ZoneId.systemDefault()))
        compose.onNodeWithText(text(R.string.connection_assistant_remembered, expected), useUnmergedTree = true).assertIsDisplayed()
    }
}
