package ru.adsrv.frpjump.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.Locale
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ssh.publicLineOfPrivateKey
import ru.adsrv.frpjump.ui.FrpJumpTheme

/**
 * Settings › Security › SSH keys on the actual Settings screen: the row, and the sheet with the phone's key and an
 * imported one. The imported key is synthetic and removed again; a phone that already has one skips these tests.
 */
@RunWith(AndroidJUnit4::class)
class SshKeysSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val app get() = compose.activity.application as FrpJumpApp
    private fun text(id: Int) = compose.activity.getString(id)
    private var imported: String? = null

    /** What each Share icon handed over: the key's line and the chooser's title. */
    private val shared = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

    /** A real RSA 3072 pair: its public line (about 570 characters) folds to three lines at any font scale. */
    private val rsa = run {
        val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val pem = java.io.StringWriter().also { w ->
            org.bouncycastle.util.io.pem.PemWriter(w).use {
                it.writeObject(org.bouncycastle.util.io.pem.PemObject("PRIVATE KEY", pair.private.encoded))
            }
        }.toString()
        pem to publicLineOfPrivateKey(pem, null, "imported")
    }
    private val longLine = rsa.second

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
        assumeTrue("the phone already has an imported key", app.sshKeys.importedPublicLine() == null)
    }

    @After fun cleanup() {
        compose.runOnIdle { models.clear() }
        if (imported != null) app.sshKeys.removeImported()
    }

    private fun show(withImported: Boolean) {
        if (withImported) {
            imported = longLine
            app.sshKeys.import(rsa.first, null, longLine)
        }
        lateinit var vm: SettingsViewModel
        compose.runOnUiThread {
            vm = SettingsViewModel(app)
            models.put("ssh-keys-settings", vm)
        }
        compose.setContent {
            CompositionLocalProvider(LocalShareText provides { _, line, title -> shared.add(line to title); Unit }) {
                FrpJumpTheme { SettingsScreen(vm, null, null, {}, {}, onBack = null, onChat = {}) }
            }
        }
    }

    private fun phoneLine() = app.sshKeys.publicLine(app.sshKeyComment())

    private fun openSheet() {
        compose.onNodeWithText(text(R.string.ssh_keys)).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.ssh_keys_hint)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        assertTrue("no text layout", results.isNotEmpty())
        return results.first()
    }

    private fun clipboardText(): String? {
        var text: String? = null
        compose.runOnUiThread {
            val clip = (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            text = clip?.getItemAt(0)?.text?.toString()
        }
        return text
    }

    @Test fun theRowIsCalledSshKeysAndSaysTheFingerprintWithOneKey() {
        show(withImported = false)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(keySummary(phoneLine())!!, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.ssh_keys)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(keySummary(phoneLine())!!, substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(text(R.string.ssh_keys_both)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theRowSaysPhoneAndImportedWithTwoKeys() {
        show(withImported = true)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.ssh_keys_both)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.ssh_keys)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ssh_keys_both)).performScrollTo().assertIsDisplayed()
    }

    @Test fun theSheetShowsBothKeysAlikeWithLabelledCopyAndShare() {
        show(withImported = true)
        openSheet()
        for (id in listOf(R.string.phone_key, R.string.imported_key)) compose.onNodeWithText(text(id), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(keySummary(phoneLine())!!).assertExists()
        compose.onNodeWithText(keySummary(longLine)!!).assertExists()
        for (id in listOf(R.string.copy_phone_key, R.string.copy_imported_key, R.string.share_phone_key, R.string.share_imported_key)) {
            compose.onNodeWithContentDescription(text(id)).performScrollTo().assertIsDisplayed().assertHasClickAction()
        }
        compose.onNodeWithText(text(R.string.imported_key_tried_first)).assertExists()
        compose.onNodeWithText(text(R.string.replace_key)).assertExists()
        compose.onNodeWithText(text(R.string.remove_key)).assertExists()
    }

    @Test fun copyWorksForBothKeys() {
        show(withImported = true)
        openSheet()
        compose.onNodeWithContentDescription(text(R.string.copy_imported_key)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(longLine, clipboardText())
        compose.onNodeWithContentDescription(text(R.string.copy_phone_key)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(phoneLine(), clipboardText())
    }

    @Test fun eachShareIconHandsOverItsOwnLineAndNameAndTheChooserIsTitledWithIt() {
        show(withImported = true)
        openSheet()
        compose.onNodeWithContentDescription(text(R.string.share_imported_key)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(text(R.string.share_phone_key)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(longLine to text(R.string.imported_key), phoneLine() to text(R.string.phone_key)), shared.toList())
        // The chooser itself is the system's; what it is built from is the key's line and name.
        val intent = shareTextIntent(longLine, text(R.string.imported_key))
        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        assertEquals(text(R.string.imported_key), intent.getCharSequenceExtra(Intent.EXTRA_TITLE))
        @Suppress("DEPRECATION")
        val send = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(longLine, send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(Intent.ACTION_SEND, send.action)
    }

    @Test fun aLongLineIsFoldedToThreeLinesAndATapUnfoldsAndFoldsIt() {
        show(withImported = true)
        openSheet()
        val block = compose.onNodeWithTag("ssh-key-line-IMPORTED").performScrollTo().assertIsDisplayed()
        val label = block.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label
        assertEquals("TalkBack action", text(R.string.show_whole_key), label)
        fun lines() = layoutOf(compose.onNodeWithText(longLine, useUnmergedTree = true)).lineCount
        assertEquals(3, lines())
        assertTrue(layoutOf(compose.onNodeWithText(longLine, useUnmergedTree = true)).hasVisualOverflow)
        block.performClick()
        compose.waitForIdle()
        assertTrue("unfolded: ${lines()}", lines() > 3)
        assertEquals(text(R.string.fold_key), block.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label)
        block.performClick()
        compose.waitForIdle()
        assertEquals(3, lines())
    }

    @Test fun aShortLineHasNoFoldAction() {
        show(withImported = false)
        openSheet()
        val phoneBlock = compose.onNodeWithTag("ssh-key-line-PHONE").performScrollTo()
        // The phone's ed25519 line fits in three lines at 100%; at larger text it folds and then has the action.
        val folded = layoutOf(compose.onNodeWithText(phoneLine(), useUnmergedTree = true)).hasVisualOverflow
        val action = phoneBlock.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)
        assertEquals(folded, action != null)
    }
}
