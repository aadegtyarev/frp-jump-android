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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.assertFalse
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

/** Real TerminalScreen and tabs, synthetic in-memory sessions; canceled transport scope opens no sockets. */
@RunWith(AndroidJUnit4::class)
class MultiSessionTextModeTest {
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
        override fun seal(plain: String): String = error("No credentials in multi-tab UI test")
        override fun open(sealed: String): String = error("No credentials in multi-tab UI test")
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

    @After fun cleanup() { compose.runOnIdle { sessions?.closeAll() }; root.cancel() }

    private fun start(initialId: Int = 1): SshSessions {
        val app = compose.activity.application as FrpJumpApp
        val env = SshEnvironment(SshKeyStore(Memory(), unusedBox), SshPasswords(Memory(), unusedBox),
            { "synthetic" }, TmuxNames(Memory()))
        lateinit var registry: SshSessions
        compose.runOnUiThread {
            registry = SshSessions(app, app.notices, env, flowOf(emptySet()), scope, hidden = app.pendingUndo.hidden)
            sessions = registry
            for (device in listOf("A", "B", "C")) {
                registry.open("synthetic-profile", HostKeyStore(Memory()), device, 22, 9, "u", SshAuth.Key)
            }
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
        return registry
    }

    private fun select(device: String) {
        val label = tabLabel("u", device, 22, 1) + ", " + compose.activity.getString(R.string.terminal_state_connecting)
        compose.onNodeWithContentDescription(label).performScrollTo().performClick().assertIsSelected()
    }

    @Test fun keyboardLongPressOpensEditorInEachSelectedSession() {
        verifyKeyboardRoundTrip(start(), listOf(1 to "A", 2 to "B", 3 to "C", 1 to "A", 2 to "B"))
    }

    @Test fun keyboardActionFollowsSelectionWhenInitialTabIsNotFirst() {
        verifyKeyboardRoundTrip(start(initialId = 2), listOf(2 to "B", 1 to "A", 3 to "C", 2 to "B"))
    }

    private fun settleWindow() {
        compose.waitForIdle()
        // Native IME/inset animations can outlive Compose idle on landscape transitions.
        runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350, 3_000) }
        compose.waitForIdle()
    }

    private fun verifyKeyboardRoundTrip(registry: SshSessions, order: List<Pair<Int, String>>) {
        for ((id, device) in order) {
            select(device)
            settleWindow()
            compose.onNodeWithTag("terminal_keyboard").performTouchInput { longClick() }
            compose.runOnIdle {
                assertTrue("Long keyboard action must target selected session $device", registry.drafts.state.value.getValue(id).textMode)
            }
            compose.onNodeWithTag("terminal_text_editor").assertExists()
            // Return through the real accessibility action, not a coordinate moving with the IME.
            compose.onNodeWithTag("terminal_mode_keys").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.runOnIdle { assertFalse(registry.drafts.state.value.getValue(id).textMode) }
        }
    }

    @Test fun editorsKeepSeparateDraftsWhenSwitchingBetweenOpenEditors() {
        val registry = start()
        for ((id, device) in listOf(1 to "A", 2 to "B", 3 to "C")) {
            select(device)
            settleWindow()
            compose.onNodeWithTag("terminal_keyboard").performTouchInput { longClick() }
            compose.onNodeWithTag("terminal_text_editor").performTextInput(device)
            compose.runOnIdle { assertEquals(device, registry.drafts.state.value.getValue(id).text) }
        }
        val expected = mutableMapOf(1 to "A", 2 to "B", 3 to "C")
        for ((id, device) in listOf(1 to "A", 2 to "B", 3 to "C", 1 to "A")) {
            select(device)
            expected[id] = expected.getValue(id) + "x"
            compose.onNodeWithTag("terminal_text_editor").performTextInput("x")
            compose.runOnIdle {
                assertEquals(expected, registry.drafts.state.value.mapValues { it.value.text })
            }
        }
    }
}
