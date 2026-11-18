package ru.adsrv.frpjump.ui.settings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.lock.AuthResult
import ru.adsrv.frpjump.ssh.KeyFileException
import ru.adsrv.frpjump.ssh.KeyFileProblem
import ru.adsrv.frpjump.ssh.SshKeyExport
import ru.adsrv.frpjump.ssh.SshKeyPair
import ru.adsrv.frpjump.ssh.blobOfPublicKeyLine
import ru.adsrv.frpjump.ssh.sshFingerprint
import java.security.SecureRandom

/*
 * Export and restore of the phone's SSH key (К4, docs/contracts/features/ssh-key-export-k4.md).
 *
 * The private half leaves the app by one path only: the passphrase-encrypted file the owner saves through the system
 * «Save as». Nothing in this file or in KeyTransferDialogs.kt copies, shares, logs or hands key material to anything
 * else (KeyTransferReachTest reads the sources to keep it so). The assistant has no way in: nothing here is reachable
 * from `assistant/`.
 */

/** Why a new passphrase is refused. */
enum class PassphraseProblem { EMPTY, TOO_SHORT, MISMATCH }

/** The passphrase rules of the export file: not empty, at least [MIN_PASSPHRASE] characters, typed twice the same. */
fun checkNewPassphrase(passphrase: CharArray, again: CharArray): PassphraseProblem? = when {
    passphrase.isEmpty() -> PassphraseProblem.EMPTY
    passphrase.size < MIN_PASSPHRASE -> PassphraseProblem.TOO_SHORT
    !passphrase.contentEquals(again) -> PassphraseProblem.MISMATCH
    else -> null
}

const val MIN_PASSPHRASE = 8

/** The default name offered by the system «Save as»: nothing about devices, profiles or fingerprints. */
const val KEY_FILE_NAME = "frp-jump-phone-key"

/** What the flow does for a screen lock check: seam, so a test drives success, cancel, failure and "no lock". */
interface IdentityCheck {
    /** A PIN, pattern, password or biometric is set on the phone. */
    fun hasScreenLock(): Boolean

    /** A fresh system prompt; the answer comes once, on the main thread. */
    fun ask(purpose: IdentityPurpose, onResult: (AuthResult) -> Unit)
}

enum class IdentityPurpose { EXPORT, RESTORE }

/** FLAG_SECURE on the window, a seam: the flow sets it while a passphrase dialog is up and clears it after. */
interface SecureWindow {
    fun secure(on: Boolean)
}

/** The phone key in its slot. Off the main thread: it may unseal with the Keystore. */
interface PhoneKey {
    fun publicLine(): String

    /** The unencrypted OpenSSH text, for export only; null when it cannot be read. */
    fun privateKeyText(): String?

    /** The comment of the phone's public line. */
    fun comment(): String

    /** The pair becomes the phone key, sealed as a generated key is; throws and changes nothing when the write fails. */
    fun replace(pair: SshKeyPair)
}

/** The picked file's two ends, by the system picker's URI as text. Off the main thread. */
interface KeyFiles {
    fun write(target: String, bytes: ByteArray): Boolean

    /** The file's bytes; null when unreadable or too large for a key. */
    fun read(source: String): ByteArray?
}

sealed interface KeyTransferStep {
    data object Idle : KeyTransferStep

    /** What the file is and what holding it means: first, before anything else. */
    data object ExportExplain : KeyTransferStep
    data object ExportNoLock : KeyTransferStep

    /** The identity prompt is up before an export. */
    data object ExportAsking : KeyTransferStep

    /** The passphrase dialog; [problem] is shown under the field. */
    data class ExportPassphrase(val problem: PassphraseProblem? = null) : KeyTransferStep

    /** The file is ready in memory (encrypted); the system «Save as» is to open. */
    data class ExportSave(val suggestedName: String) : KeyTransferStep
    data object RestorePick : KeyTransferStep
    data class RestorePassphrase(val wrong: Boolean = false) : KeyTransferStep

