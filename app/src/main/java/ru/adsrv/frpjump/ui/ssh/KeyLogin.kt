package ru.adsrv.frpjump.ui.ssh

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ssh.HostKeyCheck
import ru.adsrv.frpjump.ssh.KeyInstallResult
import ru.adsrv.frpjump.ssh.KeyInstallTarget
import ru.adsrv.frpjump.ssh.KeyInstaller
import ru.adsrv.frpjump.ssh.PinnedHostKey
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.SshLogin
import ru.adsrv.frpjump.ssh.keyInstallText
import ru.adsrv.frpjump.ui.AppTheme

/** Where "Use key login" is: the dialog it shows, if any. */
sealed interface KeyLoginStep {
    val target: KeyInstallTarget

    class Working(override val target: KeyInstallTarget) : KeyLoginStep

    class AskPassword(override val target: KeyInstallTarget, val refused: Boolean) : KeyLoginStep

    class HostKey(override val target: KeyInstallTarget, val check: HostKeyCheck, val presented: PinnedHostKey) : KeyLoginStep

    class Done(override val target: KeyInstallTarget, val result: KeyInstallResult) : KeyLoginStep
}

/**
 * "Use key login" / «Вход по ключу» for one screen: runs [installer] in
 * [scope] (the app's, so it finishes when the screen goes) and keeps the
 * step its dialogs show. Main thread.
 */
@Stable
class KeyLoginFlow(private val installer: KeyInstaller, private val scope: CoroutineScope) {
    var step by mutableStateOf<KeyLoginStep?>(null)
        private set

    /** The password typed for this run, kept for a retry after the host key is trusted. */
    private var typed: String? = null

    /** The run going; a result of one cancelled or replaced is dropped. */
    private var job: Job? = null

    fun start(target: KeyInstallTarget) {
        if (step != null) return
        typed = null
        run(target)
    }

    fun password(user: String, password: String) {
        val s = step as? KeyLoginStep.AskPassword ?: return
        typed = password
        run(s.target.withUser(user.trim().ifEmpty { s.target.user }))
    }

    /** "Trust" or "Forget the old key", then try again. */
    fun hostKeyAnswered() {
        val s = step as? KeyLoginStep.HostKey ?: return
        step = KeyLoginStep.Working(s.target)
        job = scope.launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) {
                when (s.check) {
                    is HostKeyCheck.Changed -> s.target.hostKeys.forget(s.target.device, s.target.port)
                    else -> s.target.hostKeys.pin(s.target.device, s.target.port, s.presented)
                }
            }
            run(s.target)
        }
    }

    /** "Retry" after a failure: from the start, as the first tap. */
    fun retry() {
        val s = step as? KeyLoginStep.Done ?: return
        typed = null
        run(s.target)
    }

    fun dismiss() {
        typed = null
        step = null
    }

    /** "Cancel" while it works: its connections close, nothing is saved, the dialog goes. */
    fun cancel() {
        job?.cancel()
        job = null
        installer.cancel()
        dismiss()
    }

    private fun run(target: KeyInstallTarget) {
        step = KeyLoginStep.Working(target)
        job = scope.launch(Dispatchers.Main) {
            val result = installer.install(target, typed)
            // Every change of the step on the main thread, and only by the run still wanted.
            if (!isActive) return@launch
            step = when (result) {
                is KeyInstallResult.PasswordNeeded -> KeyLoginStep.AskPassword(target, result.refused)
                is KeyInstallResult.HostKey -> KeyLoginStep.HostKey(target, result.check, result.presented)
                else -> {
                    typed = null
                    KeyLoginStep.Done(target, result)
                }
            }
        }
    }
}

/** The dialogs of [flow]: progress, the password, the host key, and what came of it. */
@Composable
fun KeyLoginDialogs(flow: KeyLoginFlow) {
    val context = LocalContext.current
    val step = flow.step ?: return
    val t = step.target
    val where = "${t.device}:${t.port}"
    val title = stringResource(R.string.key_login_title, where)
    when (step) {
        is KeyLoginStep.Working -> AlertDialog(
            // Bounded by the connect and command timeouts; Cancel closes the connection.
            onDismissRequest = {},
            title = { Text(title) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = AppTheme.colors.accent, strokeWidth = 2.dp)
                    Text(stringResource(R.string.key_install_working))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = flow::cancel) { Text(stringResource(R.string.cancel)) } },
        )
        is KeyLoginStep.AskPassword -> SshLoginDialog(
            target = where,
            initial = SshLogin(t.user, SshAuthMethod.PASSWORD),
            hasRememberedPassword = { false },
            onConnect = { user, _, password, _ -> flow.password(user, password) },
            onDismiss = flow::dismiss,
            title = title,
            passwordOnly = true,
            note = stringResource(R.string.key_install_password_note),
            problem = if (step.refused) stringResource(R.string.ssh_password_refused, t.user) else null,
            confirmLabel = stringResource(R.string.key_install_confirm),
        )
        is KeyLoginStep.HostKey -> when (val check = step.check) {
            is HostKeyCheck.Changed -> HostKeyChangedDialog(where, check.pinned, step.presented, onForget = flow::hostKeyAnswered, onCancel = flow::dismiss)
            else -> HostKeyTrustDialog(where, step.presented, onTrust = flow::hostKeyAnswered, onCancel = flow::dismiss)
        }
        is KeyLoginStep.Done -> {
            val text = keyInstallText(step.result, t.user) ?: return
            val message = context.getString(text.res, *text.args.toTypedArray())
            if (text.ok) {
                // Success needs no answer: a toast, and the menu no longer offers it.
                LaunchedEffect(step) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    flow.dismiss()
                }
            } else {
                AlertDialog(
                    onDismissRequest = flow::dismiss,
                    title = { Text(title) },
                    text = { Text(message) },
                    confirmButton = { TextButton(onClick = flow::retry) { Text(stringResource(R.string.retry)) } },
                    dismissButton = { TextButton(onClick = flow::dismiss) { Text(stringResource(R.string.close)) } },
                )
            }
        }
    }
}
