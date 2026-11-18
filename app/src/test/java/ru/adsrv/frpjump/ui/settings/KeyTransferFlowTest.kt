package ru.adsrv.frpjump.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.lock.AuthResult
import ru.adsrv.frpjump.ssh.KeyFileProblem
import ru.adsrv.frpjump.ssh.SshKeyExport
import ru.adsrv.frpjump.ssh.SshKeyPair
import ru.adsrv.frpjump.ssh.blobOfPublicKeyLine
import ru.adsrv.frpjump.ssh.generateEd25519
import ru.adsrv.frpjump.ssh.sshFingerprint

/**
 * К4: the steps of export and restore with the identity check, FLAG_SECURE, the phone's key slot and the files all
 * replaced by fakes, so success, cancel, failure and "no screen lock" can each be driven. Everything runs inline
 * (an unconfined dispatcher), so a step is final when the call returns.
 */
class KeyTransferFlowTest {
    private val pass = "correct horse battery"
    private fun phrase() = pass.toCharArray()

    private class FakeKey(var pair: SshKeyPair = generateEd25519("frp-jump@test")) : PhoneKey {
        var replaced = 0
        var readPrivate = 0
        var failReplace = false
        override fun publicLine() = pair.publicLine
        override fun privateKeyText(): String { readPrivate++; return pair.privateKey }
        override fun comment() = "frp-jump@test"
        override fun replace(pair: SshKeyPair) {
            if (failReplace) throw java.io.IOException("disk")
            this.pair = pair
            replaced++
        }
    }

    private class FakeIdentity(var lock: Boolean = true) : IdentityCheck {
        val asked = mutableListOf<IdentityPurpose>()
        var answer: ((AuthResult) -> Unit)? = null
        override fun hasScreenLock() = lock
        override fun ask(purpose: IdentityPurpose, onResult: (AuthResult) -> Unit) {
            asked += purpose
            answer = onResult
        }
    }

    private class FakeWindow : SecureWindow {
        val log = mutableListOf<Boolean>()
        var on = false
        override fun secure(on: Boolean) { this.on = on; log += on }
    }

    private class FakeFiles : KeyFiles {
        val written = mutableMapOf<String, ByteArray>()
        val files = mutableMapOf<String, ByteArray?>()
        var failWrite = false
        override fun write(target: String, bytes: ByteArray): Boolean {
            if (failWrite) return false
            written[target] = bytes.copyOf()
            return true
        }
        override fun read(source: String): ByteArray? = files[source]?.copyOf()
    }

    private inner class Rig(lock: Boolean = true, val key: FakeKey = FakeKey()) {
        val identity = FakeIdentity(lock)
        val window = FakeWindow()
        val files = FakeFiles()
        val replacedLines = mutableListOf<String>()
        val flow = KeyTransferFlow(
            key, identity, window, files,
            scope = CoroutineScope(Dispatchers.Unconfined), work = Dispatchers.Unconfined,
            onReplaced = { replacedLines += it }, rounds = 4,
        )
        val step get() = flow.step.value
        val outcome get() = flow.outcome.value

        fun toExportPassphrase() {
            flow.startExport(); flow.explainContinue(); identity.answer!!(AuthResult.Success)
            assertTrue(step is KeyTransferStep.ExportPassphrase)
        }

        fun exportFile(): ByteArray {
            toExportPassphrase()
            flow.submitExportPassphrase(phrase(), phrase())
            assertTrue(step is KeyTransferStep.ExportSave)
            flow.saveTargetChosen("content://out")
            return files.written.getValue("content://out")
        }

        fun toRestoreConfirm(file: ByteArray, p: String = pass) {
            files.files["content://in"] = file
            flow.startRestore(); flow.fileChosen("content://in")
            if (step is KeyTransferStep.RestorePassphrase) flow.submitRestorePassphrase(p.toCharArray())
            assertTrue("step: $step", step is KeyTransferStep.RestoreConfirm)
        }
    }

    private fun seedOf(text: String): ByteArray {
        val body = java.util.Base64.getMimeDecoder().decode(text.lines().filter { !it.startsWith("-----") }.joinToString(""))
        return (org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(body) as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters).encoded
    }

    private fun otherKeyFile(): Pair<SshKeyPair, ByteArray> {
        val other = generateEd25519("other")
        return other to SshKeyExport.encryptPhoneKey(other.privateKey, "x", phrase(), rounds = 4)
    }

    // Passphrase rules ------------------------------------------------------------------------------------------------

