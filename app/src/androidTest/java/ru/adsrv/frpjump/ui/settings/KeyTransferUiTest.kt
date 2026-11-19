package ru.adsrv.frpjump.ui.settings

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.lock.AuthResult
import ru.adsrv.frpjump.ssh.SshKeyExport
import ru.adsrv.frpjump.ssh.SshKeyPair
import ru.adsrv.frpjump.ssh.blobOfPublicKeyLine
import ru.adsrv.frpjump.ssh.generateEd25519
import ru.adsrv.frpjump.ssh.sshFingerprint
import ru.adsrv.frpjump.ui.FrpJumpTheme
import ru.adsrv.frpjump.ui.SecretField

/**
 * К4 on the actual Settings screen: export and restore of the phone's key, with the identity check, the system pickers
 * and the files replaced by fakes (the real BiometricPrompt and DocumentsUI are not driven here), and the REAL window:
 * FLAG_SECURE is read off the activity's and the dialog's windows. The phone key is put back after each test; every
 * other key here is synthetic.
 */
@RunWith(AndroidJUnit4::class)
class KeyTransferUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.fragment.app.FragmentActivity>()
    private val app get() = compose.activity.application as FrpJumpApp
    private fun text(id: Int) = compose.activity.getString(id)
    private val pass = "correct horse battery"

    private lateinit var original: SshKeyPair

    /** Scripted screen lock. */
    private class FakeIdentity : IdentityCheck {
        var lock = true
        var answer: AuthResult? = AuthResult.Success
        val asked = mutableListOf<IdentityPurpose>()
        override fun hasScreenLock() = lock
        override fun ask(purpose: IdentityPurpose, onResult: (AuthResult) -> Unit) {
            asked += purpose
            answer?.let(onResult)
        }
    }

    private class FakeFiles : KeyFiles {
        val written = mutableMapOf<String, ByteArray>()
        val files = mutableMapOf<String, ByteArray>()
        override fun write(target: String, bytes: ByteArray): Boolean { written[target] = bytes.copyOf(); return true }
        override fun read(source: String): ByteArray? = files[source]
    }

    private val identity = FakeIdentity()
    private val files = FakeFiles()
    private val launched = mutableListOf<String>()
    private var pickerAnswers = true

    /** The system pickers: answered by the test, with a content URI or with a cancel. */
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launched += contract.javaClass.simpleName
            val uri: Uri? = if (!pickerAnswers) null else if (contract is ActivityResultContracts.CreateDocument) Uri.parse("content://k4test/out") else Uri.parse("content://k4test/in")
            @Suppress("UNCHECKED_CAST")
            dispatchResult(requestCode, uri as O)
        }
    }

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
        }
        app.sshKeys.removeImported()
        original = SshKeyPair(app.sshKeys.phoneKeyForExport()!!, phoneLine())
    }

    @After fun putTheKeyBack() {
        app.sshKeys.replacePhoneKey(original)
    }

    /**
     * The real activity window, set on the main thread: the test's recomposition scope resumes a flow on whatever thread its
     * background read finished on (production's scope is the main one), and a window is touched on the main thread only.
     */
    private val window = object : SecureWindow {
        private val real by lazy { AndroidSecureWindow(compose.activity.window) }
        override fun secure(on: Boolean) = compose.runOnUiThread { real.secure(on) }
    }

    private fun phoneLine() = app.sshKeys.publicLine(app.sshKeyComment())

    private fun show() {
        lateinit var vm: SettingsViewModel
        compose.runOnUiThread { vm = SettingsViewModel(app) }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalKeyTransferSeams provides KeyTransferSeams(identity = identity, window = window, files = files, rounds = 4),
            ) { FrpJumpTheme { SettingsScreen(vm, null, null, {}, {}, onBack = null, onChat = {}) } }
        }
        compose.onNodeWithText(text(R.string.ssh_keys)).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.ssh_keys_hint)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tap(id: Int) {
        // The buttons of the dialogs: not in anything that scrolls.
        compose.onNodeWithText(text(id)).performClick()
        compose.waitForIdle()
    }

    private fun tag(t: String) {
        compose.onNodeWithTag(t).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun shown(id: Int) = compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(id: Int) = compose.waitUntil(5_000) { shown(id) }

    private fun activityFlags() = compose.activity.window.attributes.flags
    private fun activitySecure() = activityFlags() and WindowManager.LayoutParams.FLAG_SECURE != 0

    /** The passphrase dialog: the one with a text field (the SSH keys sheet is a dialog window too). */
    private val passphraseDialog get() = isDialog() and hasAnyDescendant(hasSetTextAction())

    /** FLAG_SECURE of the dialog's own window, read off the dialog's decor view. */
    private fun dialogSecure(): Boolean {
        val view = compose.onNode(passphraseDialog).fetchSemanticsNode().root as View
        return (view.rootView.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    /** The dialog's view or an ancestor of its content leaves the passphrase out of autofill. */
    private fun dialogLeftOutOfAutofill(): Boolean {
        var v: View? = compose.onNode(passphraseDialog).fetchSemanticsNode().root as View
        val seen = StringBuilder()
        while (v != null) {
            seen.append(v.javaClass.simpleName).append('=').append(v.importantForAutofill).append(' ')
            if (v.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS) return true
            v = v.parent as? View
        }
        throw AssertionError("no view up the dialog's chain is left out of autofill: $seen")
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    private fun type(first: String, second: String) {
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(first)
        fields[1].performTextInput(second)
        compose.waitForIdle()
    }

    private fun fp(line: String) = sshFingerprint(blobOfPublicKeyLine(line)!!)

    private fun otherKeyFile(): Pair<SshKeyPair, ByteArray> {
        val other = generateEd25519("other")
        return other to SshKeyExport.encryptPhoneKey(other.privateKey, "x", pass.toCharArray(), rounds = 4)
    }

    // Export ----------------------------------------------------------------------------------------------------------

    @Test fun exportGoesThroughExplanationIdentityPassphraseAndSaveAs() {
        show()
        tag("key-export")
        waitFor(R.string.key_export_title)
        assertEquals("the explanation asks nothing yet", emptyList<IdentityPurpose>(), identity.asked)
        assertFalse(activitySecure())
        shot("k4-export-explain")
        tap(R.string.key_transfer_continue)
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 2 }
        assertEquals(listOf(IdentityPurpose.EXPORT), identity.asked)
        assertTrue("the activity's window is secure with the passphrase dialog up", activitySecure())
        assertTrue("and so is the dialog's own", dialogSecure())
        assertTrue("no password manager sees it", dialogLeftOutOfAutofill())
        type("short", "short")
        tap(R.string.key_export_save)
        assertTrue(compose.onNodeWithTag("key-export-phrase-problem").performScrollTo().isDisplayed())
        assertTrue(activitySecure())
        shot("k4-export-passphrase-problem")
        // The fields are emptied on submit: after a refusal both are typed again.
        type(pass, "$pass.")
        tap(R.string.key_export_save)
        assertTrue("mismatch is refused", compose.onNodeWithTag("key-export-phrase-problem").performScrollTo().isDisplayed())
        type(pass, pass)
        tap(R.string.key_export_save)
        compose.waitUntil(10_000) { files.written.isNotEmpty() }
        compose.waitUntil(5_000) { !activitySecure() }
        assertEquals(listOf("CreateDocument"), launched)
        assertEquals(listOf("content://k4test/out"), files.written.keys.toList())
        compose.waitUntil(5_000) { shown(R.string.key_export_done) }
        shot("k4-export-done")
        val back = SshKeyExport.decryptEd25519(files.written.getValue("content://k4test/out"), pass.toCharArray(), "x")
        assertArrayEquals(blobOfPublicKeyLine(original.publicLine), blobOfPublicKeyLine(back.publicLine))
    }

    @Test fun cancellingTheExportIdentityCheckChangesNothingAndNeverShowsThePassphraseDialog() {
        identity.answer = AuthResult.Cancelled
        show()
        tag("key-export"); waitFor(R.string.key_export_title)
        tap(R.string.key_transfer_continue)
        compose.waitForIdle()
        assertEquals(listOf(IdentityPurpose.EXPORT), identity.asked)
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        assertFalse(activitySecure())
        assertTrue(files.written.isEmpty())
        assertTrue(launched.isEmpty())
    }

    @Test fun aFailedExportIdentityCheckSaysSoAndWritesNothing() {
        identity.answer = AuthResult.Failed("too many attempts")
        show()
        tag("key-export"); waitFor(R.string.key_export_title)
        tap(R.string.key_transfer_continue)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("key-transfer-outcome").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("key-transfer-outcome").assertTextContains("too many attempts", substring = true)
        assertTrue(files.written.isEmpty())
        assertFalse(activitySecure())
    }

    @Test fun withNoScreenLockExportWarnsAndNeedsAnExplicitContinue() {
        identity.lock = false
        show()
        tag("key-export"); waitFor(R.string.key_export_title)
        tap(R.string.key_transfer_continue)
        waitFor(R.string.key_no_lock_title)
        shot("k4-export-no-lock")
        assertTrue("no prompt without a lock", identity.asked.isEmpty())
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        tap(R.string.key_no_lock_export_go)
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 2 }
        assertTrue(activitySecure())
    }

    @Test fun cancellingSaveAsWritesNothing() {
        pickerAnswers = false
        show()
        tag("key-export"); waitFor(R.string.key_export_title)
        tap(R.string.key_transfer_continue)
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 2 }
        type(pass, pass)
        tap(R.string.key_export_save)
        compose.waitUntil(10_000) { launched.isNotEmpty() }
        compose.waitForIdle()
        assertTrue(files.written.isEmpty())
        assertFalse(activitySecure())
        assertTrue(compose.onAllNodesWithTag("key-transfer-outcome").fetchSemanticsNodes().isEmpty())
    }

    @Test fun theWindowLosesFlagSecureWhenThePassphraseDialogIsCancelled() {
        show()
        tag("key-export"); waitFor(R.string.key_export_title)
        tap(R.string.key_transfer_continue)
        compose.waitUntil(5_000) { activitySecure() }
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.waitUntil(5_000) { !activitySecure() }
    }

    // Restore ---------------------------------------------------------------------------------------------------------

    private fun pickAndOpen(file: ByteArray, phrase: String = pass) {
        files.files["content://k4test/in"] = file
        tag("key-restore")
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
        assertTrue("the restore passphrase dialog is secure too", activitySecure() && dialogSecure())
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(phrase)
        tap(R.string.key_restore_open)
    }

    @Test fun restoreShowsBothFingerprintsAndReplacesOnlyAfterTheFreshCheck() {
        val (other, file) = otherKeyFile()
        show()
        pickAndOpen(file)
        waitFor(R.string.key_restore_confirm_title)
        assertFalse("secure again off", activitySecure())
        compose.onNodeWithTag("key-restore-fp-current").assertTextEquals(fp(original.publicLine))
        compose.onNodeWithTag("key-restore-fp-file").assertTextEquals(fp(other.publicLine))
        assertTrue("no check before «Replace»", identity.asked.isEmpty())
        assertEquals(original.publicLine, phoneLine())
        shot("k4-restore-confirm")
        tap(R.string.replace_key)
        assertEquals(listOf(IdentityPurpose.RESTORE), identity.asked)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("key-transfer-outcome").fetchSemanticsNodes().isNotEmpty() }
        assertArrayEquals(blobOfPublicKeyLine(other.publicLine), blobOfPublicKeyLine(phoneLine()))
        // The key's summary is in the sheet, in the outcome line and in the list: any of them.
        compose.onAllNodesWithText(keySummary(phoneLine())!!, substring = true)[0].assertExists()
        shot("k4-restore-done")
    }

    private fun replaceAnsweredWith(answer: AuthResult) {
        identity.answer = answer
        val (_, file) = otherKeyFile()
        show()
        pickAndOpen(file)
        waitFor(R.string.key_restore_confirm_title)
        tap(R.string.replace_key)
        compose.waitForIdle()
        assertEquals(listOf(IdentityPurpose.RESTORE), identity.asked)
        assertEquals("the phone key is unchanged after $answer", original.publicLine, phoneLine())
        assertEquals(original.privateKey.length, app.sshKeys.phoneKeyForExport()!!.length)
        assertFalse(shown(R.string.key_restore_confirm_title))
    }

    @Test fun cancellingTheCheckAfterReplaceChangesNothing() = replaceAnsweredWith(AuthResult.Cancelled)

    @Test fun aFailedCheckAfterReplaceChangesNothing() = replaceAnsweredWith(AuthResult.Failed("locked out"))

    @Test fun withNoScreenLockRestoreWarnsAndReplacesOnlyOnExplicitConfirmation() {
        identity.lock = false
        val (other, file) = otherKeyFile()
        show()
        pickAndOpen(file)
        waitFor(R.string.key_restore_confirm_title)
        tap(R.string.replace_key)
        waitFor(R.string.key_no_lock_title)
        shot("k4-restore-no-lock")
        assertEquals(original.publicLine, phoneLine())
        assertTrue(identity.asked.isEmpty())
        tap(R.string.key_no_lock_restore_go)
        compose.waitUntil(5_000) { phoneLine() != original.publicLine }
        assertArrayEquals(blobOfPublicKeyLine(other.publicLine), blobOfPublicKeyLine(phoneLine()))
    }

    @Test fun aWrongPassphraseSaysSoAndChangesNothing() {
        val (_, file) = otherKeyFile()
        show()
        pickAndOpen(file, phrase = "not the one")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("key-restore-phrase-problem").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(activitySecure())
        shot("k4-restore-wrong-phrase")
        assertEquals(original.publicLine, phoneLine())
    }

    @Test fun aKeyOfAnotherTypeAndADamagedFileAreRefusedWithTheirOwnWords() {
        val rsa = java.io.StringWriter().also { w ->
            val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            org.bouncycastle.util.io.pem.PemWriter(w).use { it.writeObject(org.bouncycastle.util.io.pem.PemObject("RSA PRIVATE KEY", pair.private.encoded)) }
        }.toString()
        show()
        files.files["content://k4test/in"] = rsa.toByteArray()
        tag("key-restore")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("key-transfer-outcome").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("key-transfer-outcome").assertTextContains("rsa", substring = true)
        shot("k4-restore-rsa")
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        files.files["content://k4test/in"] = "not a key".toByteArray()
        tag("key-restore")
        compose.waitUntil(5_000) { shown(R.string.key_restore_damaged) }
        assertEquals(original.publicLine, phoneLine())
    }

    @Test fun restoringTheCurrentKeyChangesNothing() {
        val file = SshKeyExport.encryptPhoneKey(original.privateKey, "x", pass.toCharArray(), rounds = 4)
        show()
        pickAndOpen(file)
        waitFor(R.string.key_restore_same)
        assertTrue(identity.asked.isEmpty())
        assertEquals(original.publicLine, phoneLine())
    }

    // The keyboard -------------------------------------------------------------------------------------------------------

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun thePassphraseFieldAsksTheKeyboardToLearnNothingAndIsAPasswordInput() {
        var info: EditorInfo? = null
        compose.setContent {
            FrpJumpTheme {
                InterceptPlatformTextInput(
                    interceptor = { request, session ->
                        info = EditorInfo().also { request.createInputConnection(it) }
                        session.startInputMethod(request)
                    },
                ) { SecretField("", {}, "phrase-field", noPersonalizedLearning = true) }
            }
        }
        compose.onNodeWithText("phrase-field").performClick()
        compose.waitUntil(5_000) { info != null }
        assertTrue(info!!.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, info!!.inputType and android.text.InputType.TYPE_MASK_VARIATION)
        assertTrue(info!!.inputType and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0 || info!!.inputType and android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT == 0)
    }
}
