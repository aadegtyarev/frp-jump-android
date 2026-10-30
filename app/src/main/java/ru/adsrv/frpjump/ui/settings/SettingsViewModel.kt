package ru.adsrv.frpjump.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.ui.userMessage

data class SettingsUiState(val serverUrl: String = "", val deviceName: String = "", val busy: Boolean = false)

class SettingsViewModel(private val app: FrpJumpApp) : ViewModel() {
    private val _state = MutableStateFlow(
        app.store.load().let { SettingsUiState(serverUrl = it?.controlUrl.orEmpty(), deviceName = it?.deviceName.orEmpty()) },
    )
    val state: StateFlow<SettingsUiState> = _state

    /**
     * Tell the server to delete this phone, then wipe it locally whatever the
     * server said: offline the owner deletes the device from a laptop instead.
     * 401/404 mean the server already has no such phone: nothing to tell.
     * Runs in the app scope, so leaving the screen cannot stop it halfway.
     */
    fun forget(onForgotten: () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        val work = app.appScope.async {
            val credentials = app.store.load()
            if (credentials != null) {
                try {
                    app.api.deleteDevice(credentials, credentials.deviceName)
                } catch (e: ApiException) {
                    if (e.status != 401 && e.status != 404) app.session.postNotice(laptopNotice(credentials.deviceName, e))
                } catch (e: Exception) {
                    app.session.postNotice(laptopNotice(credentials.deviceName, e))
                }
            }
            app.forgetLocally(keepPorts = false)
        }
        viewModelScope.launch {
            work.await()
            onForgotten()
        }
    }

    private fun laptopNotice(name: String, e: Exception) =
        "The server was not told (${e.userMessage()}). Remove \"$name\" from a laptop: frp-jump-client devices delete $name"
}