    @Test fun thePassphraseMustNotBeEmptyMustReachTheMinimumAndBeTypedTwice() {
        assertEquals(PassphraseProblem.EMPTY, checkNewPassphrase(CharArray(0), CharArray(0)))
        assertEquals(PassphraseProblem.TOO_SHORT, checkNewPassphrase("1234567".toCharArray(), "1234567".toCharArray()))
        assertEquals(PassphraseProblem.MISMATCH, checkNewPassphrase("12345678".toCharArray(), "12345679".toCharArray()))
        assertEquals(PassphraseProblem.MISMATCH, checkNewPassphrase("12345678".toCharArray(), "123456789".toCharArray()))
        assertNull(checkNewPassphrase("12345678".toCharArray(), "12345678".toCharArray()))
        assertNull(checkNewPassphrase("пароль-фраза".toCharArray(), "пароль-фраза".toCharArray()))
        assertEquals(8, MIN_PASSPHRASE)
    }

    @Test fun aRefusedPassphraseKeepsTheDialogReadsNoKeyAndZeroesBothArrays() {
        val r = Rig()
        r.toExportPassphrase()
        for ((a, b, problem) in listOf(
            Triple("", "", PassphraseProblem.EMPTY),
            Triple("short", "short", PassphraseProblem.TOO_SHORT),
            Triple("long enough 1", "long enough 2", PassphraseProblem.MISMATCH),
        )) {
            val first = a.toCharArray()
            val second = b.toCharArray()
            r.flow.submitExportPassphrase(first, second)
            assertEquals(KeyTransferStep.ExportPassphrase(problem), r.step)
            assertTrue(first.all { it == '\u0000' } && second.all { it == '\u0000' })
        }
        assertEquals("the key was not even read", 0, r.key.readPrivate)
        assertTrue(r.files.written.isEmpty())
    }

    // Export: the identity check ---------------------------------------------------------------------------------------

    @Test fun exportAsksForTheScreenLockBeforeAnythingElseAndGoesOnOnlyAfterSuccess() {
        val r = Rig()
        r.flow.startExport()
        assertEquals(KeyTransferStep.ExportExplain, r.step)
        assertTrue("nothing is asked before «Continue»", r.identity.asked.isEmpty())
        r.flow.explainContinue()
        assertEquals(listOf(IdentityPurpose.EXPORT), r.identity.asked)
        assertEquals("the prompt is up: no passphrase yet", KeyTransferStep.ExportAsking, r.step)
        r.identity.answer!!(AuthResult.Success)
        assertEquals(KeyTransferStep.ExportPassphrase(), r.step)
    }

    @Test fun cancellingTheExportPromptChangesNothing() {
        val r = Rig()
        r.flow.startExport(); r.flow.explainContinue()
        r.identity.answer!!(AuthResult.Cancelled)
        assertEquals(KeyTransferStep.Idle, r.step)
        assertNull(r.outcome)
        assertEquals(0, r.key.readPrivate)
        assertTrue(r.files.written.isEmpty())
        assertFalse(r.window.log.contains(true))
    }

    @Test fun aFailedExportPromptChangesNothingAndSaysSo() {
        val r = Rig()
        r.flow.startExport(); r.flow.explainContinue()
        r.identity.answer!!(AuthResult.Failed("too many attempts"))
        assertEquals(KeyTransferStep.Idle, r.step)
        assertEquals(KeyTransferOutcome.IdentityFailed("too many attempts"), r.outcome)
        assertEquals(0, r.key.readPrivate)
        assertTrue(r.files.written.isEmpty())
    }

    @Test fun withNoScreenLockExportWarnsAndContinuesOnlyOnExplicitConfirmation() {
        val r = Rig(lock = false)
        r.flow.startExport(); r.flow.explainContinue()
        assertEquals(KeyTransferStep.ExportNoLock, r.step)
        assertTrue("no prompt on a phone without a lock", r.identity.asked.isEmpty())
        r.flow.cancel()
        assertEquals(KeyTransferStep.Idle, r.step)
        r.flow.startExport(); r.flow.explainContinue()
        r.flow.exportWithoutLock()
        assertEquals(KeyTransferStep.ExportPassphrase(), r.step)
    }

    @Test fun anExportPromptAnsweredAfterCancelOrCloseDoesNothing() {
        val r = Rig()
        r.flow.startExport(); r.flow.explainContinue()
        r.flow.cancel()
        r.identity.answer!!(AuthResult.Success)
        assertEquals(KeyTransferStep.Idle, r.step)
        r.flow.startExport(); r.flow.explainContinue()
        r.flow.close()
        r.identity.answer!!(AuthResult.Success)
        assertEquals(KeyTransferStep.Idle, r.step)
        assertFalse(r.window.on)
    }

