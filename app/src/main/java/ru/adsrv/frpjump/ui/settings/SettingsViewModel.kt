package ru.adsrv.frpjump.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.settings.BackgroundPoll
import ru.adsrv.frpjump.settings.PortRangeProblem
import ru.adsrv.frpjump.settings.portRangeProblem
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.AppLanguage
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.locale.applyAppLanguage
import ru.adsrv.frpjump.tunnel.TunnelService
import ru.adsrv.frpjump.ssh.publicLineOfPrivateKey
import ru.adsrv.frpjump.ui.userMessage
import androidx.fragment.app.FragmentActivity
import ru.adsrv.frpjump.lock.AuthResult
import ru.adsrv.frpjump.settings.ThemeMode
import ru.adsrv.frpjump.ssh.blobOfPublicKeyLine
import ru.adsrv.frpjump.ssh.sshFingerprint
import ru.adsrv.frpjump.ui.applyTheme

data class SettingsUiState(
    /** This phone's SSH public key line; null until it is read (or made) off the main thread. */
    val sshPublicKey: String? = null,
    val sshPublicKeyError: String? = null,
    /** "ed25519 · SHA256:Fymb…7U8": the key as `ssh-keygen -l` would name it, shortened. */
    val sshKeySummary: String? = null,
    /** The imported private key's public line, when there is one. */
    val importedKey: String? = null,
    val importError: String? = null,
)

/** Settings of the app; the profile group is [ru.adsrv.frpjump.ui.profiles.ProfilesViewModel]'s. */
class SettingsViewModel(private val app: FrpJumpApp) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    /** Background poll, direct connection, local ports. */
    val settings: StateFlow<AppSettings> = app.settings.settings

    fun setBackgroundPoll(poll: BackgroundPoll) = save { it.copy(backgroundPoll = poll) }

    fun setDirect(direct: Boolean) = save { it.copy(direct = direct) }

    /** Returns why the range was refused, or null once it is saved. */
    fun setLocalPorts(first: Int?, last: Int?): PortRangeProblem? {
        portRangeProblem(first, last)?.let { return it }
        save { it.copy(localPortFirst = first!!, localPortLast = last!!) }
        return null
    }

    /** Switches the language at once (main thread); the settings keep a copy for Android 10–12 ([restoreAppLanguage]). */
    fun setLanguage(language: AppLanguage) {
        applyAppLanguage(language)
        // Android 13+ tells the tunnels service of the new language itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) TunnelService.refresh(app)
        save { it.copy(language = language) }
    }

    /** Applies [theme] at once (the window and system dialogs too) and keeps it. Main thread. */
    fun setTheme(theme: ThemeMode) {
        applyTheme(theme)
        save { it.copy(theme = theme) }
    }

    /**
     * Turns the lock on once the owner has passed the prompt; [onResult]
     * says how it ended. Off needs no prompt.
     */
    fun turnOnAppLock(activity: FragmentActivity, title: String, subtitle: String, onResult: (AuthResult) -> Unit) {
        app.lock.authenticate(activity, title, subtitle) { result ->
            if (result == AuthResult.Success) save { it.copy(appLock = true) }
            onResult(result)
        }
    }

    fun turnOffAppLock() {
        app.lock.unlock()
        save { it.copy(appLock = false) }
    }

    /** Writes off the main thread; a failed write leaves the setting as it was and says so. */
    private fun save(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching { app.settings.update(change) }.exceptionOrNull() }
            if (failure != null) {
                Toast.makeText(app, Texts.get(R.string.settings_save_failed, failure.userMessage()), Toast.LENGTH_LONG).show()
            }
        }
    }

    init {
        viewModelScope.launch {
            try {
                val (line, imported) = withContext(Dispatchers.IO) {
                    app.sshKeys.publicLine(app.sshKeyComment()) to app.sshKeys.importedPublicLine()
                }
                _state.update { it.copy(sshPublicKey = line, sshKeySummary = keySummary(line), importedKey = imported) }
            } catch (e: Exception) {
                _state.update { it.copy(sshPublicKeyError = Texts.get(R.string.ssh_key_failed, e.userMessage())) }
            }
        }
    }

    /**
     * Keeps an OpenSSH private key picked by the owner; it is offered before
     * the app's own key. Checked by loading it with [passphrase] first.
     */
    fun importKey(privateKey: String, passphrase: String) {
        viewModelScope.launch {
            try {
                val line = withContext(Dispatchers.IO) {
                    val publicLine = publicLineOfPrivateKey(privateKey, passphrase, "imported")
                    app.sshKeys.import(privateKey, passphrase, publicLine)
                    publicLine
                }
                _state.update { it.copy(importedKey = line, importError = null) }
            } catch (e: Exception) {
                _state.update { it.copy(importError = Texts.get(R.string.import_key_failed, e.userMessage())) }
            }
        }
    }

    fun removeImportedKey() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.sshKeys.removeImported() }
            _state.update { it.copy(importedKey = null) }
        }
    }

    fun clearImportError() = _state.update { it.copy(importError = null) }
}

/** "ed25519 · SHA256:Fymb…7U8" of an OpenSSH public key line; null when it is not one. */
internal fun keySummary(line: String): String? {
    val blob = blobOfPublicKeyLine(line) ?: return null
    val type = line.substringBefore(' ').removePrefix("ssh-")
    val print = sshFingerprint(blob)
    val short = if (print.length > 18) print.take(11) + "…" + print.takeLast(3) else print
    return "$type · $short"
}
