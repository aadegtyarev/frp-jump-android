package ru.adsrv.frpjump.ui.settings

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.lock.AppLock
import ru.adsrv.frpjump.lock.AuthResult
import ru.adsrv.frpjump.ssh.KeyFileProblem
import ru.adsrv.frpjump.ui.AppAlertDialog
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.SecretField

/*
 * The screens of K4's export and restore (the logic is KeyTransferFlow). No clipboard, no share, no log here: the
 * private half goes to the system «Save as» only.
 */

/** Seams a test replaces; null keeps the app's own. */
internal class KeyTransferSeams(
    val identity: IdentityCheck? = null,
    val window: SecureWindow? = null,
    val files: KeyFiles? = null,
    val rounds: Int? = null,
)

internal val LocalKeyTransferSeams = staticCompositionLocalOf { KeyTransferSeams() }

/** The system's screen lock, through the app's [AppLock] (the same BiometricPrompt as the app lock, always a fresh one). */
internal class AndroidIdentityCheck(
    private val activity: FragmentActivity,
    private val lock: AppLock,
    private val titles: (IdentityPurpose) -> Pair<String, String>,
) : IdentityCheck {
    override fun hasScreenLock(): Boolean = AppLock.hasScreenLock(activity)

    override fun ask(purpose: IdentityPurpose, onResult: (AuthResult) -> Unit) {
        val (title, subtitle) = titles(purpose)
        lock.authenticate(activity, title, subtitle, onResult)
    }
}

/**
 * FLAG_SECURE on the activity's window while a passphrase dialog is up. A window that was secure before (Settings ›
 * Hide in Recents on Android 12 and older) stays secure after.
 */
internal class AndroidSecureWindow(private val window: Window) : SecureWindow {
    private var on = false
    private var wasSecure = false

    override fun secure(on: Boolean) {
        if (on == this.on) return
        this.on = on
        if (on) {
            wasSecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else if (!wasSecure) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** The system picker's documents. */
internal class ContentKeyFiles(private val context: Context) : KeyFiles {
    override fun write(target: String, bytes: ByteArray): Boolean {
        val uri = Uri.parse(target)
        return try {
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: return false
            out.use {
                it.write(bytes)
                it.flush()
            }
            true
        } catch (e: Exception) {
            // The picker made an empty document: do not leave it behind as if it held the key.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            false
        }
    }

    override fun read(source: String): ByteArray? = try {
        context.contentResolver.openInputStream(Uri.parse(source))?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (out.size() <= MAX_KEY_FILE_BYTES) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            if (out.size() > MAX_KEY_FILE_BYTES) null else out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        /** An OpenSSH ed25519 file is under a kilobyte; anything this large is not one. */
        const val MAX_KEY_FILE_BYTES = 16 * 1024
    }
}

internal fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}

/**
 * The flow for the open sheet, made once and closed (wiped, FLAG_SECURE off) when the sheet leaves. Null when there is
 * no activity to ask for a screen lock.
 */
@Composable
internal fun rememberKeyTransferFlow(vm: SettingsViewModel): KeyTransferFlow? {
    val context = LocalContext.current
    val seams = LocalKeyTransferSeams.current
    val scope = rememberCoroutineScope()
    val activity = context.fragmentActivity()
    val titleId = stringResource(R.string.key_identity_title)
    val exportSubtitle = stringResource(R.string.key_identity_export)
    val restoreSubtitle = stringResource(R.string.key_identity_restore)
    val flow = remember(vm, activity) {
        if (activity == null) {
            null
        } else {
            KeyTransferFlow(
                key = vm.phoneKey,
                identity = seams.identity ?: AndroidIdentityCheck(activity, vm.appLock) { purpose ->
                    titleId to if (purpose == IdentityPurpose.EXPORT) exportSubtitle else restoreSubtitle
                },
                window = seams.window ?: AndroidSecureWindow(activity.window),
                files = seams.files ?: ContentKeyFiles(context.applicationContext),
                scope = scope,
                work = Dispatchers.Default,
                onReplaced = vm::phoneKeyReplaced,
                rounds = seams.rounds ?: ru.adsrv.frpjump.ssh.SshKeyExport.ROUNDS,
            )
        }
    }
    // The app out of sight ends the owner's dialog: back again, the next export asks for a fresh identity check.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(flow, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) flow?.onStopped() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            flow?.close()
        }
    }
    return flow
}