    @Test fun theStepsCannotBeSkipped() {
        val r = Rig()
        r.flow.explainContinue(); r.flow.exportWithoutLock()
        r.flow.submitExportPassphrase(phrase(), phrase())
        r.flow.saveTargetChosen("content://out")
        assertEquals(KeyTransferStep.Idle, r.step)
        assertEquals(0, r.key.readPrivate)
        assertTrue(r.files.written.isEmpty())
        assertTrue(r.identity.asked.isEmpty())
    }

    // Export: the file ------------------------------------------------------------------------------------------------

    @Test fun exportWritesOnlyTheEncryptedFileAndItRestoresToTheSameKey() {
        val r = Rig()
        val file = r.exportFile()
        assertEquals(KeyTransferOutcome.Exported, r.outcome)
        assertEquals(listOf("content://out"), r.files.written.keys.toList())
        assertFalse(String(file).contains(r.key.pair.privateKey.lines()[1]))
        val back = SshKeyExport.decryptEd25519(file, phrase(), "x")
        assertArrayEquals(seedOf(r.key.pair.privateKey), seedOf(back.privateKey))
        assertEquals(1, r.key.readPrivate)
        assertEquals(0, r.key.replaced)
    }

    @Test fun cancellingTheSavePickerWritesNothing() {
        val r = Rig()
        r.toExportPassphrase()
        r.flow.submitExportPassphrase(phrase(), phrase())
        assertEquals(KeyTransferStep.ExportSave("frp-jump-phone-key"), r.step)
        r.flow.saveTargetChosen(null)
        assertEquals(KeyTransferStep.Idle, r.step)
        assertNull(r.outcome)
        assertTrue(r.files.written.isEmpty())
    }

    @Test fun aFailedWriteSaysTheKeyWasNotExported() {
        val r = Rig()
        r.files.failWrite = true
        r.toExportPassphrase()
        r.flow.submitExportPassphrase(phrase(), phrase())
        r.flow.saveTargetChosen("content://out")
        assertEquals(KeyTransferOutcome.ExportWriteFailed, r.outcome)
        assertEquals(KeyTransferStep.Idle, r.step)
    }

    @Test fun theSubmittedExportPassphraseIsZeroed() {
        val r = Rig()
        r.toExportPassphrase()
        val first = phrase()
        val second = phrase()
        r.flow.submitExportPassphrase(first, second)
        assertTrue(first.all { it == '\u0000' } && second.all { it == '\u0000' })
    }

    // FLAG_SECURE ---------------------------------------------------------------------------------------------------------

    @Test fun theWindowIsSecureExactlyWhileAPassphraseDialogIsShown() {
        val r = Rig()
        r.flow.startExport()
        assertFalse(r.window.on)
        r.flow.explainContinue()
        assertFalse("not on the explanation or the prompt", r.window.on)
        r.identity.answer!!(AuthResult.Success)
        assertTrue("on with the export passphrase dialog", r.window.on)
        r.flow.submitExportPassphrase(phrase(), phrase())
        assertFalse("off as soon as it is answered", r.window.on)
        r.flow.saveTargetChosen("content://out")
        assertFalse(r.window.on)
        // restore
        val (_, file) = otherKeyFile()
        r.files.files["content://in"] = file
        r.flow.startRestore(); r.flow.fileChosen("content://in")
        assertTrue("on with the restore passphrase dialog", r.window.on)
        r.flow.submitRestorePassphrase("wrong wrong".toCharArray())
        assertTrue("still on for another try", r.window.on)
        r.flow.cancel()
        assertFalse("off after Cancel", r.window.on)
    }

    @Test fun theWindowLosesTheFlagWhenTheSheetClosesWithTheDialogUp() {
        val r = Rig()
        r.toExportPassphrase()
        assertTrue(r.window.on)
        r.flow.close()
        assertFalse(r.window.on)
    }

    @Test fun theFlagIsSetOnceAndClearedOncePerDialog() {
        val r = Rig()
        r.toExportPassphrase()
        r.flow.submitExportPassphrase("x".toCharArray(), "x".toCharArray())
        r.flow.submitExportPassphrase(phrase(), phrase())
        r.flow.cancel()
        assertEquals(listOf(true, false), r.window.log)
    }

    // Restore --------------------------------------------------------------------------------------------------------

