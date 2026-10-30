package ru.adsrv.frpjump.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
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
     */
    fun forget(onForgotten: () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val credentials = app.store.load()
            if (credentials != null) {
                try {
                    app.api.deleteDevice(credentials, credentials.deviceName)
                } catch (e: Exception) {
                    val name = credentials.deviceName
                    app.notice = "The server was not told (${e.userMessage()}). " +
                        "Remove \"$name\" from a laptop: frp-jump-client devices delete $name"
                }
            }
            app.tunnels.shutdown()
            app.devicePorts.wipe()
            app.store.wipe()
            onForgotten()
        }
    }
}
