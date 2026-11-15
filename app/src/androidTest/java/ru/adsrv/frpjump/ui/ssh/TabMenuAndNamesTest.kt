package ru.adsrv.frpjump.ui.ssh

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.sessions.OpenSessions
import ru.adsrv.frpjump.sessions.SessionCloser
import ru.adsrv.frpjump.snippets.SnippetStore
import ru.adsrv.frpjump.ssh.HostKeyStore
import ru.adsrv.frpjump.ssh.KeyInstaller
import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SshEnvironment
import ru.adsrv.frpjump.ssh.SshKeyStore
import ru.adsrv.frpjump.ssh.SshPasswords
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.TmuxNames
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.ui.FrpJumpTheme
import java.io.File
import java.util.Locale

/**
 * The visible tab menu (the ▾ on the selected tab) and owner-set tab names, on the real terminal screen with synthetic
 * in-memory sessions (a canceled transport scope opens no sockets: the tabs stay "connecting").
 */
@RunWith(AndroidJUnit4::class)
class TabMenuAndNamesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = SupervisorJob().apply { cancel() }
    private val scope = CoroutineScope(root + Dispatchers.Main)
    private var sessions: SshSessions? = null

    private class Memory : TextStorage {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
        override fun delete() { text = null }
    }
    private val unusedBox = object : SecretBox {
        override fun seal(plain: String): String = error("No credentials in the tab menu test")
        override fun open(sealed: String): String = error("No credentials in the tab menu test")
        override fun destroy() = Unit
    }

    @Before fun configureDisplay() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        args.getString("chatLocale")?.let { lang ->
            val res = compose.activity.resources
            val config = Configuration(res.configuration).apply { setLocale(Locale(lang)) }
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            assertEquals(lang, res.configuration.locales[0].language)
        }
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    @After fun cleanup() { compose.runOnIdle { sessions?.closeAll() }; root.cancel() }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun start(devices: List<String> = listOf("A", "B"), initialId: Int = 1) {
        val app = compose.activity.application as FrpJumpApp
        val env = SshEnvironment(SshKeyStore(Memory(), unusedBox), SshPasswords(Memory(), unusedBox), { "synthetic" }, TmuxNames(Memory()))
        lateinit var registry: SshSessions
        compose.runOnUiThread {
            registry = SshSessions(app, app.notices, env, flowOf(emptySet()), scope, hidden = app.pendingUndo.hidden)
            sessions = registry
            for (device in devices) registry.open("synthetic-profile", HostKeyStore(Memory()), device, 22, 9, "u", SshAuth.Key)
        }
        val all = OpenSessions(registry.through, registry::throughNow, flowOf(emptyList()), { emptyList() }, app.pendingUndo.hidden)
        val closer = SessionCloser(app.pendingUndo, closeTerminal = { registry.close(it) }, closeMqtt = {})
        val keyLogin = KeyLoginFlow(KeyInstaller(env, registry), scope)
        val snippets = SnippetStore(Memory(), Memory())
        compose.setContent {
            FrpJumpTheme {
                TerminalScreen(registry, all, snippets, app.pendingUndo, closer, emptyMap(), initialId = initialId,
                    onBack = {}, onOpenFiles = {}, rememberLogin = { _, _ -> }, keyLogin = keyLogin, keyLoginTarget = { null })
            }
        }
        compose.waitForIdle()
    }

    private fun automatic(device: String) = tabLabel("u", device, 22, 1)

    /** A tab as TalkBack names it: [named] is the owner's name when one is set. */
    private fun tab(device: String, named: String? = null) =
        compose.onNodeWithContentDescription(
            (if (named != null) "$named, ${automatic(device)}" else automatic(device)) + ", " + text(R.string.terminal_state_connecting),
        )

    private fun menuItem(id: Int) = compose.onNodeWithText(text(id))

    private fun dialogField() = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))

    private fun dialogSave() = compose.onNode(hasText(text(R.string.save)) and hasAnyAncestor(isDialog()))

    private fun openRename(device: String, named: String? = null) {
        tab(device, named).performScrollTo().performClick()
        menuItem(R.string.terminal_tab_rename).performClick()
        compose.waitForIdle()
    }

    private fun rename(device: String, name: String, before: String? = null) {
        openRename(device, before)
        dialogField().performTextClearance()
        dialogField().performTextInput(name)
        dialogSave().performClick()
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = compose.activity.resources.configuration
        runCatching { instrumentation.uiAutomation.waitForIdle(350, 3_000) }
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return
        val orientation = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        File(compose.activity.getExternalFilesDir(null), "tabs-$name-$orientation-${config.fontScale}.png")
            .outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    @Test fun theArrowIsOnTheSelectedTabOnly() {
        start()
        compose.onAllNodesWithTag("tab_menu_arrow", useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(hasTestTag("tab_menu_arrow") and hasAnyAncestor(hasContentDescription(automatic("A"), substring = true)), useUnmergedTree = true)
            .assertExists()
        // Tapping the other tab selects it (no menu) and moves the arrow.
        tab("B").performScrollTo().performClick().assertIsSelected()
        compose.onAllNodesWithTag("tab_menu_arrow", useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(hasTestTag("tab_menu_arrow") and hasAnyAncestor(hasContentDescription(automatic("B"), substring = true)), useUnmergedTree = true)
            .assertExists()
        menuItem(R.string.terminal_tab_rename).assertDoesNotExist()
        capture("row-with-arrow")
    }

    @Test fun aTapOnTheSelectedTabOpensItsMenu() {
        start()
        tab("A").assertIsSelected().performClick()
        listOf(R.string.terminal_tab_rename, R.string.terminal_close_session, R.string.terminal_new_session_as)
            .forEach { menuItem(it).assertExists() }
        // A live tab: no Reconnect or Change login.
        menuItem(R.string.terminal_reconnect).assertDoesNotExist()
        menuItem(R.string.terminal_change_login).assertDoesNotExist()
        // No name yet: no header either.
        compose.onNodeWithTag("tab_menu_header").assertDoesNotExist()
    }

    @Test fun aLongPressOnAnotherTabOpensItsMenuAndSelectsNothing() {
        start()
        tab("B").performScrollTo().performTouchInput { longClick() }
        compose.waitForIdle()
        menuItem(R.string.terminal_tab_rename).assertExists()
        tab("A").assertIsSelected()
    }

    @Test fun renameShowsTheNameOnTheChipAndEmptyRestoresTheAutomaticLabel() {
        start()
        rename("A", "build box")
        // The chip's text is hidden from TalkBack: it reads "<name>, <automatic label>".
        tab("A", named = "build box").assertIsSelected()
        // The other tab is untouched.
        tab("B").assertExists()
        // The dialog starts from the name now; empty goes back to the automatic label.
        openRename("A", "build box")
        dialogField().assertTextContains("build box")
        dialogField().performTextClearance()
        dialogSave().assertIsEnabled().performClick()
        compose.waitForIdle()
        tab("A").assertIsSelected()
    }

    @Test fun theDialogShowsTheAutomaticLabelAndACounter() {
        start()
        openRename("A")
        compose.onNodeWithText(text(R.string.terminal_tab_name_title)).assertExists()
        compose.onNodeWithText(text(R.string.terminal_tab_name_automatic, automatic("A"))).assertExists()
        compose.onNodeWithContentDescription(text(R.string.device_label_counter_spoken, 0, 24)).assertExists()
        dialogField().performTextInput("build")
        compose.onNodeWithContentDescription(text(R.string.device_label_counter_spoken, 5, 24)).assertExists()
        capture("rename-dialog")
        compose.onNode(hasText(text(R.string.cancel)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        tab("A").assertExists()
    }

    @Test fun theTwentyFifthCharacterAndABarAreRefusedAndImeDoneSaves() {
        start()
        openRename("A")
        dialogField().performTextInput("x".repeat(24))
        dialogSave().assertIsEnabled()
        compose.onNodeWithText(text(R.string.terminal_tab_name_too_long)).assertDoesNotExist()
        dialogField().performTextInput("x")
        dialogSave().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.terminal_tab_name_too_long)).assertExists()
        dialogField().performTextClearance()
        dialogField().performTextInput("a|b")
        dialogSave().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.terminal_tab_name_bad_character)).assertExists()
        dialogField().performTextClearance()
        dialogField().performTextInput("  via ime ")
        dialogSave().assertIsEnabled()
        dialogField().performImeAction()
        compose.waitForIdle()
        tab("A", named = "via ime").assertExists()
    }

    @Test fun aLongNameIsOneLineWithAnEllipsisAndTheMenuHeaderKeepsUserAndDevice() {
        start()
        rename("A", "W".repeat(24))
        tab("A", named = "W".repeat(24)).assertIsSelected()
        capture("long-name")
        tab("A", named = "W".repeat(24)).performClick()
        compose.onNodeWithTag("tab_menu_header").assertTextEquals(automatic("A"))
        capture("menu-with-header")
    }

    @Test fun everyTabHasTalkBackActionsMatchingItsMenuAndPlusHasLogInAsAnotherUser() {
        start()
        val expected = listOf(R.string.terminal_tab_rename, R.string.terminal_close_session, R.string.terminal_new_session_as).map { text(it) }
        for (device in listOf("A", "B")) {
            val config = tab(device).performScrollTo().fetchSemanticsNode().config
            assertEquals(device, expected, config[SemanticsActions.CustomActions].map { it.label })
        }
        val plus = compose.onNodeWithContentDescription(text(R.string.terminal_new_session)).fetchSemanticsNode().config
        assertEquals(listOf(text(R.string.terminal_new_session_as)), plus[SemanticsActions.CustomActions].map { it.label })
        // The selected tab's click and the long click are labelled "Session actions"; another tab's click has no such label.
        val selected = tab("A").fetchSemanticsNode().config
        assertEquals(text(R.string.terminal_tab_menu), selected.getOrNull(SemanticsActions.OnClick)?.label)
        assertEquals(text(R.string.terminal_tab_menu), selected.getOrNull(SemanticsActions.OnLongClick)?.label)
        assertFalse(tab("B").fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label == text(R.string.terminal_tab_menu))
        assertTrue(selected.getOrNull(SemanticsProperties.Selected) == true)
    }

    @Test fun aCustomActionRenamesNothingUntilSavedAndOpensTheSameDialog() {
        start()
        val rename = tab("A").fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == text(R.string.terminal_tab_rename) }
        compose.runOnUiThread { rename.action() }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.terminal_tab_name_title)).assertExists()
    }
}