    @Test fun restoreShowsBothFingerprintsAndReplacesOnlyAfterAFreshIdentityCheck() {
        val r = Rig()
        val (other, file) = otherKeyFile()
        val before = r.key.pair
        r.toRestoreConfirm(file)
        val confirm = r.step as KeyTransferStep.RestoreConfirm
        assertEquals(sshFingerprint(blobOfPublicKeyLine(before.publicLine)!!), confirm.current)
        assertEquals(sshFingerprint(blobOfPublicKeyLine(other.publicLine)!!), confirm.incoming)
        assertTrue("no check before «Replace»", r.identity.asked.isEmpty())
        r.flow.confirmReplace()
        assertEquals(listOf(IdentityPurpose.RESTORE), r.identity.asked)
        assertEquals("nothing replaced while the prompt is up", 0, r.key.replaced)
        r.identity.answer!!(AuthResult.Success)
        assertEquals(1, r.key.replaced)
        assertArrayEquals(seedOf(other.privateKey), seedOf(r.key.pair.privateKey))
        assertEquals(KeyTransferOutcome.Restored(confirm.incoming), r.outcome)
        assertEquals(listOf(r.key.pair.publicLine), r.replacedLines)
        assertEquals(KeyTransferStep.Idle, r.step)
    }

    @Test fun cancellingTheRestorePromptChangesNothing() {
        val r = Rig()
        val (_, file) = otherKeyFile()
        val before = r.key.pair
        r.toRestoreConfirm(file)
        r.flow.confirmReplace()
        r.identity.answer!!(AuthResult.Cancelled)
        assertEquals(0, r.key.replaced)
        assertEquals(before, r.key.pair)
        assertEquals(KeyTransferStep.Idle, r.step)
        assertTrue(r.replacedLines.isEmpty())
    }

    @Test fun aFailedRestorePromptChangesNothingAndSaysSo() {
        val r = Rig()
        val (_, file) = otherKeyFile()
        val before = r.key.pair
        r.toRestoreConfirm(file)
        r.flow.confirmReplace()
        r.identity.answer!!(AuthResult.Failed("locked out"))
        assertEquals(0, r.key.replaced)
        assertEquals(before, r.key.pair)
        assertEquals(KeyTransferOutcome.IdentityFailed("locked out"), r.outcome)
        assertEquals(KeyTransferStep.Idle, r.step)
    }

    @Test fun withNoScreenLockRestoreWarnsAndReplacesOnlyOnExplicitConfirmation() {
        val r = Rig(lock = false)
        val (other, file) = otherKeyFile()
        r.toRestoreConfirm(file)
        r.flow.confirmReplace()
        assertEquals(KeyTransferStep.RestoreNoLock, r.step)
        assertTrue(r.identity.asked.isEmpty())
        assertEquals(0, r.key.replaced)
        r.flow.cancel()
        assertEquals(0, r.key.replaced)
        r.toRestoreConfirm(file)
        r.flow.confirmReplace()
        r.flow.replaceWithoutLock()
        assertEquals(1, r.key.replaced)
        assertArrayEquals(seedOf(other.privateKey), seedOf(r.key.pair.privateKey))
    }

    @Test fun aRestorePromptAnsweredAfterCancelOrCloseReplacesNothing() {
        val r = Rig()
        val (_, file) = otherKeyFile()
        r.toRestoreConfirm(file)
        r.flow.confirmReplace()
        r.flow.close()
        r.identity.answer!!(AuthResult.Success)
        assertEquals(0, r.key.replaced)
    }

    @Test fun replaceCannotBeReachedWithoutTheConfirmation() {
        val r = Rig()
        r.flow.confirmReplace(); r.flow.replaceWithoutLock()
        val (_, file) = otherKeyFile()
        r.files.files["content://in"] = file
        r.flow.startRestore(); r.flow.fileChosen("content://in")
        r.flow.confirmReplace(); r.flow.replaceWithoutLock()
        assertEquals(0, r.key.replaced)
        assertTrue(r.identity.asked.isEmpty())
    }

    @Test fun aWrongPassphraseKeepsTheDialogAndChangesNothing() {
        val r = Rig()
        val (_, file) = otherKeyFile()
        val before = r.key.pair
        r.files.files["content://in"] = file
        r.flow.startRestore(); r.flow.fileChosen("content://in")
        val wrong = "wrong wrong".toCharArray()
        r.flow.submitRestorePassphrase(wrong)
        assertEquals(KeyTransferStep.RestorePassphrase(wrong = true), r.step)
        assertTrue(wrong.all { it == '\u0000' })
        assertEquals(before, r.key.pair)
        assertNull(r.outcome)
        r.flow.submitRestorePassphrase(phrase())
        assertTrue(r.step is KeyTransferStep.RestoreConfirm)
    }

