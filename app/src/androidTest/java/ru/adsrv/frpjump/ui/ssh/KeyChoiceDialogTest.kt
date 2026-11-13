package ru.adsrv.frpjump.ui.ssh

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ssh.HostKeyStore
import ru.adsrv.frpjump.ssh.KeyInstallResult
import ru.adsrv.frpjump.ssh.KeyInstallTarget
import ru.adsrv.frpjump.ssh.KeyInstalling
import ru.adsrv.frpjump.ssh.SshKeyChoice
import ru.adsrv.frpjump.ssh.SshKeySlot
import ru.adsrv.frpjump.ssh.SshLoginMemory
import ru.adsrv.frpjump.ssh.generateEd25519
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.ui.FrpJumpTheme
import ru.adsrv.frpjump.ui.settings.keySummary
import java.util.Locale

/** The key choice of "Use key login": a fake installer, in-memory stores, synthetic keys, no network. */
@RunWith(AndroidJUnit4::class)
class KeyChoiceDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Mem(var text: String? = null) : TextStorage {
        override fun read() = text
        override fun write(text: String) { this.text = text }
        override fun delete() { text = null }
    }

    private class Installer(val keys: List<SshKeyChoice>, val result: KeyInstallResult?) : KeyInstalling {
        @Volatile var seen: KeyInstallTarget? = null
        override fun keys() = keys
        override suspend fun install(target: KeyInstallTarget, typed: String?): KeyInstallResult {
            seen = target
            return result ?: awaitCancellation()
        }
        override fun cancel() = Unit
    }

    private val phone = SshKeyChoice(SshKeySlot.PHONE, generateEd25519("frp-jump@synthetic").publicLine)
    private val imported = SshKeyChoice(SshKeySlot.IMPORTED, generateEd25519("imported").publicLine)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Before fun display() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val deadline = SystemClock.uptimeMillis() + 5000
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

    @After fun tearDown() = scope.cancel()

    private fun start(installer: Installer): KeyLoginFlow {
        val flow = KeyLoginFlow(installer, scope)
        compose.setContent { FrpJumpTheme { KeyLoginDialogs(flow) } }
        compose.runOnUiThread {
            flow.start(KeyInstallTarget("synthetic", HostKeyStore(Mem()), "wb", 22, "root", SshLoginMemory(Mem()), { 1 }))
        }
        compose.waitForIdle()
        return flow
    }

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private fun row(key: SshKeyChoice) =
        compose.onNode(isSelectable() and radio and hasText(name(key)) and hasText(keySummary(key.publicLine)!!))
    private fun name(key: SshKeyChoice) = text(if (key.slot == SshKeySlot.PHONE) R.string.phone_key else R.string.imported_key)
    private fun keyName(key: SshKeyChoice) = text(if (key.slot == SshKeySlot.PHONE) R.string.key_name_phone else R.string.key_name_imported)

    @Test fun twoKeysAskFirstWithThePhoneKeyChosenAndTheButtonsReachable() {
        val installer = Installer(listOf(phone, imported), null)
        start(installer)
        compose.onNodeWithText(text(R.string.key_login_title, "wb:22")).assertIsDisplayed()
        // The dialog's text scrolls (landscape at 200%), its buttons never do.
        compose.onNodeWithText(text(R.string.key_choose_question, "root")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.key_choose_hint)).performScrollTo().assertIsDisplayed()
        row(phone).performScrollTo().assertIsDisplayed().assertIsSelected()
        row(imported).performScrollTo().assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithText(text(R.string.cancel)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.key_choose_confirm)).assertIsDisplayed()
        // One group of radio rows for TalkBack.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertExists()
        assertNull(installer.seen)
    }

    @Test fun pickingTheImportedKeyInstallsItAndNamesItWhileWorking() {
        val installer = Installer(listOf(phone, imported), null)
        start(installer)
        row(imported).performScrollTo().performClick()
        row(imported).assertIsSelected()
        row(phone).assertIsNotSelected()
        compose.onNodeWithText(text(R.string.key_choose_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(imported, installer.seen?.key)
        compose.onNodeWithText(text(R.string.key_install_working, keyName(imported))).assertIsDisplayed()
    }

    @Test fun theDefaultChoiceInstallsThePhoneKey() {
        val installer = Installer(listOf(phone, imported), null)
        start(installer)
        compose.onNodeWithText(text(R.string.key_choose_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(phone, installer.seen?.key)
        compose.onNodeWithText(text(R.string.key_install_working, keyName(phone))).assertIsDisplayed()
    }

    @Test fun cancelInTheChoiceRunsNothing() {
        val installer = Installer(listOf(phone, imported), null)
        val flow = start(installer)
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.waitForIdle()
        assertNull(flow.step)
        assertNull(installer.seen)
        compose.onAllNodesWithText(text(R.string.key_choose_question, "root")).assertCountEquals0()
    }

    @Test fun oneKeyHasNoChoiceAndInstallsAtOnce() {
        val installer = Installer(listOf(phone), null)
        start(installer)
        compose.onNodeWithText(text(R.string.key_install_working, keyName(phone))).assertIsDisplayed()
        assertEquals(phone, installer.seen?.key)
        assertTrue(compose.onAllNodesWithText(text(R.string.key_choose_question, "root")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun thePasswordNoteNamesTheChosenKey() {
        val installer = Installer(listOf(phone, imported), KeyInstallResult.PasswordNeeded())
        start(installer)
        row(imported).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.key_choose_confirm)).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.key_install_password_note, keyName(imported))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.key_install_password_note, keyName(imported))).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.key_install_confirm)).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() = assertTrue(fetchSemanticsNodes().isEmpty())
}
