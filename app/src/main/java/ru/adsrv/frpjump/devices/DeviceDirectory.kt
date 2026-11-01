package ru.adsrv.frpjump.devices

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.FEATURE_DEVICE_DISPLAY
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.api.NetworkException
import ru.adsrv.frpjump.api.OwnedDevice
import ru.adsrv.frpjump.api.Age
import ru.adsrv.frpjump.api.presenceOf
import ru.adsrv.frpjump.session.ProfileSession
import ru.adsrv.frpjump.store.CredentialStore
import ru.adsrv.frpjump.ui.userMessage
import java.time.Instant

private const val TAG = "DeviceDirectory"

data class DeviceRow(
    val name: String,
    val online: Boolean,
    /** Null: never seen. */
    val lastSeen: Age?,
    val disabled: Boolean,
    /** From a list the server gave earlier: [online] is as of [DeviceList.updatedAt]. */
    val stale: Boolean = false,
    /** [stale] and the server not asked yet since the app started: the list is being checked, not old. */
    val checking: Boolean = false,
    /** The owner's label; null when unset or the server does not support labels ([DeviceList.display]). */
    val label: String? = null,
    /** The icon to show ([shownIcon]); null: the generic device glyph. */
    val icon: DeviceIcon? = null,
    /** The owner's icon choice as the server has it, for the edit sheet; null: automatic (or no labels). */
    val iconChoice: String? = null,
    /** What the device reports about itself, for the sheet's «Авто» tile; null: unknown (or no labels). */
    val kind: String? = null,
)

data class DeviceList(
    /** Null until the first answer or the cached list. */
    val devices: List<DeviceRow>? = null,
    /** When [devices] came from the server. */
    val updatedAt: Instant? = null,
    /** The server has not answered since [updatedAt]. */
    val stale: Boolean = false,
    val error: String? = null,
    /** [error] is a network failure, not an answer from the server. */
    val unreachable: Boolean = false,
    /**
     * The server supports labels and icons ("device-display" in its last
     * stored `features`). Off: rows carry no label and the generic glyph.
     */
    val display: Boolean = false,
)

/**
 * Rows for [devices] without the phone itself. Fresh rows are as of [now].
 * Stale rows keep the online flag as of [fetchedAt], since nothing newer is
 * known, but "last seen" counts from [now]. Labels and icons only with
 * [display] (the server supports them).
 */
fun deviceRows(
    devices: List<OwnedDevice>,
    ownName: String,
    fetchedAt: Instant,
    now: Instant,
    stale: Boolean,
    checking: Boolean = false,
    display: Boolean = false,
): List<DeviceRow> =
    devices.filter { it.name != ownName }.map {
        val seen = presenceOf(it.lastSeenAt, now)
        val online = if (stale) presenceOf(it.lastSeenAt, fetchedAt).online else seen.online
        DeviceRow(it.name, online, seen.lastSeen, disabled = !it.enabled, stale = stale, checking = stale && checking).shown(it, display)
    }

/** [row] with [device]'s label and icon when [display] is on, without them otherwise. */
private fun DeviceRow.shown(device: OwnedDevice?, display: Boolean): DeviceRow =
    if (display && device != null) {
        copy(
            label = device.label?.takeIf { it.isNotBlank() },
            icon = shownIcon(device.icon, device.kind),
            iconChoice = device.icon,
            kind = device.kind,
        )
    } else {
        copy(label = null, icon = null, iconChoice = null, kind = null)
    }

/**
 * One profile's devices, fetched by its poller while the list is on screen
 * ([wanted]) and on pull to refresh. The last answer is kept in
 * [cache] and shown, marked stale, until the server answers again. The
 * server's features ([checkFeatures]) are kept in [featuresCache] beside it.
 */