    @Test fun aNonEd25519KeyIsRefusedWithoutAskingForAPassphrase() {
        val r = Rig()
        val pem = java.io.StringWriter().also { w ->
            val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            org.bouncycastle.util.io.pem.PemWriter(w).use { it.writeObject(org.bouncycastle.util.io.pem.PemObject("RSA PRIVATE KEY", pair.private.encoded)) }
        }.toString()
        r.files.files["content://in"] = pem.toByteArray()
        r.flow.startRestore(); r.flow.fileChosen("content://in")
        assertEquals(KeyTransferOutcome.Refused(KeyFileProblem.NOT_ED25519, "ssh-rsa"), r.outcome)
        assertEquals(KeyTransferStep.Idle, r.step)
        assertEquals(0, r.key.replaced)
        assertFalse(r.window.log.contains(true))
    }

    @Test fun aDamagedFileIsRefusedAndNothingChanges() {
        val r = Rig()
        val (_, file) = otherKeyFile()
        val before = r.key.pair
        r.files.files["content://in"] = file.copyOf(file.size / 2)
        r.flow.startRestore(); r.flow.fileChosen("content://in")
        assertEquals(KeyTransferOutcome.Refused(KeyFileProblem.DAMAGED, null), r.outcome)
        assertEquals(before, r.key.pair)
        assertEquals(0, r.key.replaced)
    }

    @Test fun anUnreadableFileSaysSoAndAPickerCancelIsSilent() {
        val r = Rig()
        r.flow.startRestore(); r.flow.fileChosen("content://missing")
        assertEquals(KeyTransferOutcome.FileUnreadable, r.outcome)
        r.flow.startRestore(); r.flow.fileChosen(null)
        assertNull(r.outcome)
        assertEquals(KeyTransferStep.Idle, r.step)
    }

    @Test fun restoringTheCurrentKeyChangesNothing() {
        val r = Rig()
        val file = r.exportFile()
        r.toRestoreConfirmOrSame(file)
        assertEquals(KeyTransferOutcome.SameKey, r.outcome)
        assertEquals(0, r.key.replaced)
        assertTrue(r.identity.asked.size == 1)
    }

    private fun Rig.toRestoreConfirmOrSame(file: ByteArray) {
        files.files["content://in"] = file
        flow.startRestore(); flow.fileChosen("content://in")
        flow.submitRestorePassphrase(phrase())
    }

    @Test fun cancellingTheConfirmationChangesNothing() {
        val r = Rig()
        val (_, file) = otherKeyFile()
        val before = r.key.pair
        r.toRestoreConfirm(file)
        r.flow.cancel()
        assertEquals(before, r.key.pair)
        assertEquals(KeyTransferStep.Idle, r.step)
        assertTrue(r.identity.asked.isEmpty())
    }

    @Test fun aFailedWriteLeavesTheOldKeyAndSaysSo() {
        val r = Rig()
        val (_, file) = otherKeyFile()
        val before = r.key.pair
        r.key.failReplace = true
        r.toRestoreConfirm(file)
        r.flow.confirmReplace()
        r.identity.answer!!(AuthResult.Success)
        assertEquals(KeyTransferOutcome.ReplaceFailed, r.outcome)
        assertEquals(before, r.key.pair)
        assertTrue(r.replacedLines.isEmpty())
    }

    @Test fun theRestorePassphraseIsZeroed() {
        val r = Rig()
        val (_, file) = otherKeyFile()
        r.files.files["content://in"] = file
        r.flow.startRestore(); r.flow.fileChosen("content://in")
        val p = phrase()
        r.flow.submitRestorePassphrase(p)
        assertTrue(p.all { it == '\u0000' })
    }

    @Test fun anUnencryptedOpenSshKeyIsRestoredWithoutAPassphraseDialog() {
        val r = Rig()
        val other = generateEd25519("other")
        r.files.files["content://in"] = other.privateKey.toByteArray()
        r.flow.startRestore(); r.flow.fileChosen("content://in")
        assertTrue(r.step is KeyTransferStep.RestoreConfirm)
        assertFalse(r.window.log.contains(true))
    }

    // Both ways -----------------------------------------------------------------------------------------------------

    @Test fun aKeyExportedFromOnePhoneRestoresOnAnotherWithTheSamePublicKey() {
        val a = Rig()
        val file = a.exportFile()
        val b = Rig()
        b.toRestoreConfirm(file)
        b.flow.confirmReplace()
        b.identity.answer!!(AuthResult.Success)
        assertArrayEquals(blobOfPublicKeyLine(a.key.pair.publicLine), blobOfPublicKeyLine(b.key.pair.publicLine))
        assertArrayEquals(seedOf(a.key.pair.privateKey), seedOf(b.key.pair.privateKey))
    }
}
