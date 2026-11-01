package ru.adsrv.frpjump.restore

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/**
 * How many tunnels each profile had up when it last applied its desired
 * state: what a cold start (the process killed, the phone rebooted) brings
 * back, before the app is opened. A profile is in here from its first
 * tunnel until it has none left: "Disconnect all", its last tunnel
 * disconnected (here or elsewhere), "Forget profile", its server removing it.
 * The tunnels themselves are the server's desired state and their local
 * ports the local port map; this only says which profiles to poll and to
 * start frpc for.
 *
 * One small JSON object for all profiles (`restore.json`, profile id to
 * tunnel count), written atomically: a crash leaves the old record or the
 * new one. An unreadable record restores nothing.
 *
 * [load], [set] and [retain] touch storage: call them off the main thread.
 */
class RestoreRecord(private val storage: TextStorage) {
    private val _profiles = MutableStateFlow<Map<String, Int>?>(null)

    /** Tunnels by profile id, profiles without any left out; null until [load]. */
    val profiles: StateFlow<Map<String, Int>?> = _profiles

    /** Reads the record once; later calls return what is in memory. */
    @Synchronized
    fun load(): Map<String, Int> {
        _profiles.value?.let { return it }
        val read = try {
            storage.read()?.let { WireJson.decodeFromString<Map<String, Int>>(it) }.orEmpty().filterValues { it > 0 }
        } catch (e: Exception) {
            Log.w(TAG, "restore record unreadable, nothing to restore: ${e.javaClass.simpleName}")
            emptyMap()
        }
        _profiles.value = read
        return read
    }

    /**
     * [profile] has [tunnels] up now; 0 drops it. Written only when it
     * changes. A failed write is logged and tried again on the next call:
     * the record in memory stays as on disk.
     */
    @Synchronized
    fun set(profile: String, tunnels: Int) {
        val now = load()
        save(if (tunnels > 0) now + (profile to tunnels) else now - profile)
    }

    /** Drops profiles no longer listed (a "Forget profile" cut short). */
    @Synchronized
    fun retain(ids: Set<String>) = save(load().filterKeys { it in ids })

    private fun save(next: Map<String, Int>) {
        if (next == _profiles.value) return
        try {
            if (next.isEmpty()) storage.delete() else storage.write(WireJson.encodeToString(next))
            _profiles.value = next
        } catch (e: Exception) {
            Log.w(TAG, "restore record not written: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "RestoreRecord"
    }
}