/** The result line under the key buttons. */
@Composable
internal fun KeyTransferOutcomeLine(flow: KeyTransferFlow) {
    val outcome by flow.outcome.collectAsState()
    val colors = AppTheme.colors
    val done = outcome is KeyTransferOutcome.Exported || outcome is KeyTransferOutcome.Restored || outcome is KeyTransferOutcome.SameKey
    outcome?.let {
        Text(
            outcomeText(it),
            color = if (done) colors.muted else colors.error,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.testTag("key-transfer-outcome"),
        )
    }
}

@Composable
private fun outcomeText(outcome: KeyTransferOutcome): String = when (outcome) {
    KeyTransferOutcome.Exported -> stringResource(R.string.key_export_done)
    KeyTransferOutcome.ExportWriteFailed -> stringResource(R.string.key_export_write_failed)
    KeyTransferOutcome.ExportFailed -> stringResource(R.string.key_export_failed)
    is KeyTransferOutcome.IdentityFailed -> stringResource(R.string.key_identity_failed, outcome.message)
    KeyTransferOutcome.FileUnreadable -> stringResource(R.string.key_file_unreadable)
    is KeyTransferOutcome.Refused -> when (outcome.problem) {
        KeyFileProblem.NOT_ED25519 ->
            outcome.keyType?.let { stringResource(R.string.key_restore_not_ed25519, it.removePrefix("ssh-")) } ?: stringResource(R.string.key_restore_not_openssh)
        KeyFileProblem.UNSUPPORTED -> stringResource(R.string.key_restore_unsupported)
        else -> stringResource(R.string.key_restore_damaged)
    }
    KeyTransferOutcome.SameKey -> stringResource(R.string.key_restore_same)
    is KeyTransferOutcome.Restored -> stringResource(R.string.key_restore_done, shortPrint(outcome.fingerprint))
    KeyTransferOutcome.ReplaceFailed -> stringResource(R.string.key_restore_replace_failed)
}

/** "SHA256:Fymb…7U8". */
private fun shortPrint(print: String): String = if (print.length > 18) print.take(11) + "…" + print.takeLast(3) else print

/** The dialogs and system pickers of the current step. */
@Composable
internal fun KeyTransferHost(flow: KeyTransferFlow) {
    val step by flow.step.collectAsState()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        flow.saveTargetChosen(uri?.toString())
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        flow.fileChosen(uri?.toString())
    }
    // Each step opens its picker once.
    LaunchedEffect(step) {
        when (val s = step) {
            is KeyTransferStep.ExportSave -> saver.launch(s.suggestedName)
            KeyTransferStep.RestorePick -> opener.launch(arrayOf("*/*"))
            else -> Unit
        }
    }
    when (val s = step) {
        KeyTransferStep.ExportExplain -> ExplainDialog(onContinue = flow::explainContinue, onCancel = flow::cancel)
        KeyTransferStep.ExportNoLock -> NoLockDialog(
            body = R.string.key_no_lock_export, go = R.string.key_no_lock_export_go, onGo = flow::exportWithoutLock, onCancel = flow::cancel,
        )
        is KeyTransferStep.ExportPassphrase -> ExportPassphraseDialog(s.problem, flow::submitExportPassphrase, flow::cancel)
        is KeyTransferStep.RestorePassphrase -> RestorePassphraseDialog(s.wrong, flow::submitRestorePassphrase, flow::cancel)
        is KeyTransferStep.RestoreConfirm -> ConfirmReplaceDialog(s, onReplace = flow::confirmReplace, onCancel = flow::cancel)
        KeyTransferStep.RestoreNoLock -> NoLockDialog(
            body = R.string.key_no_lock_restore, go = R.string.key_no_lock_restore_go, onGo = flow::replaceWithoutLock, onCancel = flow::cancel,
        )
        is KeyTransferStep.Working -> WorkingDialog(if (s.cancellable) flow::cancel else null)
        else -> Unit
    }
}