    /** The two fingerprints, in full. */
    data class RestoreConfirm(val current: String, val incoming: String) : KeyTransferStep

    /** The identity prompt is up after «Replace». */
    data object RestoreAsking : KeyTransferStep
    data object RestoreNoLock : KeyTransferStep

    /** Work off the main thread (key derivation takes a moment); a dialog says so. */
    class Working : KeyTransferStep
}

/** The one-line result shown at the bottom of the sheet. */
sealed interface KeyTransferOutcome {
    data object Exported : KeyTransferOutcome
    data object ExportWriteFailed : KeyTransferOutcome
    data object ExportFailed : KeyTransferOutcome
    data class IdentityFailed(val message: String) : KeyTransferOutcome
    data object FileUnreadable : KeyTransferOutcome

    /** Restore refused: NOT_ED25519, DAMAGED or UNSUPPORTED ([keyType] as the file names it); a wrong passphrase stays in its dialog. */
    data class Refused(val problem: KeyFileProblem, val keyType: String?) : KeyTransferOutcome
    data object SameKey : KeyTransferOutcome
    data class Restored(val fingerprint: String) : KeyTransferOutcome
    data object ReplaceFailed : KeyTransferOutcome
}

/**
 * The steps of export and restore. Main thread; the slow parts run on [work]. One flow per open sheet: [close] wipes
 * what it holds. Every answer that arrives for a step that is no longer current (the owner cancelled, the sheet
 * closed) is dropped, so a late prompt result or a late file never changes anything.
 */
