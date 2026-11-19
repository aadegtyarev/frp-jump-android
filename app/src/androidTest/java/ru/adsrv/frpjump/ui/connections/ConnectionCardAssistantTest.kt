package ru.adsrv.frpjump.ui.connections

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
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

/** The connection card's assistant mark (a calm line) and its sheet with "Ask again", and the card's switch and main-action link; the mark is shown only while the grant exists; synthetic data only. */
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
    private fun card(mode: ru.adsrv.frpjump.settings.ThemeMode = ru.adsrv.frpjump.settings.ThemeMode.SYSTEM, content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { FrpJumpTheme(mode) { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.systemBarsPadding()) { content() } } }

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

    @Test fun aDownCardHasAnOffSwitchAndMenuAndNoMainAction() {
        var connected = 0
        val counting = RowActions({ connected++ }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { false }, {}, {})
        card { androidx.compose.foundation.layout.Column {
            ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = counting, assistant = AssistantMark(CMD, null))
        } }
        val toggle = compose.onNodeWithTag("card_switch", useUnmergedTree = true).assertIsDisplayed().assertIsOff().assertIsEnabled()
        // The M3 Switch draws 52x32 dp, and the node's own bounds are that; its touch target is 48 dp tall (minimumInteractiveComponentSize),
        // so the check is a tap 22 dp above the centre, outside the drawn 32 dp and inside the 48.
        toggle.performTouchInput { click(Offset(center.x, center.y - 22.dp.toPx())) }
        compose.waitForIdle()
        org.junit.Assert.assertEquals("a tap 22 dp above the switch's centre turns it on", 1, connected)
        toggle.performTouchInput { click(Offset(center.x, center.y + 22.dp.toPx())) }
        compose.waitForIdle()
        org.junit.Assert.assertEquals("a tap 22 dp below the switch's centre turns it on", 2, connected)
        compose.onNodeWithTag("card_menu", useUnmergedTree = true).assertIsDisplayed().assertWidthIsAtLeast(44.dp)
        compose.onNodeWithTag("card_main", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("card_on", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("card_off", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.tunnel_switch, "Pi shell")).assertIsDisplayed()
        // The assistant's mark alone makes the bottom row.
        compose.onNodeWithTag(chip, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.state_off), useUnmergedTree = true).assertIsDisplayed()
        shot("connection-card-down")
    }

    @Test fun anUpSshCardHasAnOnSwitchTheTerminalLinkWithTheSessionCountAndMenu() {
        card { ConnectionCard(up, sshMethod = null, sessions = SessionCount(2, 0), menuOpen = false, onMenu = {}, actions = actions, assistant = AssistantMark(null, dateMillis, BOTH)) }
        compose.onNodeWithTag("card_switch", useUnmergedTree = true).assertIsDisplayed().assertIsOn().assertIsEnabled()
        compose.onNodeWithTag("card_main", useUnmergedTree = true).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("card_menu", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(chip, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.terminal_count, 2) + "\u00A0\u203A", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.main_action_named, text(R.string.terminal_count, 2), "Pi shell")).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.tunnel_switch, "Pi shell")).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.connection_actions, "Pi shell")).assertIsDisplayed()
        shot("connection-card-up")
    }

    @Test fun theSwitchConnectsAndDisconnectsAndTheLinkDoesTheMainAction() {
        var connects = 0; var disconnects = 0; var mains = 0
        val counting = RowActions({ connects++ }, { mains++ }, {}, { disconnects++ }, {}, {}, {}, {}, {}, {}, {}, {}, { false }, {}, {})
        var shown by mutableStateOf(row)
        card { ConnectionCard(shown, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = counting) }
        compose.onNodeWithTag("card_switch", useUnmergedTree = true).performClick(); compose.waitForIdle()
        assertEquals(1, connects); assertEquals(0, disconnects)
        shown = up; compose.waitForIdle()
        compose.onNodeWithTag("card_main", useUnmergedTree = true).performClick(); compose.waitForIdle()
        assertEquals(1, mains)
        compose.onNodeWithTag("card_switch", useUnmergedTree = true).performClick(); compose.waitForIdle()
        assertEquals(1, disconnects); assertEquals(1, connects)
    }

    @Test fun aCardBeingConnectedShowsAnOnSwitchThatIsDisabledAndTheStartingState() {
        card { ConnectionCard(row.copy(pending = PendingOp.CONNECTING), sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions) }
        compose.onNodeWithTag("card_switch", useUnmergedTree = true).assertIsDisplayed().assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.state_connecting), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("card_menu", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("card_main", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aCardBeingDisconnectedShowsAnOffSwitchThatIsDisabled() {
        card { ConnectionCard(up.copy(pending = PendingOp.DISCONNECTING), sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions) }
        compose.onNodeWithTag("card_switch", useUnmergedTree = true).assertIsDisplayed().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.state_disconnecting), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("card_main", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aDownCardOnAnOfflineDeviceHasADisabledSwitchAndSaysSo() {
        card { ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions, offline = true) }
        compose.onNodeWithTag("card_switch", useUnmergedTree = true).assertIsDisplayed().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.state_device_offline), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("card_menu", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun aCardWithNoOwnNameTitlesItselfWithTheKindAndMutesTheDetails() {
        val unnamed = row.copy(connection = row.connection.copy(customName = null, user = "pi"))
        card { ConnectionCard(unnamed, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions) }
        compose.onNodeWithText("SSH", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.card_port_ssh_key, 22, "pi"), useUnmergedTree = true).assertIsDisplayed()
    }

    /** An up SSH card through the server with one session: the link and the switch, the state and the address do not leave a dot at a line end. */
    private fun upSshCardWithASession(mode: ru.adsrv.frpjump.settings.ThemeMode, name: String) {
        val relayed = up.copy(tunnel = Tunnel("synthetic-device", 22, "g1", 40001, VisitorState(TunnelStatus.RELAYED, directFailed = true), pinned = true))
        card(mode) { ConnectionCard(relayed, sshMethod = null, sessions = SessionCount(1, 0), menuOpen = false, onMenu = {}, actions = actions) }
        compose.onNodeWithTag("card_main", useUnmergedTree = true).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("card_switch", useUnmergedTree = true).assertIsDisplayed().assertIsOn()
        shot(name)
    }

    /** The key the open session logged in with: its mark and name next to the sessions pill, on the card only while the session is open. */
    private fun cardWithAKey(mode: ru.adsrv.frpjump.settings.ThemeMode, name: String) {
        card(mode) {
            androidx.compose.foundation.layout.Column {
                ConnectionCard(up, sshMethod = null, sessions = SessionCount(1, 0, listOf(ru.adsrv.frpjump.ssh.SshKeySlot.PHONE)), menuOpen = false, onMenu = {}, actions = actions)
                ConnectionCard(up, sshMethod = null, sessions = SessionCount(2, 0, listOf(ru.adsrv.frpjump.ssh.SshKeySlot.PHONE, ru.adsrv.frpjump.ssh.SshKeySlot.IMPORTED)), menuOpen = false, onMenu = {}, actions = actions)
                ConnectionCard(up, sshMethod = null, sessions = SessionCount(1, 0), menuOpen = false, onMenu = {}, actions = actions)
            }
        }
        compose.onAllNodesWithTag("card_key_mark", useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithTag("key_mark_phone", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithTag("key_mark_imported", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(text(R.string.key_used_phone), useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithContentDescription(text(R.string.key_used_imported), useUnmergedTree = true).assertCountEquals(1)
        shot(name)
    }

    @Test fun aCardWithOpenKeySessionsShowsTheKeyMarksLightTheme() = cardWithAKey(ru.adsrv.frpjump.settings.ThemeMode.LIGHT, "connection-card-key-light")

    @Test fun aCardWithOpenKeySessionsShowsTheKeyMarksDarkTheme() = cardWithAKey(ru.adsrv.frpjump.settings.ThemeMode.DARK, "connection-card-key-dark")

    @Test fun aCardWithoutAKeySessionShowsNoKeyMark() {
        // A password session, a session whose login is not known yet, and no session at all.
        card {
            androidx.compose.foundation.layout.Column {
                ConnectionCard(up, sshMethod = null, sessions = SessionCount(1, 0), menuOpen = false, onMenu = {}, actions = actions)
                ConnectionCard(up, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions)
                ConnectionCard(row, sshMethod = null, sessions = SessionCount.NONE, menuOpen = false, onMenu = {}, actions = actions)
            }
        }
        compose.onAllNodesWithTag("card_key_mark", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun anUpSshCardWithASessionLightTheme() = upSshCardWithASession(ru.adsrv.frpjump.settings.ThemeMode.LIGHT, "connection-card-up-session-light")

    @Test fun anUpSshCardWithASessionDarkTheme() = upSshCardWithASession(ru.adsrv.frpjump.settings.ThemeMode.DARK, "connection-card-up-session-dark")
}
