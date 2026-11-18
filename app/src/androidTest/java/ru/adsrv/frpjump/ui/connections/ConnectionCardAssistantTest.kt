package ru.adsrv.frpjump.ui.connections

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.VisitorState
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.ui.FrpJumpTheme
import java.io.File

/** The connection card's assistant mark (a calm line) and its sheet with "Ask again", and the card's round buttons: shown only while the grant exists; synthetic data only. */
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

    private val up = row.copy(tunnel = Tunnel("synthetic-device", 22, "g1", 40022, VisitorState(TunnelStatus.RELAYED)))
    private val chip = "connection_assistant_chip"
    private val CMD = setOf(ru.adsrv.frpjump.assistant.AssistantToolId.SSH_COMMAND)
    private val INPUT = setOf(ru.adsrv.frpjump.assistant.AssistantToolId.TERMINAL_INPUT)
    private val BOTH = CMD + INPUT

    // Inside the system bars, as a screen is (the test activity draws under them).
    private fun card(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.systemBarsPadding()) { content() } } }

    @Test fun theMarkIsACalmLineThatOpensTheSheetAndAskAgainEndsBothLifetimes() {
        var mark by mutableStateOf<AssistantMark?>(AssistantMark(sessionTools = CMD, rememberedAt = null))
        var forgotten = 0
        card { ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions, assistant = mark, onForgetAssistant = { forgotten++; mark = null }) }
        compose.onNodeWithTag(chip, useUnmergedTree = true).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(text(R.string.connection_assistant_chip_session), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.connection_assistant_chip_description, "\u2068Pi shell\u2069", text(R.string.connection_assistant_chip_session))).assertIsDisplayed()
        shot("connection-card-assistant-mark")
        compose.onNodeWithTag(chip, useUnmergedTree = true).performClick(); compose.waitForIdle()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_title, "\u2068Pi shell\u2069")).assertExists()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_session)).assertExists()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_passwords)).assertExists()
        compose.onNodeWithTag("connection_assistant_close").assertExists()
        shot("connection-card-assistant-sheet")
        compose.onNodeWithTag("connection_assistant_ask_again").performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(1, forgotten)
        compose.onNodeWithTag(chip, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_passwords)).assertDoesNotExist()
    }

    @Test fun closeKeepsTheGrantAndNoMarkWithoutOne() {
        var forgotten = 0
        card { ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions, assistant = AssistantMark(CMD, null), onForgetAssistant = { forgotten++ }) }
        compose.onNodeWithTag(chip, useUnmergedTree = true).performClick(); compose.waitForIdle()
        compose.onNodeWithTag("connection_assistant_close").performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(0, forgotten)
        compose.onNodeWithTag(chip, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_passwords)).assertDoesNotExist()
    }

    @Test fun noMarkWithoutAGrant() {
        card { ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions) }
        compose.onNodeWithTag(chip, useUnmergedTree = true).assertDoesNotExist()
    }

    private val dateMillis = java.time.LocalDate.of(2026, 11, 13).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun date(millis: Long, skeleton: String): String = compose.activity.resources.configuration.locales[0].let { locale ->
        java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))
    }

    @Test fun aRememberedPermissionSaysForThisConnectionWithTheDateAndBothToolsWhenCovered() {
        card { ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions, assistant = AssistantMark(sessionTools = BOTH, rememberedAt = dateMillis, rememberedTools = BOTH)) }
        compose.onNodeWithText(text(R.string.connection_assistant_chip_connection), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.connection_assistant_chip_session), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(chip, useUnmergedTree = true).performClick(); compose.waitForIdle()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_session_both)).assertExists()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_connection_both, date(dateMillis, "dMMM").trimEnd('.'))).assertExists()
        shot("connection-card-assistant-sheet-both")
    }

    @Test fun aTypingOnlyGrantSaysTypingIntoTabsNotCommands() {
        card { ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions, assistant = AssistantMark(sessionTools = INPUT, rememberedAt = dateMillis, rememberedTools = INPUT)) }
        compose.onNodeWithTag(chip, useUnmergedTree = true).performClick(); compose.waitForIdle()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_session_input)).assertExists()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_connection_input, date(dateMillis, "dMMM").trimEnd('.'))).assertExists()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_session)).assertDoesNotExist()
        shot("connection-card-assistant-sheet-typing")
    }

    @Test fun aDateFromAnotherYearCarriesTheYear() {
        val old = java.time.LocalDate.of(java.time.LocalDate.now().year - 1, 11, 13).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        card { ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions, assistant = AssistantMark(null, old, CMD)) }
        compose.onNodeWithTag(chip, useUnmergedTree = true).performClick(); compose.waitForIdle()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_connection, date(old, "dMMMy").trimEnd('.'))).assertExists()
    }

    @Test fun theSheetAndTheMarkGoWhenTheGrantEndsWhileItIsOpen() {
        var mark by mutableStateOf<AssistantMark?>(AssistantMark(CMD, dateMillis, CMD))
        card { ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions, assistant = mark) }
        compose.onNodeWithTag(chip, useUnmergedTree = true).performClick(); compose.waitForIdle()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_passwords)).assertExists()
        mark = null; compose.waitForIdle()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_passwords)).assertDoesNotExist()
        compose.onNodeWithTag(chip, useUnmergedTree = true).assertDoesNotExist()
        mark = AssistantMark(CMD, null); compose.waitForIdle()
        compose.onNodeWithText(text(R.string.connection_assistant_sheet_passwords)).assertDoesNotExist()
    }

    @Test fun aDownCardOffersOnlyPowerOnAndMenuAndAnUpCardAlsoItsMainAction() {
        card { androidx.compose.foundation.layout.Column {
            ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions, assistant = AssistantMark(CMD, null))
        } }
        compose.onNodeWithTag("card_on", useUnmergedTree = true).assertIsDisplayed().assertWidthIsAtLeast(44.dp).assertHeightIsAtLeast(44.dp)
        compose.onNodeWithTag("card_menu", useUnmergedTree = true).assertIsDisplayed().assertWidthIsAtLeast(44.dp)
        compose.onNodeWithTag("card_main", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("card_off", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.connect_named, "Pi shell")).assertIsDisplayed()
        shot("connection-card-down")
    }

    @Test fun anUpSshCardHasTerminalPowerOffAndMenuWithTheSessionCountAndNamedActions() {
        card { ConnectionCard(up, sshMethod = null, sessions = SessionCount(2, 0), menuOpen = false, onMenu = {}, actions = actions, assistant = AssistantMark(null, dateMillis, BOTH)) }
        compose.onNodeWithTag("card_main", useUnmergedTree = true).assertIsDisplayed().assertHeightIsAtLeast(44.dp)
        compose.onNodeWithTag("card_off", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("card_menu", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("card_on", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.main_action_named, text(R.string.terminal_count, 2), "Pi shell")).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.disconnect_named, "Pi shell")).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.connection_actions, "Pi shell")).assertIsDisplayed()
        shot("connection-card-up")
    }

    @Test fun aCardBeingConnectedOffersOnlyADisabledPowerOnAndMenu() {
        card { ConnectionCard(row.copy(pending = PendingOp.CONNECTING), sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions) }
        compose.onNodeWithTag("card_on", useUnmergedTree = true).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.connect_named, "Pi shell")).assertIsDisplayed()
        compose.onNodeWithTag("card_menu", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("card_main", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("card_off", useUnmergedTree = true).assertDoesNotExist()
    }
}