class KeyTransferFlow(
    private val key: PhoneKey,
    private val identity: IdentityCheck,
    private val window: SecureWindow,
    private val files: KeyFiles,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val onReplaced: (publicLine: String) -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
    private val rounds: Int = SshKeyExport.ROUNDS,
) {
    private val _step = MutableStateFlow<KeyTransferStep>(KeyTransferStep.Idle)
    val step: StateFlow<KeyTransferStep> = _step

    private val _outcome = MutableStateFlow<KeyTransferOutcome?>(null)
    val outcome: StateFlow<KeyTransferOutcome?> = _outcome

    private var closed = false
    private var secureOn = false

    /** The encrypted export file, until the owner saves it or cancels. */
    private var exportFile: ByteArray? = null

    /** The file picked for restore, until its key is read. */
    private var restoreFile: ByteArray? = null

    /** The key read from the file, until «Replace» or cancel. */
    private var incoming: SshKeyPair? = null

    private fun set(next: KeyTransferStep) {
        _step.value = next
        val want = next is KeyTransferStep.ExportPassphrase || next is KeyTransferStep.RestorePassphrase
        if (want != secureOn) {
            secureOn = want
            window.secure(want)
        }
    }

    private fun wipe() {
        exportFile?.fill(0)
        exportFile = null
        restoreFile?.fill(0)
        restoreFile = null
        incoming = null
    }

    /** The owner backed out, or the sheet closed: nothing is written, nothing replaced. */
    fun cancel() {
        wipe()
        set(KeyTransferStep.Idle)
    }

    fun close() {
        closed = true
        wipe()
        set(KeyTransferStep.Idle)
    }

    fun clearOutcome() {
        _outcome.value = null
    }

    // Export ---------------------------------------------------------------------------------------------------------

    fun startExport() {
        if (closed || _step.value != KeyTransferStep.Idle) return
        _outcome.value = null
        set(KeyTransferStep.ExportExplain)
    }

    /** «Continue» on the explanation: the identity check, before anything is asked or written. */
    fun explainContinue() {
        if (closed || _step.value != KeyTransferStep.ExportExplain) return
        if (!identity.hasScreenLock()) {
            set(KeyTransferStep.ExportNoLock)
            return
        }
        val asking = KeyTransferStep.ExportAsking
        set(asking)
        identity.ask(IdentityPurpose.EXPORT) { result ->
            if (closed || _step.value !== asking) return@ask
            when (result) {
                AuthResult.Success -> set(KeyTransferStep.ExportPassphrase())
                AuthResult.Cancelled -> set(KeyTransferStep.Idle)
                is AuthResult.Failed -> {
                    _outcome.value = KeyTransferOutcome.IdentityFailed(result.message)
                    set(KeyTransferStep.Idle)
                }
            }
        }
    }

    /** «Export anyway» on the no-lock warning. */
    fun exportWithoutLock() {
        if (closed || _step.value != KeyTransferStep.ExportNoLock) return
        set(KeyTransferStep.ExportPassphrase())
    }

    /** Both arrays are zeroed here, whatever happens. */
    fun submitExportPassphrase(passphrase: CharArray, again: CharArray) {
        if (closed || _step.value !is KeyTransferStep.ExportPassphrase) {
            passphrase.fill('\u0000'); again.fill('\u0000')
            return
        }
        val problem = checkNewPassphrase(passphrase, again)
        again.fill('\u0000')
        if (problem != null) {
            passphrase.fill('\u0000')
            set(KeyTransferStep.ExportPassphrase(problem))
            return
        }
        val working = KeyTransferStep.Working()
        set(working)
        scope.launch {
            val file = withContext(work) {
                try {
                    val text = key.privateKeyText()
                    if (text == null) null else SshKeyExport.encryptPhoneKey(text, key.comment(), passphrase, random, rounds)
                } catch (e: Exception) {
                    null
                } finally {
                    passphrase.fill('\u0000')
                }
            }
            if (closed || _step.value !== working) {
                file?.fill(0)
                return@launch
            }
            if (file == null) {
                _outcome.value = KeyTransferOutcome.ExportFailed
                set(KeyTransferStep.Idle)
            } else {
                exportFile = file
                set(KeyTransferStep.ExportSave(KEY_FILE_NAME))
            }
        }
    }

    /** The system «Save as» answered: [target] is the chosen document, null when the owner cancelled. */
    fun saveTargetChosen(target: String?) {
        val current = _step.value
        if (closed || current !is KeyTransferStep.ExportSave) return
        val file = exportFile
        if (target == null || file == null) {
            cancel()
            return
        }
        val working = KeyTransferStep.Working()
        set(working)
        scope.launch {
            val written = withContext(work) { runCatching { files.write(target, file) }.getOrDefault(false) }
            if (closed || _step.value !== working) return@launch
            wipe()
            _outcome.value = if (written) KeyTransferOutcome.Exported else KeyTransferOutcome.ExportWriteFailed
            set(KeyTransferStep.Idle)
        }
    }

    // Restore --------------------------------------------------------------------------------------------------------

    fun startRestore() {
        if (closed || _step.value != KeyTransferStep.Idle) return
        _outcome.value = null
        set(KeyTransferStep.RestorePick)
    }

    /** The system picker answered: [source] is the chosen document, null when the owner cancelled. */
    fun fileChosen(source: String?) {
        if (closed || _step.value != KeyTransferStep.RestorePick) return
        if (source == null) {
            cancel()
            return
        }
        val working = KeyTransferStep.Working()
        set(working)
        scope.launch {
            val read = withContext(work) {
                try {
                    val bytes = files.read(source)
                    if (bytes == null) {
                        Read.Unreadable
                    } else {
                        try {
                            Read.Opened(bytes, SshKeyExport.inspect(bytes).encrypted)
                        } catch (e: KeyFileException) {
                            bytes.fill(0)
                            Read.Refused(e)
                        }
                    }
                } catch (e: Exception) {
                    Read.Unreadable
                }
            }
            if (closed || _step.value !== working) {
                (read as? Read.Opened)?.bytes?.fill(0)
                return@launch
            }
            when (read) {
                Read.Unreadable -> finish(KeyTransferOutcome.FileUnreadable)
                is Read.Refused -> finish(KeyTransferOutcome.Refused(read.problem.problem, read.problem.keyType))
                is Read.Opened -> {
                    restoreFile = read.bytes
                    if (read.encrypted) set(KeyTransferStep.RestorePassphrase()) else open(CharArray(0))
                }
            }
        }
    }

    private sealed interface Read {
        data object Unreadable : Read
        class Refused(val problem: KeyFileException) : Read
        class Opened(val bytes: ByteArray, val encrypted: Boolean) : Read
    }

    private fun finish(outcome: KeyTransferOutcome) {
        wipe()
        _outcome.value = outcome
        set(KeyTransferStep.Idle)
    }

    /** The restore passphrase; zeroed here, whatever happens. */
    fun submitRestorePassphrase(passphrase: CharArray) {
        if (closed || _step.value !is KeyTransferStep.RestorePassphrase) {
            passphrase.fill('\u0000')
            return
        }
        open(passphrase)
    }

    private fun open(passphrase: CharArray) {
        val file = restoreFile
        if (file == null) {
            passphrase.fill('\u0000')
            cancel()
            return
        }
        val working = KeyTransferStep.Working()
        set(working)
        scope.launch {
            val result = withContext(work) {
                try {
                    val pair = SshKeyExport.decryptEd25519(file, passphrase, key.comment())
                    val now = key.publicLine()
                    Opened.Key(pair, fingerprintOf(now), fingerprintOf(pair.publicLine))
                } catch (e: KeyFileException) {
                    Opened.Refused(e)
                } catch (e: Exception) {
                    Opened.Refused(KeyFileException(KeyFileProblem.DAMAGED))
                } finally {
                    passphrase.fill('\u0000')
                }
            }
            if (closed || _step.value !== working) return@launch
            when (result) {
                is Opened.Key -> if (result.current == result.incoming) {
                    finish(KeyTransferOutcome.SameKey)
                } else {
                    incoming = result.pair
                    restoreFile?.fill(0)
                    restoreFile = null
                    set(KeyTransferStep.RestoreConfirm(result.current, result.incoming))
                }
                is Opened.Refused -> if (result.problem.problem == KeyFileProblem.WRONG_PASSPHRASE) {
                    set(KeyTransferStep.RestorePassphrase(wrong = true))
                } else {
                    finish(KeyTransferOutcome.Refused(result.problem.problem, result.problem.keyType))
                }
            }
        }
    }

    private sealed interface Opened {
        class Key(val pair: SshKeyPair, val current: String, val incoming: String) : Opened
        class Refused(val problem: KeyFileException) : Opened
    }

    /** «Replace»: a fresh identity check at once; only its success replaces the key. */
    fun confirmReplace() {
        if (closed || _step.value !is KeyTransferStep.RestoreConfirm) return
        if (!identity.hasScreenLock()) {
            set(KeyTransferStep.RestoreNoLock)
            return
        }
        val asking = KeyTransferStep.RestoreAsking
        set(asking)
        identity.ask(IdentityPurpose.RESTORE) { result ->
            if (closed || _step.value !== asking) return@ask
            when (result) {
                AuthResult.Success -> replace()
                AuthResult.Cancelled -> cancel()
                is AuthResult.Failed -> {
                    wipe()
                    _outcome.value = KeyTransferOutcome.IdentityFailed(result.message)
                    set(KeyTransferStep.Idle)
                }
            }
        }
    }

    /** «Replace anyway» on the no-lock warning. */
    fun replaceWithoutLock() {
        if (closed || _step.value != KeyTransferStep.RestoreNoLock) return
        replace()
    }

    private fun replace() {
        val pair = incoming
        if (pair == null) {
            cancel()
            return
        }
        val working = KeyTransferStep.Working()
        set(working)
        scope.launch {
            val done = withContext(work) { runCatching { key.replace(pair) }.isSuccess }
            if (closed || _step.value !== working) return@launch
            if (done) {
                val print = fingerprintOf(pair.publicLine)
                finish(KeyTransferOutcome.Restored(print))
                onReplaced(pair.publicLine)
            } else {
                finish(KeyTransferOutcome.ReplaceFailed)
            }
        }
    }

    private companion object {
        fun fingerprintOf(publicLine: String): String = blobOfPublicKeyLine(publicLine)?.let(::sshFingerprint) ?: ""
    }
}
