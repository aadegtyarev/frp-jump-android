package ru.adsrv.frpjump.ui.ssh

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

/**
 * Terminal > menu > "Ask the assistant" on the real TerminalScreen with a synthetic tab: offered only when the screen is given the
 * hook (the assistant is on), and used with nothing selected it opens the chat without a quote. The selection's text itself is
 * read from the Termux view by the screen; the quote built from it is covered by the unit tests of [ru.adsrv.frpjump.assistant.chat.ChatQuote].
 */
@RunWith(AndroidJUnit4::class)
class TerminalAskAssistantTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = SupervisorJob().apply { cancel() }
    private val scope = CoroutineScope(root + Dispatchers.Main)
    private var sessions: SshSessions? = null
    private val asked = mutableListOf<String?>()

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
        compose.runOnIdle { (compose.activity.application as FrpJumpApp).visible.value = false; sessions?.closeAll() }
        root.cancel()
    }

    private fun start(assistantOn: Boolean) {
        val app = compose.activity.application as FrpJumpApp
        val env = SshEnvironment(SshKeyStore(Memory(), unusedBox), SshPasswords(Memory(), unusedBox), { "synthetic" }, TmuxNames(Memory()))
        lateinit var registry: SshSessions
        compose.runOnUiThread {
            registry = SshSessions(app, app.notices, env, flowOf(emptySet()), scope, hidden = app.pendingUndo.hidden)
            sessions = registry
            registry.open("synthetic-profile", HostKeyStore(Memory()), "A", 22, 9, "u", SshAuth.Key)
        }
        val all = OpenSessions(registry.through, registry::throughNow, flowOf(emptyList()), { emptyList() }, app.pendingUndo.hidden)
        val closer = SessionCloser(app.pendingUndo, closeTerminal = { registry.close(it) }, closeMqtt = {})
        val keyLogin = KeyLoginFlow(KeyInstaller(env, registry), scope)
        val snippets = SnippetStore(Memory(), Memory())
        compose.setContent {
            FrpJumpTheme {
                TerminalScreen(registry, all, snippets, app.pendingUndo, closer, emptyMap(), initialId = 1,
                    onBack = {}, onAskAssistant = if (assistantOn) ({ asked += it }) else null,
                    onOpenFiles = {}, rememberLogin = { _, _ -> }, keyLogin = keyLogin, keyLoginTarget = { null })
            }
        }
        compose.waitForIdle()
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.more)).performClick()
        compose.waitForIdle()
    }

    @Test fun theItemIsOfferedWhileTheAssistantIsOnAndOpensTheChatWithoutAQuote() {
        start(assistantOn = true)
        openMenu()
        compose.onNodeWithTag("terminal_ask_assistant").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(1, asked.size)
        assertNull(asked.single())
    }

    @Test fun theItemIsNotOfferedWhileTheAssistantIsOff() {
        start(assistantOn = false)
        openMenu()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.more)).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("terminal_ask_assistant").fetchSemanticsNodes().isEmpty())
    }
}