class DeviceDirectory(
    private val store: CredentialStore,
    private val api: FrpJumpApi,
    private val session: ProfileSession,
    private val cache: DeviceListCache,
    private val featuresCache: ServerFeaturesCache,
    /** Runs the features check a list refresh starts, so the poll does not wait for it. */
    private val background: CoroutineScope,
) {
    private val _state = MutableStateFlow(DeviceList())
    val state: StateFlow<DeviceList> = _state
    private val fetching = Mutex()

    /** The last list the server gave this enrollment. */
    @Volatile
    private var last: CachedDeviceList? = null

    /** The server answered `GET /version` since the app started; until then the stored answer stands. */
    @Volatile
    private var featuresAnswered = false

    /** A features check started by [refresh] is still out: no second one. */
    private val featuresInFlight = AtomicBoolean(false)

    /** The screens showing this list now (Devices, Connections). */
    private val wantedBy = ScreenMarks()

    /** Set while a screen shows the list: only then does the poller fetch it. */
    val wanted: Boolean get() = wantedBy.any

    /** [screen] shows the list now, or no longer ([ScreenMarks]). */
    fun want(screen: Any, on: Boolean) = wantedBy.set(screen, on)

    /**
     * Shows the cached list and the stored features, unless the server has
     * answered already. Reads storage: call off the main thread.
     */
    suspend fun restore() = fetching.withLock {
        val credentials = store.load() ?: return@withLock
        if (!featuresAnswered) featuresCache.load(credentials.controlUrl)?.let { applyFeatures(it) }
        if (_state.value.devices != null) return@withLock
        val cached = cache.load(credentials.controlUrl, credentials.deviceName) ?: return@withLock
        last = cached
        val fetchedAt = Instant.ofEpochMilli(cached.fetchedAtMs)
        // Keeps the error of a fetch that already failed: the banner shows at once.
        _state.value = _state.value.copy(
            // No fetch has failed yet: the rows say they are being checked, not how old they are.
            devices = deviceRows(
                cached.devices, credentials.deviceName, fetchedAt, Instant.now(),
                stale = true, checking = _state.value.error == null, display = _state.value.display,
            ),
            updatedAt = fetchedAt,
            stale = true,
        )
    }

    /**
     * One fetch; skipped when another is still in flight unless [wait].
     * False when it failed. A 401 means the server deleted this profile's device.
     */
    suspend fun refresh(wait: Boolean = false): Boolean {
        val ok = fetch(wait)
        // The check at start failed (offline, or not enrolled yet): the server answers now.
        if (ok && !featuresAnswered && featuresInFlight.compareAndSet(false, true)) {
            background.launch {
                try {
                    checkFeatures()
                } finally {
                    featuresInFlight.set(false)
                }
            }
        }
        return ok
    }

    /** Pull to refresh: the list, waiting for a fetch in flight, and the server's features. */
    suspend fun pull(): Boolean = coroutineScope {
        launch { checkFeatures() }
        fetch(wait = true)
    }

    private suspend fun fetch(wait: Boolean): Boolean {
        if (wait) fetching.withLock { }
        val credentials = store.load() ?: return true
        if (session.removed.value) return true
        if (!fetching.tryLock()) return true
        try {
            val devices = api.devices(credentials)
            // "Forget" or a new enrollment while the call was out: the answer is not this phone's any more.
            if (store.load()?.apiToken != credentials.apiToken) return true
            val now = Instant.now()
            val fresh = CachedDeviceList(credentials.controlUrl, credentials.deviceName, now.toEpochMilli(), devices)
            last = fresh
            val display = _state.value.display
            _state.value = DeviceList(
                devices = deviceRows(devices, credentials.deviceName, now, now, stale = false, display = display),
                updatedAt = now,
                display = display,
            )
            saveQuietly(fresh)
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Only for the token still stored: not a late answer to a call made before "Forget".
            if (e is ApiException && e.status == 401 && store.load()?.apiToken == credentials.apiToken) session.markRemoved()
            val known = last?.takeIf { it.controlUrl == credentials.controlUrl && it.deviceName == credentials.deviceName }
            _state.update {
                if (known == null) {
                    it.copy(error = e.userMessage(), unreachable = e is NetworkException)
                } else {
                    val fetchedAt = Instant.ofEpochMilli(known.fetchedAtMs)
                    it.copy(
                        devices = deviceRows(known.devices, credentials.deviceName, fetchedAt, Instant.now(), stale = true, display = it.display),
                        stale = true,
                        error = e.userMessage(),
                        unreachable = e is NetworkException,
                    )
                }
            }
            return false
        } finally {
            fetching.unlock()
        }
    }

    /**
     * Asks the server's `GET /version` which features it has; at start, on
     * pull to refresh, and after a good list refresh (not awaited by it) until it answers once. A failure
     * keeps the last stored answer. False when it failed.
     */
    suspend fun checkFeatures(): Boolean {
        val credentials = store.load() ?: return true
        if (session.removed.value) return true
        val features = try {
            api.version(credentials.controlUrl).features
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "features not checked: ${e.javaClass.simpleName}")
            return false
        }
        fetching.withLock {
            // "Forget" or a new enrollment while the call was out: not this enrollment's answer.
            if (store.load()?.apiToken != credentials.apiToken) return true
            featuresAnswered = true
            applyFeatures(features)
            // Under the lock, as forget() wipes it: a late save cannot bring the file back.
            try {
                withContext(Dispatchers.IO) { featuresCache.save(credentials.controlUrl, features) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "features not stored: ${e.javaClass.simpleName}")
            }
        }
        return true
    }

    /** Turns labels and icons on or off as [features] says; the rows follow at once. Under [fetching]. */
    private fun applyFeatures(features: List<String>) {
        val display = FEATURE_DEVICE_DISPLAY in features
        if (display == _state.value.display) return
        val known = last?.devices.orEmpty().associateBy { it.name }
        _state.update { list ->
            list.copy(display = display, devices = list.devices?.map { it.shown(known[it.name], display) })
        }
    }

    /**
     * Sends [change] of [name]'s label and icon. On success the list, its
     * cache and every screen showing it take the server's answer at once.
     * Throws what the call threw. Runs in the profile's scope: a caller that
     * goes away mid-save (the card left) does not drop the server's answer.
     */
    suspend fun setDisplay(name: String, change: DisplayChange) {
        // Caught inside, so a failure reaches the caller and never cancels the profile's scope.
        background.async { runCatching { saveDisplay(name, change) } }.await().getOrThrow()
    }

    private suspend fun saveDisplay(name: String, change: DisplayChange) {
        val credentials = store.load() ?: throw IllegalStateException("no credentials")
        val updated = api.setDisplay(credentials, name, change)
        // After a fetch in flight: a list asked for before the save cannot overwrite its answer.
        fetching.withLock {
            // "Forget" or a new enrollment while the call was out: not this enrollment's list any more.
            if (store.load()?.apiToken != credentials.apiToken) return
            val known = last?.takeIf { it.controlUrl == credentials.controlUrl && it.deviceName == credentials.deviceName }
            if (known != null) {
                val fresh = known.copy(devices = known.devices.map { if (it.name == updated.name) updated else it })
                last = fresh
                saveQuietly(fresh)
            }
            _state.update { list ->
                list.copy(devices = list.devices?.map { if (it.name == updated.name) it.shown(updated, list.display) else it })
            }
        }
    }

    /** The server answered; a full disk must not turn that into a failed poll. */
    private suspend fun saveQuietly(list: CachedDeviceList) {
        try {
            withContext(Dispatchers.IO) { cache.save(list) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "device list not cached: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Drops the list and its cache; for "Forget profile", after the
     * credentials are gone so no later fetch can bring the list back. Waits
     * for a fetch in flight.
     */
    suspend fun forget() = fetching.withLock {
        last = null
        featuresAnswered = false
        _state.value = DeviceList()
        withContext(Dispatchers.IO) {
            cache.wipe()
            featuresCache.wipe()
        }
    }
}
