package ru.adsrv.frpjump.devices

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.api.OwnedDevice
import ru.adsrv.frpjump.api.presenceOf
import ru.adsrv.frpjump.session.AppSession
import ru.adsrv.frpjump.store.CredentialStore
import ru.adsrv.frpjump.ui.userMessage
import java.time.Instant

data class DeviceRow(val name: String, val online: Boolean, val lastSeen: String, val disabled: Boolean)

data class DeviceList(
    /** Null until the first answer. */
    val devices: List<DeviceRow>? = null,
    val error: String? = null,
)

/**
 * The owner's other devices, fetched by the app's poller while the list is
 * on screen ([wanted]) and on pull to refresh.
 */
class DeviceDirectory(
    private val store: CredentialStore,
    private val api: FrpJumpApi,
    private val session: AppSession,
) {
    private val _state = MutableStateFlow(DeviceList())
    val state: StateFlow<DeviceList> = _state
    private val fetching = Mutex()

    /** Set while the device list is visible: only then does the poller fetch it. */
    @Volatile
    var wanted = false

    /**
     * One fetch; skipped when another is still in flight unless [wait].
     * False when it failed. A 401 means the server deleted this phone.
     */
    suspend fun refresh(wait: Boolean = false): Boolean {
        if (wait) fetching.withLock { }
        val credentials = store.load() ?: return true
        if (session.removed.value) return true
        if (!fetching.tryLock()) return true
        try {
            val devices = api.devices(credentials)
            _state.update { it.copy(devices = rows(devices, credentials.deviceName), error = null) }
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Only for the token still stored: not a late answer to a call made before "Forget".
            if (e is ApiException && e.status == 401 && store.load()?.apiToken == credentials.apiToken) session.markRemoved()
            _state.update { it.copy(error = e.userMessage()) }
            return false
        } finally {
            fetching.unlock()
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
