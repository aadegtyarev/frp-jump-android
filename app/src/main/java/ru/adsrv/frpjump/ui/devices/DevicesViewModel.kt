package ru.adsrv.frpjump.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.OwnedDevice
import ru.adsrv.frpjump.session.PollBackoff
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

    /** Open SSH sessions, so the list can lead back to them. */
    val sshSessions = app.ssh.sessions

    /**
     * One fetch; skipped when another is still in flight. False when it
     * failed. A 401 means the server deleted this phone: polling stops.
     */
    suspend fun refresh(): Boolean {
        val credentials = app.store.load() ?: return true
        if (app.session.removed.value) return true
        if (!fetching.tryLock()) return true
        try {
            val devices = app.api.devices(credentials)
            _state.update { it.copy(devices = rows(devices, credentials.deviceName), error = null) }
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Only for the token still stored: not a late answer to a call made before "Forget".
            if (e is ApiException && e.status == 401 && app.store.load()?.apiToken == credentials.apiToken) app.session.markRemoved()
            _state.update { it.copy(error = e.userMessage()) }
            return false
        } finally {
            fetching.unlock()
        }
    }

    /** Polls while called from a visible screen, backing off after failures. */
    suspend fun pollEvery(intervalMs: Long) {
        val backoff = PollBackoff()
        while (true) {
            app.session.removed.first { !it }
            delay(backoff.next(refresh(), intervalMs))
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
