package ru.adsrv.frpjump.ui.ssh

import ru.adsrv.frpjump.ui.AppAlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ssh.HostKeyCheck
import ru.adsrv.frpjump.ssh.hostKeyWriteFailure
import ru.adsrv.frpjump.ssh.hostKeyWriteDetail
import ru.adsrv.frpjump.ssh.KeyInstallResult
import ru.adsrv.frpjump.ssh.KeyInstallTarget
import ru.adsrv.frpjump.ssh.KeyInstalling
import ru.adsrv.frpjump.ssh.LoginRefusal
import ru.adsrv.frpjump.ssh.PinnedHostKey
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.SshKeyChoice
import ru.adsrv.frpjump.ssh.SshKeySlot
import ru.adsrv.frpjump.ssh.SshLogin
import ru.adsrv.frpjump.ssh.keyInstallText
import ru.adsrv.frpjump.ssh.loginRefusalText
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.notice
import ru.adsrv.frpjump.ui.settings.keySummary

/** Where "Use key login" is: the dialog it shows, if any. */
sealed interface KeyLoginStep {
    val target: KeyInstallTarget

    /** Two keys to pick from ([keys], the phone's first); asked before anything runs. */
    class Choose(override val target: KeyInstallTarget, val keys: List<SshKeyChoice>) : KeyLoginStep

    class Working(override val target: KeyInstallTarget) : KeyLoginStep

    /** [refusal]: why the password before did not do, if one was tried. */
    class AskPassword(override val target: KeyInstallTarget, val refusal: LoginRefusal?) : KeyLoginStep

    class HostKey(override val target: KeyInstallTarget, val check: HostKeyCheck, val presented: PinnedHostKey) : KeyLoginStep

    class Done(override val target: KeyInstallTarget, val result: KeyInstallResult) : KeyLoginStep
}

/**
 * "Use key login" / «Вход по ключу» for one screen: runs [installer] in
 * [scope] (the app's, so it finishes when the screen goes) and keeps the
 * step its dialogs show. Main thread.
 */
@Stable
class KeyLoginFlow(
    private val installer: KeyInstalling,
    private val scope: CoroutineScope,
    /** Where every change of [step] happens; the main thread, a test's own in tests. */
    private val main: CoroutineDispatcher = Dispatchers.Main,
) {
    var step by mutableStateOf<KeyLoginStep?>(null)
        private set

    /** The password typed for this run, kept for a retry after the host key is trusted. */
    private var typed: String? = null

    /** The run going; a result of one cancelled or replaced is dropped. */
    private var job: Job? = null

    /**
     * With two keys, asks which first ([choose]); with one, goes straight to
     * installing it, as before there were two. The keys are read off the
     * main thread (the phone's is made and sealed on first use); when that
     * fails the run goes on without a choice and reports the error itself.
     */
    fun start(target: KeyInstallTarget) {
        if (step != null) return
        typed = null
        step = KeyLoginStep.Working(target)
        job = scope.launch(main) {
            val keys = withContext(Dispatchers.IO) { runCatching { installer.keys() } }.getOrNull()
            if (!isActive) return@launch
            when {
                keys == null -> run(target)
                keys.size > 1 -> step = KeyLoginStep.Choose(target, keys)
                else -> run(target.withKey(keys.firstOrNull()))
            }
        }
    }

    /** "Install" in the key choice: runs with [key]; the choice stays with the run through retries, host-key answers and a user change. */
    fun choose(key: SshKeyChoice) {
        val s = step as? KeyLoginStep.Choose ?: return
        run(s.target.withKey(key))
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
        job = scope.launch(main) {
            val failure = withContext(Dispatchers.IO) {
                hostKeyWriteFailure {
                    when (s.check) {
                        is HostKeyCheck.Changed -> s.target.hostKeys.forget(s.target.device, s.target.port)
                        else -> s.target.hostKeys.pin(s.target.device, s.target.port, s.presented)
                    }
                }
            }
            if (failure != null) {
                // Nothing was trusted: the owner may retry from the start or close.
                step = KeyLoginStep.Done(s.target, KeyInstallResult.HostKeySaveFailed(hostKeyWriteDetail(failure), forgot = s.check is HostKeyCheck.Changed))
            } else run(s.target)
        }
    }

    /** "Retry" after a failure: from the start, as the first tap (after an unreadable key, from the key choice). */
    fun retry() {
        val s = step as? KeyLoginStep.Done ?: return
        typed = null
        if (s.result == KeyInstallResult.KeyUnreadable) {
            // The chosen key is gone or unreadable: choose again from the keys as they are now.
            step = null
            start(s.target.withKey(null))
        } else run(s.target)
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
        job = scope.launch(main) {
            val result = installer.install(target, typed)
            // Every change of the step on the main thread, and only by the run still wanted.
            if (!isActive) return@launch
            step = when (result) {
                is KeyInstallResult.PasswordNeeded -> KeyLoginStep.AskPassword(target, result.refusal)
                is KeyInstallResult.HostKey -> KeyLoginStep.HostKey(target, result.check, result.presented)
                else -> {
                    typed = null
                    if (result is KeyInstallResult.Installed || result is KeyInstallResult.InstalledPasswordKept) target.onInstalled()
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
    val keyName = stringResource(if (t.key?.slot == SshKeySlot.IMPORTED) R.string.key_name_imported else R.string.key_name_phone)
    when (step) {
        is KeyLoginStep.Choose -> KeyChoiceDialog(title, t.user, step.keys, onInstall = flow::choose, onCancel = flow::dismiss)
        is KeyLoginStep.Working -> AppAlertDialog(
            // Bounded by the connect and command timeouts; Cancel closes the connection.
            onDismissRequest = {},
            title = { Text(title) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = AppTheme.colors.accent, strokeWidth = 2.dp)
                    Text(stringResource(R.string.key_install_working, keyName))
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
            note = stringResource(R.string.key_install_password_note, keyName),
            problem = step.refusal?.let { stringResource(loginRefusalText(it), t.user) },
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
                    context.notice(message, NoticeKind.DONE, long = true)
                    flow.dismiss()
                }
            } else {
                AppAlertDialog(
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

/** The name of [slot]'s key: "Phone key" / "Imported key". */
@Composable
fun keyTitle(slot: SshKeySlot): String = stringResource(if (slot == SshKeySlot.IMPORTED) R.string.imported_key else R.string.phone_key)

/**
 * Which key to add, when there are two: radio rows with name, type and
 * fingerprint, the phone's key chosen first.
 */
@Composable
private fun KeyChoiceDialog(title: String, user: String, keys: List<SshKeyChoice>, onInstall: (SshKeyChoice) -> Unit, onCancel: () -> Unit) {
    val colors = AppTheme.colors
    var slot by rememberSaveable { mutableStateOf(SshKeySlot.PHONE) }
    val chosen = keys.firstOrNull { it.slot == slot } ?: keys.first()
    AppAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.key_choose_question, user))
                Column(Modifier.selectableGroup()) {
                    keys.forEach { key ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(selected = key == chosen, role = Role.RadioButton, onClick = { slot = key.slot })
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = key == chosen, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text(keyTitle(key.slot), fontSize = 16.sp)
                                Text(keySummary(key.publicLine).orEmpty(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.muted)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.key_choose_hint), fontSize = 13.sp, color = colors.muted)
            }
        },
        confirmButton = { TextButton(onClick = { onInstall(chosen) }) { Text(stringResource(R.string.key_choose_confirm)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
