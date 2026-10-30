package ru.adsrv.frpjump.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.api.OwnedDevice
import ru.adsrv.frpjump.api.presenceOf
import ru.adsrv.frpjump.ui.userMessage
import java.time.Instant

data class DeviceRow(val name: String, val online: Boolean, val lastSeen: String, val disabled: Boolean)

data class DevicesUiState(
    /** Null until the first answer. */
    val devices: List<DeviceRow>? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
)

class DevicesViewModel(private val app: FrpJumpApp) : ViewModel() {
    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state
    private val fetching = Mutex()

    /** One fetch; skipped when another is still in flight. */
    suspend fun refresh() {
        val credentials = app.store.load() ?: return
        if (!fetching.tryLock()) return
        try {
            val devices = app.api.devices(credentials)
            _state.update { it.copy(devices = rows(devices, credentials.deviceName), error = null) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.update { it.copy(error = e.userMessage()) }
        } finally {
            fetching.unlock()
        }
    }

    /** Pull to refresh: waits for a fetch already in flight rather than skipping. */
    fun pull() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            fetching.withLock { }
            refresh()
            _state.update { it.copy(refreshing = false) }
        }
    }

    private fun rows(devices: List<OwnedDevice>, ownName: String): List<DeviceRow> {
        val now = Instant.now()
        return devices.filter { it.name != ownName }.map {
            val presence = presenceOf(it.lastSeenAt, now)
            DeviceRow(it.name, presence.online, presence.lastSeen, disabled = !it.enabled)
        }
    }
}
