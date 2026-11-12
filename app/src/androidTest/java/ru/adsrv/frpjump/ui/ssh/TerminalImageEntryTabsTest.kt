package ru.adsrv.frpjump.ui.ssh

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
import ru.adsrv.frpjump.ui.notice
import ru.adsrv.frpjump.notices.NoticeKind
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled

/**
 * The photo-picker entry points on the real TerminalScreen with three synthetic tabs. An Open tab needs an SSH server, so the
 * screen's [ImageEntryHooks] stand in for "Open" and for the system picker and say which tab asked.
 */
@RunWith(AndroidJUnit4::class)
class TerminalImageEntryTabsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = SupervisorJob().apply { cancel() }
    private val scope = CoroutineScope(root + Dispatchers.Main)
    private var sessions: SshSessions? = null
    private val picked = mutableListOf<String>()
    @Volatile private var open = true

    private class Memory : TextStorage {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
        override fun delete() { text = null }
    }
    private val unusedBox = object : SecretBox {
        override fun seal(plain: String): String = error("No credentials in this test")
        override fun open(sealed: String): String = error("No credentials in this test")
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
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    @After fun cleanup() {
        compose.runOnIdle { (compose.activity.application as FrpJumpApp).visible.value = false; sessions?.closeAll(); compose.activity.getSystemService(android.content.ClipboardManager::class.java).clearPrimaryClip() }
        root.cancel()
    }

    private fun settleWindow() {
        compose.waitForIdle()
        runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350, 3_000) }
        compose.waitForIdle()
    }

    private fun start() {
        val app = compose.activity.application as FrpJumpApp
        val env = SshEnvironment(SshKeyStore(Memory(), unusedBox), SshPasswords(Memory(), unusedBox), { "synthetic" }, TmuxNames(Memory()))
        lateinit var registry: SshSessions
        compose.runOnUiThread {
            registry = SshSessions(app, app.notices, env, flowOf(emptySet()), scope, hidden = app.pendingUndo.hidden)
            sessions = registry
            for (device in listOf("A", "B", "C")) registry.open("synthetic-profile", HostKeyStore(Memory()), device, 22, 9, "u", SshAuth.Key)
        }
        val all = OpenSessions(registry.through, registry::throughNow, flowOf(emptyList()), { emptyList() }, app.pendingUndo.hidden)
        val closer = SessionCloser(app.pendingUndo, closeTerminal = { registry.close(it) }, closeMqtt = {})
        val keyLogin = KeyLoginFlow(KeyInstaller(env, registry), scope)
        val snippets = SnippetStore(Memory(), Memory())
        val hooks = ImageEntryHooks(isOpen = { open }, picked = { picked += it.device })
        compose.setContent {
            FrpJumpTheme {
                TerminalScreen(registry, all, snippets, app.pendingUndo, closer, emptyMap(), initialId = 1,
                    onBack = {}, onOpenFiles = {}, rememberLogin = { _, _ -> }, keyLogin = keyLogin, keyLoginTarget = { null }, imageHooks = hooks)
            }
        }
        compose.waitForIdle()
    }

    private fun select(device: String) {
        val label = tabLabel("u", device, 22, 1) + ", " + compose.activity.getString(R.string.terminal_state_connecting)
        compose.onNodeWithContentDescription(label).performScrollTo().performClick().assertIsSelected()
        settleWindow()
    }

    private val pasteKey get() = compose.onNodeWithContentDescription(compose.activity.getString(R.string.terminal_paste))
    private val imageButton get() = compose.onNodeWithTag("terminal_text_image")

    @Test fun theLongPressOnPasteAsksForTheTabShownNowAfterEverySwitch() {
        start()
        val order = listOf("A", "B", "C", "B", "A")
        for (device in order) {
            select(device)
            pasteKey.performTouchInput { longClick() }
            compose.waitForIdle()
            assertEquals(device, picked.last())
        }
        assertEquals(order, picked)
    }

    @Test fun theEditorsImageButtonAsksForTheTabShownNowAndLeavesTheDraftAlone() {
        start()
        for (device in listOf("A", "B", "C")) {
            select(device)
            compose.onNodeWithTag("terminal_keyboard").performTouchInput { longClick() }
            compose.onNodeWithTag("terminal_text_editor").performTextInput("draft $device")
            imageButton.performClick()
            compose.waitForIdle()
            assertEquals(device, picked.last())
            compose.onNodeWithTag("terminal_text_editor").assertTextEquals("draft $device")
            // Expanded: the same button, the same tab, the same draft.
            compose.onNodeWithTag("terminal_text_expand").performClick()
            compose.waitForIdle()
            imageButton.performClick()
            compose.waitForIdle()
            assertEquals(device, picked.last())
            compose.onNodeWithTag("terminal_text_editor").assertTextEquals("draft $device")
            compose.onNodeWithTag("terminal_text_expand").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("terminal_mode_keys").performClick()
            compose.waitForIdle()
        }
        assertEquals(6, picked.size)
        compose.runOnIdle { assertEquals("draft B", sessions!!.drafts.state.value.getValue(2).text) }
    }

    @Test fun anUnavailableTabAsksForNothingFromAnyEntry() {
        start()
        open = false
        compose.runOnUiThread { compose.activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("synthetic", "text")) }
        compose.waitForIdle()
        pasteKey.performTouchInput { longClick() }
        compose.onNodeWithTag("terminal_keyboard").performTouchInput { longClick() }
        imageButton.assertIsNotEnabled().performClick()
        compose.waitForIdle()
        assertTrue(picked.isEmpty())
    }

    @Test fun anImageNoticeIsSeenOverTheExpandedEditor() {
        start()
        compose.onNodeWithTag("terminal_keyboard").performTouchInput { longClick() }
        compose.onNodeWithTag("terminal_text_expand").performClick()
        compose.waitForIdle()
        // A notice is shown only while the app is in the foreground (MainActivity says so; this test has no MainActivity).
        compose.runOnUiThread { (compose.activity.application as FrpJumpApp).visible.value = true; compose.activity.notice("synthetic image notice", NoticeKind.WARN) }
        compose.waitForIdle()
        // Under the dialog the activity has no host in this test, so any node found is the dialog's own layer.
        assertTrue("the notice is shown over the expanded editor", compose.onAllNodesWithText("synthetic image notice").fetchSemanticsNodes().isNotEmpty())
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { shot ->
            java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir, "entry-editor-expanded-notice.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
        }
    }
}
