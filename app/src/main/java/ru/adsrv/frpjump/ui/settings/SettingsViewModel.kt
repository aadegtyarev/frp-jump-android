package ru.adsrv.frpjump.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.settings.BackgroundPoll
import ru.adsrv.frpjump.settings.portRangeProblem
import ru.adsrv.frpjump.ssh.publicLineOfPrivateKey
import ru.adsrv.frpjump.ui.userMessage

data class SettingsUiState(
    val serverUrl: String = "",
    val deviceName: String = "",
    val busy: Boolean = false,
    /** This phone's SSH public key line; null until it is read (or made) off the main thread. */
    val sshPublicKey: String? = null,
    val sshPublicKeyError: String? = null,
    /** The imported private key's public line, when there is one. */
    val importedKey: String? = null,
    val importError: String? = null,
)

/** Settings of the app and of the current profile. */
class SettingsViewModel(private val app: FrpJumpApp) : ViewModel() {
    private val profile = app.profiles.current
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    /** Background poll, relay only, local ports. */
    val settings: StateFlow<AppSettings> = app.settings.settings

    fun setBackgroundPoll(poll: BackgroundPoll) = save { it.copy(backgroundPoll = poll) }

    fun setRelayOnly(relayOnly: Boolean) = save { it.copy(relayOnly = relayOnly) }

    /** Returns why the range was refused, or null once it is saved. */
    fun setLocalPorts(first: Int?, last: Int?): String? {
        portRangeProblem(first, last)?.let { return it }
        save { it.copy(localPortFirst = first!!, localPortLast = last!!) }
        return null
    }

    private fun save(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch(Dispatchers.IO) { app.settings.update(change) }
    }

    init {
        viewModelScope.launch {
            // The Keystore: off the main thread.
            val credentials = withContext(Dispatchers.IO) { profile?.store?.load() }
            _state.update { it.copy(serverUrl = credentials?.controlUrl.orEmpty(), deviceName = credentials?.deviceName.orEmpty()) }
        }
        viewModelScope.launch {
            try {
                val (line, imported) = withContext(Dispatchers.IO) {
                    app.sshKeys.publicLine(app.sshKeyComment()) to app.sshKeys.importedPublicLine()
                }
                _state.update { it.copy(sshPublicKey = line, importedKey = imported) }
            } catch (e: Exception) {
                _state.update { it.copy(sshPublicKeyError = "Cannot make or read the SSH key: ${e.userMessage()}") }
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
                _state.update { it.copy(importError = "Not a usable private key, or a wrong passphrase (${e.userMessage()}).") }
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

    /**
     * Forget the current profile: tell its server to delete this phone, then
     * wipe the profile locally whatever the server said: offline the owner
     * deletes the device from a laptop instead. 401/404 mean the server
     * already has no such phone: nothing to tell. Runs in the app scope, so
     * leaving the screen cannot stop it halfway.
     */
    fun forget(onForgotten: () -> Unit) {
        val profile = profile ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        val work = app.appScope.async {
            val credentials = withContext(Dispatchers.IO) { profile.store.load() }
            if (credentials != null) {
                try {
                    app.api.deleteDevice(credentials, credentials.deviceName)
                } catch (e: ApiException) {
                    if (e.status != 401 && e.status != 404) app.session.postNotice(laptopNotice(credentials.deviceName, e))
                } catch (e: Exception) {
                    app.session.postNotice(laptopNotice(credentials.deviceName, e))
                }
            }
            app.forgetProfile(profile.id, keepData = false)
        }
        viewModelScope.launch {
            work.await()
            onForgotten()
        }
    }

    private fun laptopNotice(name: String, e: Exception) =
        "The server was not told (${e.userMessage()}). Remove \"$name\" from a laptop: frp-jump-client devices delete $name"
}
