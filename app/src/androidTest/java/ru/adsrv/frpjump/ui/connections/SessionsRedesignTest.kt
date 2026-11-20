package ru.adsrv.frpjump.ui.connections

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.sessions.SessionCloser
import ru.adsrv.frpjump.notices.Notices
import ru.adsrv.frpjump.notices.NoticeAction
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ui.NoticeHost
import ru.adsrv.frpjump.settings.ThemeMode
import ru.adsrv.frpjump.ssh.HostKeyStore
import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SshEnvironment
import ru.adsrv.frpjump.ssh.SshKeyStore
import ru.adsrv.frpjump.ssh.SshPasswords
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.TmuxNames
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FrpJumpTheme
import ru.adsrv.frpjump.undo.PendingUndo
import java.io.File
import java.util.Locale

/** Production Sessions UI with inert, synthetic SSH tabs and an isolated Undo owner. */
class SessionsRedesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Memory : TextStorage {
        private var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
        override fun delete() { text = null }
    }
    private val box = object : SecretBox {
        override fun seal(plain: String): String = error("no credentials")
        override fun open(sealed: String): String = error("no credentials")
        override fun destroy() = Unit
    }
    private val root = SupervisorJob().apply { cancel() }
    private val scope = CoroutineScope(root + Dispatchers.Main)
    private val app get() = compose.activity.application as FrpJumpApp
    private val notices = Notices({ true }, { false }, { _, _ -> })
    private val pending = PendingUndo(timer = { _, _ -> {} }, run = {}, announce = { counts, undo ->
        if (counts == null) notices.dismissKey("undo") else notices.show(
            compose.activity.getString(R.string.undo_closed, counts.values.sum()), NoticeKind.DONE,
            key = "undo", action = NoticeAction(compose.activity.getString(R.string.undo), undo), first = true)
    })
    private lateinit var terminals: SshSessions
    private var opened: Int? = null
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var originalConfiguration: Configuration

    @Before fun prepare() {
        val landscape = args.getString("composerOrientation") == "landscape"
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        val end = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < end) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        args.getString("composerFontScale")?.toFloat()?.let { assertEquals(it, compose.activity.resources.configuration.fontScale, 0.01f) }
        val resources = compose.activity.resources
        originalConfiguration = Configuration(resources.configuration)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(Configuration(originalConfiguration).apply { setLocale(Locale.forLanguageTag(args.getString("chatLocale") ?: "en")) }, resources.displayMetrics)
        val env = SshEnvironment(SshKeyStore(Memory(), box), SshPasswords(Memory(), box), { "synthetic" }, TmuxNames(Memory()))
        compose.runOnUiThread {
            terminals = SshSessions(app, app.notices, env, flowOf(emptySet()), scope, hidden = pending.hidden)
            terminals.open("work", HostKeyStore(Memory()), "beelink", 22, 9, "user", SshAuth.Key)
            terminals.open("work", HostKeyStore(Memory()), "beelink", 22, 9, "user", SshAuth.Key)
            terminals.open("home", HostKeyStore(Memory()), "nas", 22, 9, "user", SshAuth.Key)
        }
    }

    @After fun cleanup() {
        scope.cancel()
        @Suppress("DEPRECATION")
        compose.activity.resources.updateConfiguration(originalConfiguration, compose.activity.resources.displayMetrics)
    }

    private fun host() = compose.setContent {
        val locale = Locale.forLanguageTag(args.getString("chatLocale") ?: "en")
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(locale) }
        val localized = compose.activity.createConfigurationContext(config)
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config, LocalActivityResultRegistryOwner provides compose.activity) {
            FrpJumpTheme(mode = ThemeMode.DARK) {
                Box(Modifier.fillMaxSize().background(AppTheme.colors.ground)) {
                    SessionsScreen(terminals, app.mqtt, pending.hidden, SessionCloser(pending, {}, {}), mapOf("work" to "crimson", "home" to "blue"), { opened = it }, {}, profileNames = mapOf("work" to "Работа", "home" to "Дом"))
                    NoticeHost(notices)
                }
            }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle(); SystemClock.sleep(500)
        val suffix = "${args.getString("chatLocale") ?: "en"}-${args.getString("composerOrientation") ?: "portrait"}-font${args.getString("composerFontScale") ?: "1.0"}"
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(compose.activity.getExternalFilesDir(null), "$name-$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }

    @Test fun searchHintAndInputAreCenteredWithIcon() {
        host()
        fun center(tag: String): Float {
            val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
            return (bounds.top.value + bounds.bottom.value) / 2f
        }
        val icon = center("sessions_search_icon")
        assertEquals("empty hint centered on icon", icon, center("sessions_search_hint"), 2f)
        compose.onNodeWithTag("sessions_search").performTextInput("SSH")
        assertEquals("typed input centered on icon", icon, center("sessions_search_value"), 2f)
        shot("sessions-search-alignment")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.connections_clear_query)).performClick()
        assertEquals("cleared hint centered on icon", icon, center("sessions_search_hint"), 2f)
    }

    @Test fun searchSelectionAndUndo() {
        host()
        compose.onNodeWithTag("sessions_terminal_1").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        shot("sessions-list")
        compose.onNodeWithTag("sessions_search").performTextInput("beelink")
        compose.onNodeWithTag("sessions_terminal_3").assertDoesNotExist()
        shot("sessions-search")
        compose.onNodeWithTag("sessions_search").performImeAction()
        compose.onNodeWithTag("sessions_terminal_1").performTouchInput { longClick() }
        compose.onNodeWithTag("sessions_selection").assertIsDisplayed()
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.session_close_named, "user · beelink"), useUnmergedTree = true).assertCountEquals(0)
        shot("sessions-selection")
        compose.onNodeWithTag("sessions_select_all").performClick()
        compose.onNodeWithTag("sessions_close_selected").performClick()
        compose.runOnIdle { assertEquals(2, pending.count) }
        compose.onNodeWithTag("sessions_terminal_1").assertDoesNotExist()
        compose.onNodeWithTag("sessions_terminal_2").assertDoesNotExist()
        shot("sessions-undo")
        compose.onNodeWithTag("sessions_search").performTextClearance()
        compose.onNodeWithTag("sessions_list").performScrollToNode(hasTestTag("sessions_terminal_3"))
        compose.onNodeWithTag("sessions_terminal_3").assertExists()
        compose.runOnIdle { pending.undoAll() }
        compose.onNodeWithTag("sessions_list").performScrollToNode(hasTestTag("sessions_terminal_1"))
        compose.onNodeWithTag("sessions_terminal_1").assertExists()
        compose.onNodeWithTag("sessions_search").performTextInput("not-found")
        compose.onNodeWithText(compose.activity.getString(R.string.sessions_no_match)).assertIsDisplayed()
        shot("sessions-no-match")
    }
    @Test fun emptyListHasItsOwnMessage() {
        compose.runOnIdle { terminals.closeAll() }
        host()
        compose.onNodeWithText(compose.activity.getString(R.string.sessions_empty)).assertIsDisplayed()
        shot("sessions-empty")
    }

    @Test fun terminalChoicesShowDistinctTabsAndNewAction() {
        compose.runOnIdle {
            repeat(10) { terminals.open("work", HostKeyStore(Memory()), "beelink", 22, 9, "user", SshAuth.Key) }
        }
        val choices = terminals.sessions.value.filter { it.device == "beelink" }
        compose.setContent {
            FrpJumpTheme(mode = ThemeMode.DARK) {
                TerminalChoicesSheet("beelink", choices, {}, { opened = it.id }, { opened = 99 })
            }
        }
        compose.onNodeWithTag("sessions_picker_1").assertExists()
        compose.onNodeWithTag("sessions_picker_2").assertExists()
        compose.onNodeWithTag("sessions_picker_new").assertIsDisplayed()
        shot("sessions-picker")
        compose.onNodeWithTag("sessions_picker_list").performScrollToNode(hasTestTag("sessions_picker_13"))
        compose.onNodeWithTag("sessions_picker_13").assertExists()
        compose.onNodeWithTag("sessions_picker_new").assertIsDisplayed()
        compose.onNodeWithTag("sessions_picker_list").performScrollToNode(hasTestTag("sessions_picker_2"))
        compose.onNodeWithTag("sessions_picker_2").performClick()
        compose.runOnIdle { assertEquals(2, opened) }
        compose.onNodeWithTag("sessions_picker_new").performClick()
        compose.runOnIdle { assertEquals(99, opened) }
    }

}
