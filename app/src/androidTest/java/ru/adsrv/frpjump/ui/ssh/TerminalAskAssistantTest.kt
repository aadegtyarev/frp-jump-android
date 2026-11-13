package ru.adsrv.frpjump.ui.ssh

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.termux.view.TerminalView
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import ru.adsrv.frpjump.assistant.chat.ChatQuote
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.ui.ChatFileBackup
import ru.adsrv.frpjump.ui.Routes
import ru.adsrv.frpjump.ui.askAssistant
import ru.adsrv.frpjump.ui.assistantRoutes
import ru.adsrv.frpjump.ui.openAssistant
import ru.adsrv.frpjump.ui.openDeviceCard
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
 * hook (the assistant is on); used with nothing selected it opens the chat without a quote; with text selected in the tab's own
 * emulator the real chain (selection, menu, the app's quote, the chat's draft) puts exactly [ChatQuote.block] of it in the draft; and
 * from a chat that is already open (chat, a device card over it, the terminal, Ask) the existing chat is reached, never a second one.
 * The app's sealed chat file is put back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class TerminalAskAssistantTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = SupervisorJob().apply { cancel() }
    private val scope = CoroutineScope(root + Dispatchers.Main)
    private var sessions: SshSessions? = null
    private val app get() = compose.activity.application as FrpJumpApp
    private val chatFile by lazy { ChatFileBackup(app) }
    private var originalSettings: AppSettings? = null
    private lateinit var nav: NavHostController
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
        compose.runOnIdle {
            app.visible.value = false; sessions?.closeAll(); app.assistantQuote.value = null
            originalSettings?.let { old -> app.settings.update { old } }
        }
        root.cancel()
        if (originalSettings != null) {
            runBlocking(Dispatchers.IO) { app.assistantHistory.clear() }
            chatFile.restore()
        }
    }

    /** The real terminal screen over one synthetic tab (id 1), with [onAsk] as its Ask hook. */
    private fun terminalContent(onAsk: ((String?) -> Unit)?): @Composable () -> Unit {
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
        return {
            TerminalScreen(registry, all, snippets, app.pendingUndo, closer, emptyMap(), initialId = 1,
                onBack = {}, onAskAssistant = onAsk, onOpenFiles = {}, rememberLogin = { _, _ -> }, keyLogin = keyLogin, keyLoginTarget = { null })
        }
    }

    private fun start(assistantOn: Boolean) {
        val terminal = terminalContent(if (assistantOn) ({ asked += it }) else null)
        compose.setContent { FrpJumpTheme { terminal() } }
        compose.waitForIdle()
    }

    private val route: String? get() = compose.runOnUiThread { nav.currentDestination?.route }
    private fun chatEntries() = compose.runOnUiThread { nav.currentBackStack.value.count { it.destination.route == Routes.CHAT } }
    private val draft get() = compose.onNodeWithTag("chat_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** The assistant is on, the chat file is empty (and kept), and the app's own navigation pieces wrap the real terminal screen. */
    private fun startNav() {
        originalSettings = app.settings.settings.value
        chatFile.save()
        runBlocking(Dispatchers.IO) { app.assistantHistory.clear() }
        compose.runOnUiThread { app.settings.update { it.copy(assistantEnabled = true) }; app.assistantQuote.value = null }
        val terminal = terminalContent { quote -> nav.askAssistant(app, quote) }
        compose.setContent {
            FrpJumpTheme {
                nav = rememberNavController()
                NavHost(nav, Routes.DEVICES) {
                    composable(Routes.DEVICES) { Box(Modifier.testTag("screen_devices")) { Text("devices") } }
                    composable(Routes.DEVICE) { Box(Modifier.testTag("screen_device")) { Text("device card") } }
                    composable(Routes.TERMINAL) { terminal() }
                    assistantRoutes(nav, app)
                }
            }
        }
        compose.waitForIdle()
    }

    /** Clears the tab's screen, writes [token] on its first row and selects it the way a long press does. */
    private fun selectInTab(token: String) {
        compose.waitUntil(10_000) { compose.runOnUiThread { findTerminalView(compose.activity.window.decorView) != null } }
        compose.waitForIdle()
        val tab = compose.runOnUiThread { sessions!!.sessions.value.single() }
        val view = compose.runOnUiThread { findTerminalView(compose.activity.window.decorView) ?: error("no terminal view on screen: " + dump(compose.activity.window.decorView)) }
        compose.runOnUiThread {
            val bytes = "\u001b[2J\u001b[H$token\r\n".toByteArray()
            tab.terminal.session.emulator.append(bytes, bytes.size)
            view.onScreenUpdated()
        }
        compose.waitForIdle()
        // The first row sits at a height that depends on the font; try heights until the selection is exactly the token.
        var selected: String? = null
        var y = 4f
        while (selected != token && y < 240f) {
            compose.runOnUiThread {
                view.stopTextSelectionMode()
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 12f, y, 0)
                view.startTextSelectionMode(event); event.recycle()
                selected = view.selectedTerminalText()
            }
            y += 6f
        }
        assertEquals("the selection in the tab", token, selected)
    }

    private fun dump(v: View, d: Int = 0): String = "\n" + " ".repeat(d) + v.javaClass.simpleName + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).joinToString("") { dump(g.getChildAt(it), d + 1) } } ?: "")

    private fun findTerminalView(v: View): TerminalView? =
        if (v is TerminalView) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { findTerminalView(g.getChildAt(it)) } }

    private fun askFromMenu() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("terminal_ask_assistant").assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    @Test fun aSelectionInTheTabReachesTheChatDraftAsTheQuoteBlock() {
        startNav()
        compose.runOnUiThread { nav.navigate(Routes.terminal(1)) }
        compose.waitUntil(10_000) { route == Routes.TERMINAL }
        selectInTab("SYNTH_TOKEN_42")
        askFromMenu()
        compose.waitUntil(10_000) { route == Routes.CHAT }
        val expected = ChatQuote.block("SYNTH_TOKEN_42", compose.activity.getString(R.string.chat_quote_truncated))!!
        compose.waitUntil(10_000) { runCatching { draft == expected }.getOrDefault(false) }
        assertNull(app.assistantQuote.value)
        assertEquals(1, chatEntries())
    }

    @Test fun chatThenADeviceCardThenTheTerminalThenAskReachesTheOneChatWithTheQuote() {
        startNav()
        compose.runOnUiThread { nav.openAssistant() }
        compose.waitUntil(10_000) { route == Routes.CHAT }
        val chat = compose.runOnUiThread { nav.getBackStackEntry(Routes.CHAT) }
        // A notification tap: the device card over the chat; from it a terminal tab.
        compose.runOnUiThread { nav.openDeviceCard("synthetic-device") }
        compose.waitUntil(10_000) { route == Routes.DEVICE }
        compose.runOnUiThread { nav.navigate(Routes.terminal(1)) }
        compose.waitUntil(10_000) { route == Routes.TERMINAL }
        selectInTab("SYNTH_TOKEN_43")
        askFromMenu()
        compose.waitUntil(10_000) { route == Routes.CHAT }
        compose.waitForIdle()
        assertEquals("exactly one chat entry", 1, chatEntries())
        assertSame("the chat that was already open", chat, compose.runOnUiThread { nav.getBackStackEntry(Routes.CHAT) })
        val expected = ChatQuote.block("SYNTH_TOKEN_43", compose.activity.getString(R.string.chat_quote_truncated))!!
        compose.waitUntil(10_000) { runCatching { draft == expected }.getOrDefault(false) }
        assertNull(app.assistantQuote.value)
        // Back leaves to where the chat was opened from, not to the terminal or the card.
        compose.onNodeWithTag("chat_back").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { route == Routes.DEVICES }
        assertEquals(0, chatEntries())
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
