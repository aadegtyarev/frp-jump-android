package ru.adsrv.frpjump.mqtt.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.mqtt.connectionKey
import ru.adsrv.frpjump.mqtt.profileOfKey
import ru.adsrv.frpjump.store.TextStorage

/** The plugins' settings of one connection; a connection never set up has the defaults. */
@Serializable
data class ConnectionPlugins(
    val statusCommand: StatusCommandSettings = StatusCommandSettings(),
)

/**
 * The plugins' settings per connection of a profile, kept after sessions
 * and tunnels close. Read on first use, off the main thread; [changes]
 * counts the writes, so open screens read again.
 */
class PluginStore(private val storage: TextStorage) {
    private var loaded: Map<String, ConnectionPlugins>? = null

    private val all: Map<String, ConnectionPlugins>
        get() = loaded ?: read().also { loaded = it }

    private val _changes = MutableStateFlow(0)

    /** Goes up on every change. Any thread. */
    val changes: StateFlow<Int> = _changes

    @Synchronized
    fun get(profile: String, connectionId: String): ConnectionPlugins = all[connectionKey(profile, connectionId)] ?: ConnectionPlugins()

    /** Changes the settings of one connection by [change]; returns them after it. Kept in memory even when writing fails (which then throws). */
    @Synchronized
    fun update(profile: String, connectionId: String, change: (ConnectionPlugins) -> ConnectionPlugins): ConnectionPlugins {
        val key = connectionKey(profile, connectionId)
        val updated = change(all[key] ?: ConnectionPlugins())
        save(if (updated == ConnectionPlugins()) all - key else all + (key to updated))
        return updated
    }

    @Synchronized
    fun forget(profile: String, connectionId: String) {
        val key = connectionKey(profile, connectionId)
        if (key in all) save(all - key)
    }

    @Synchronized
    fun forgetProfile(profile: String) {
        val kept = all.filterKeys { profileOfKey(it) != profile }
        if (kept != all) save(kept)
    }

    @Synchronized
    fun wipe() {
        storage.delete()
        loaded = emptyMap()
        _changes.value++
    }

    private fun read(): Map<String, ConnectionPlugins> =
        runCatching { storage.read()?.let { WireJson.decodeFromString<Map<String, ConnectionPlugins>>(it) } }.getOrNull().orEmpty()

    private fun save(value: Map<String, ConnectionPlugins>) {
        // In memory first: a failed write loses only the file's copy.
        loaded = value
        _changes.value++
        storage.write(WireJson.encodeToString(value))
    }
}
