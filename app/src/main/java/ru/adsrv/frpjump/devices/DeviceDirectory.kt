package ru.adsrv.frpjump.devices

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.api.OwnedDevice
import ru.adsrv.frpjump.api.presenceOf
import ru.adsrv.frpjump.session.AppSession
import ru.adsrv.frpjump.store.CredentialStore
import ru.adsrv.frpjump.ui.userMessage
import java.time.Instant

data class DeviceRow(
    val name: String,
    val online: Boolean,
    val lastSeen: String,
    val disabled: Boolean,
    /** From a list the server gave earlier: [online] is as of [DeviceList.updatedAt]. */
    val stale: Boolean = false,
)

data class DeviceList(
    /** Null until the first answer or the cached list. */
    val devices: List<DeviceRow>? = null,
    /** When [devices] came from the server. */
    val updatedAt: Instant? = null,
    /** The server has not answered since [updatedAt]. */
    val stale: Boolean = false,
    val error: String? = null,
)

/**
 * Rows for [devices] without the phone itself. Fresh rows are as of [now].
 * Stale rows keep the online flag as of [fetchedAt], since nothing newer is
 * known, but "last seen" counts from [now].
 */
fun deviceRows(devices: List<OwnedDevice>, ownName: String, fetchedAt: Instant, now: Instant, stale: Boolean): List<DeviceRow> =
    devices.filter { it.name != ownName }.map {
        val seen = presenceOf(it.lastSeenAt, now)
        val online = if (stale) presenceOf(it.lastSeenAt, fetchedAt).online else seen.online
        DeviceRow(it.name, online, seen.lastSeen, disabled = !it.enabled, stale = stale)
    }

/**
 * The owner's other devices, fetched by the app's poller while the list is
 * on screen ([wanted]) and on pull to refresh. The last answer is kept in
 * [cache] and shown, marked stale, until the server answers again.
 */
class DeviceDirectory(
    private val store: CredentialStore,
    private val api: FrpJumpApi,
    private val session: AppSession,
    private val cache: DeviceListCache,
) {
    private val _state = MutableStateFlow(DeviceList())
    val state: StateFlow<DeviceList> = _state
    private val fetching = Mutex()

    /** The last list the server gave this enrollment. */
    @Volatile
    private var last: CachedDeviceList? = null

    /** Set while the device list is visible: only then does the poller fetch it. */
    @Volatile
    var wanted = false

    /** Shows the cached list, unless the server has answered already. Reads storage: call off the main thread. */
    fun restore() {
        val credentials = store.load() ?: return
        val cached = cache.load(credentials.controlUrl, credentials.deviceName) ?: return
        _state.update { current ->
            if (current.devices != null) return@update current
            last = cached
            val fetchedAt = Instant.ofEpochMilli(cached.fetchedAtMs)
            current.copy(
                devices = deviceRows(cached.devices, credentials.deviceName, fetchedAt, Instant.now(), stale = true),
                updatedAt = fetchedAt,
                stale = true,
            )
        }
    }

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
            val now = Instant.now()
            val fresh = CachedDeviceList(credentials.controlUrl, credentials.deviceName, now.toEpochMilli(), devices)
            last = fresh
            _state.value = DeviceList(
                devices = deviceRows(devices, credentials.deviceName, now, now, stale = false),
                updatedAt = now,
            )
            withContext(Dispatchers.IO) { cache.save(fresh) }
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Only for the token still stored: not a late answer to a call made before "Forget".
            if (e is ApiException && e.status == 401 && store.load()?.apiToken == credentials.apiToken) session.markRemoved()
            val known = last?.takeIf { it.controlUrl == credentials.controlUrl && it.deviceName == credentials.deviceName }
            _state.update {
                if (known == null) {
                    it.copy(error = e.userMessage())
                } else {
                    val fetchedAt = Instant.ofEpochMilli(known.fetchedAtMs)
                    it.copy(
                        devices = deviceRows(known.devices, credentials.deviceName, fetchedAt, Instant.now(), stale = true),
                        stale = true,
                        error = e.userMessage(),
                    )
                }
            }
            return false
        } finally {
            fetching.unlock()
        }
    }

    /** Drops the list and its cache; for "Forget this phone". Waits for a fetch in flight so it cannot save the list back. */
    suspend fun forget() = fetching.withLock {
        last = null
        _state.value = DeviceList()
        withContext(Dispatchers.IO) { cache.wipe() }
    }
}
