package ru.adsrv.frpjump.ui.ssh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
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
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.TmuxNames
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.ui.FrpJumpTheme

/** Actual OSC parser, registry, TerminalScreen selection and Android clipboard; no SSH sockets. */
@RunWith(AndroidJUnit4::class)
class SshClipboardAuthorityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = SupervisorJob().apply { cancel() }
    private val scope = CoroutineScope(root + Dispatchers.Main)
    private lateinit var sessions: SshSessions
    private lateinit var env: SshEnvironment
    private val shown = mutableStateOf(true)
    private val visible = MutableStateFlow(true)
    private val clipboard get() = compose.activity.getSystemService(ClipboardManager::class.java)

    private class Memory : TextStorage {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
        override fun delete() { text = null }
    }
    private val unusedBox = object : SecretBox {
        override fun seal(plain: String): String = error("No secrets in clipboard test")
        override fun open(sealed: String): String = error("No secrets in clipboard test")
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
        compose.runOnIdle { if (::sessions.isInitialized) sessions.closeAll(); clipboard.clearPrimaryClip() }
        root.cancel()
    }

    private fun start(initialId: Int): List<SshSession> {
        val app = compose.activity.application as FrpJumpApp
        env = SshEnvironment(SshKeyStore(Memory(), unusedBox), SshPasswords(Memory(), unusedBox),
            { "synthetic" }, TmuxNames(Memory()), visible = visible)
        compose.runOnUiThread {
            sessions = SshSessions(app, app.notices, env, flowOf(emptySet()), scope)
            for (device in listOf("A", "B")) {
                sessions.open("synthetic-profile", HostKeyStore(Memory()), device, 22, 9, "u", SshAuth.Key)
            }
        }
        val all = OpenSessions(sessions.through, sessions::throughNow, flowOf(emptyList()), { emptyList() }, app.pendingUndo.hidden)
        val closer = SessionCloser(app.pendingUndo, closeTerminal = { sessions.close(it) }, closeMqtt = {})
        val login = KeyLoginFlow(KeyInstaller(env, sessions), scope)
        val snippets = SnippetStore(Memory(), Memory())
        compose.setContent {
            FrpJumpTheme {
                if (shown.value) TerminalScreen(sessions, all, snippets, app.pendingUndo, closer, emptyMap(), initialId = initialId,
                    onBack = {}, onOpenFiles = {}, rememberLogin = { _, _ -> }, keyLogin = login, keyLoginTarget = { null })
            }
        }
        compose.waitForIdle()
        return sessions.sessions.value
    }

    /** Called on main, just like TerminalSession's input Handler calls append(). */
    private fun osc(tab: SshSession, text: String, st: Boolean = false) {
        val bytes = ("\u001b]52;c;" + Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) +
            if (st) "\u001b\\" else "\u0007").toByteArray(Charsets.UTF_8)
        tab.terminal.session.emulator.append(bytes, bytes.size)
    }
    private fun seed() { clipboard.setPrimaryClip(ClipData.newPlainText("synthetic", "sentinel")) }
    private fun copied() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    @Test fun initialBRejectsAutomaticClipboardFromA() {
        val tabs = start(initialId = 2)
        compose.runOnIdle {
            seed()
            osc(tabs[0], "unselected A")
            assertEquals("Nonselected tab must not overwrite the clipboard", "sentinel", copied())
            osc(tabs[1], "selected B\nтекст", st = true)
            assertEquals("selected B\nтекст", copied())
        }
    }

    @Test fun tabTapRevokesOldTabBeforeNextComposition() {
        val tabs = start(initialId = 2)
        compose.runOnIdle { seed() }
        val label = tabLabel("u", "A", 22, 1) + ", " + compose.activity.getString(R.string.terminal_state_connecting)
        compose.onNodeWithContentDescription(label).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { click ->
            click()
            // A remote callback already queued behind this gesture cannot retain B's authority.
            osc(tabs[1], "old B after A tap")
            assertEquals("Selection revocation must be immediate, not a LaunchedEffect later", "sentinel", copied())
        }
        compose.runOnIdle {
            osc(tabs[0], "now A")
            assertEquals("now A", copied())
        }
    }

    @Test fun selectedAndManualCopyRemainAllowedWithoutScreenOrVisibilityGate() {
        val tabs = start(initialId = 1)
        compose.runOnIdle {
            osc(tabs[0], "selected A")
            assertEquals("selected A", copied())
            // The manual selection-copy route is TerminalSession's callback, not the OSC parser.
            tabs[1].terminal.session.onCopyTextToClipboard("explicit manual B")
            assertEquals("explicit manual B", copied())
            visible.value = false
            shown.value = false
        }
        compose.runOnIdle {
            osc(tabs[0], "last selected A, screen gone")
            assertEquals("last selected A, screen gone", copied())
        }
    }

    @Test fun fallbackUsesActuallyShownFirstTabNotMissingInitialId() {
        val tabs = start(initialId = 999)
        compose.runOnIdle {
            seed()
            osc(tabs[1], "not selected B")
            assertEquals("sentinel", copied())
            osc(tabs[0], "fallback A")
            assertEquals("fallback A", copied())
        }
    }

    @Test fun replacementAndRemovedTerminalCannotReuseSelectionAuthority() {
        val tabs = start(initialId = 1)
        compose.runOnIdle { shown.value = false }
        compose.runOnIdle {
            seed()
            val fresh = requireNotNull(sessions.reconnect(tabs[0].id))
            osc(tabs[0], "late replaced A")
            assertEquals("sentinel", copied())
            // A replacement is not the old selected terminal, even for the same device and login.
            osc(fresh, "fresh before selection")
            assertEquals("sentinel", copied())
            sessions.selectClipboardSource(fresh.id)
            osc(fresh, "fresh selected")
            assertEquals("fresh selected", copied())
            sessions.close(fresh.id)
            osc(fresh, "late after removal")
            assertEquals("fresh selected", copied())
        }
    }

    @Test fun unselectedStaysDeniedAfterScreenLeaves() {
        val tabs = start(initialId = 1)
        compose.runOnIdle {
            seed()
            visible.value = false
            shown.value = false
        }
        compose.runOnIdle {
            osc(tabs[1], "unselected B, screen gone")
            assertEquals("sentinel", copied())
        }
    }
}