@Composable
private fun ScrollingText(content: @Composable () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun ExplainDialog(onContinue: () -> Unit, onCancel: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.key_export_title)) },
        text = {
            ScrollingText {
                Text(stringResource(R.string.key_export_what))
                Text(stringResource(R.string.key_export_risk), fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.key_export_keep))
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.key_transfer_continue)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NoLockDialog(body: Int, go: Int, onGo: () -> Unit, onCancel: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.key_no_lock_title)) },
        text = { ScrollingText { Text(stringResource(body)) } },
        confirmButton = { TextButton(onClick = onGo) { Text(stringResource(go)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * No password manager is offered the passphrase or asked to save it: the dialog's view and everything in it is left out
 * of autofill.
 */
@Composable
private fun NoAutofill() {
    val view = LocalView.current
    SideEffect { view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
}

/** Both a passphrase dialog's window and the activity's (see [SecureWindow]) are secure while it is up. */
private val SecureDialog = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

@Composable
private fun ExportPassphraseDialog(problem: PassphraseProblem?, onSave: (CharArray, CharArray) -> Unit, onCancel: () -> Unit) {
    // Plain remember: a passphrase is never saved with the screen's state. A String cannot be wiped; the arrays made of it are.
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    AppAlertDialog(
        onDismissRequest = onCancel,
        properties = SecureDialog,
        title = { Text(stringResource(R.string.key_export_phrase_title)) },
        text = {
            NoAutofill()
            ScrollingText {
                Text(stringResource(R.string.key_export_phrase_hint))
                SecretField(first, { first = it }, stringResource(R.string.key_export_phrase), noPersonalizedLearning = true)
                SecretField(second, { second = it }, stringResource(R.string.key_export_phrase_again), noPersonalizedLearning = true)
                problem?.let {
                    Text(
                        stringResource(
                            when (it) {
                                PassphraseProblem.EMPTY -> R.string.key_export_phrase_empty
                                PassphraseProblem.TOO_SHORT -> R.string.key_export_phrase_short
                                PassphraseProblem.MISMATCH -> R.string.key_export_phrase_mismatch
                            },
                            MIN_PASSPHRASE,
                        ),
                        color = AppTheme.colors.error,
                        fontSize = 13.sp,
                        modifier = Modifier.testTag("key-export-phrase-problem"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(first.toCharArray(), second.toCharArray())
                // The text is not kept: after a refusal both fields are typed again.
                first = ""
                second = ""
            }) { Text(stringResource(R.string.key_export_save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RestorePassphraseDialog(wrong: Boolean, onOpen: (CharArray) -> Unit, onCancel: () -> Unit) {
    var phrase by remember { mutableStateOf("") }
    AppAlertDialog(
        onDismissRequest = onCancel,
        properties = SecureDialog,
        title = { Text(stringResource(R.string.key_restore_phrase_title)) },
        text = {
            NoAutofill()
            ScrollingText {
                SecretField(phrase, { phrase = it }, stringResource(R.string.key_export_phrase), noPersonalizedLearning = true)
                if (wrong) {
                    Text(
                        stringResource(R.string.key_restore_wrong_phrase),
                        color = AppTheme.colors.error,
                        fontSize = 13.sp,
                        modifier = Modifier.testTag("key-restore-phrase-problem"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onOpen(phrase.toCharArray())
                phrase = ""
            }) { Text(stringResource(R.string.key_restore_open)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmReplaceDialog(step: KeyTransferStep.RestoreConfirm, onReplace: () -> Unit, onCancel: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.key_restore_confirm_title)) },
        text = {
            ScrollingText {
                Text(stringResource(R.string.key_restore_now), fontWeight = FontWeight.Medium)
                Text(step.current, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.testTag("key-restore-fp-current"))
                Text(stringResource(R.string.key_restore_from_file), fontWeight = FontWeight.Medium)
                Text(step.incoming, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.testTag("key-restore-fp-file"))
                Text(stringResource(R.string.key_restore_confirm_body))
            }
        },
        confirmButton = { TextButton(onClick = onReplace) { Text(stringResource(R.string.replace_key)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun WorkingDialog(onCancel: (() -> Unit)?) {
    AppAlertDialog(
        onDismissRequest = { onCancel?.invoke() },
        properties = DialogProperties(dismissOnBackPress = onCancel != null, dismissOnClickOutside = false),
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.key_transfer_working))
            }
        },
        confirmButton = {},
        dismissButton = onCancel?.let { cancel -> { TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) } } },
    )
}
