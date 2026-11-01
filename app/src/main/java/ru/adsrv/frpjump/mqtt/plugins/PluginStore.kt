package ru.adsrv.frpjump.mqtt.plugins

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.mqtt.connectionKey
import ru.adsrv.frpjump.mqtt.profileOfKey
import ru.adsrv.frpjump.store.TextStorage
import java.util.UUID

/** `mqtt_plugins_v2.json` as written: every field, as the contract's sketch shows it. */
private val PluginsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * The MQTT plugins' settings: app level and each connection's own on or
 * off ([PluginsFile], docs/contract.md scenario 12), kept in [file].
 * Read on first use, off the main thread: a missing [file] is migrated
 * from the per-connection [legacy] one and written at once; one that does
 * not parse, or has a higher version, reads as the defaults and is copied
 * to [corruptCopy] (the first one only). [legacy] is otherwise only
 * scrubbed of deleted connections and forgotten profiles. [changes] counts
 * the changes, so open screens and sessions read again.
 */
class PluginStore(
    private val file: TextStorage,
    private val legacy: TextStorage,
    private val corruptCopy: TextStorage,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private var loaded: PluginsFile? = null

    /** The file could not be read: the defaults apply until the owner changes something. */
    private var unreadable = false

    private val all: PluginsFile
        get() = loaded ?: load().also { loaded = it }

    private val _changes = MutableStateFlow(0)

    /** Goes up on every change. Any thread. */
    val changes: StateFlow<Int> = _changes

    /** Everything stored. */
    @Synchronized
    fun get(): PluginsFile = all

    /** What connection [connectionId] of [profile] has in force. */
    @Synchronized
    fun get(profile: String, connectionId: String): ConnectionPlugins = all.forConnection(pluginKey(profile, connectionId))

    /** The file did not read and the defaults apply; until the next change by the owner. */
    @Synchronized
    fun unreadable(): Boolean {
        all
        return unreadable
    }

    /**
     * A change by the owner; returns the settings after it. Kept in memory
     * even when writing fails (which then throws).
     */
    @Synchronized
    fun update(change: (PluginsFile) -> PluginsFile): PluginsFile {
        val updated = change(all)
        if (updated != all) {
            unreadable = false
            save(updated)
        }
        return updated
    }

    /**
     * A change the app makes by itself (the WB tab shown): while the file
     * did not read it is kept in memory only, so the file is not
     * overwritten until the owner changes something.
     */
    @Synchronized
    fun updateByApp(change: (PluginsFile) -> PluginsFile) {
        val updated = change(all)
        if (updated == all) return
        if (unreadable) {
            loaded = updated
            _changes.value++
        } else {
            save(updated)
        }
    }

    /** The switch of plugin [id] on a connection moved to [on]. */
    fun switch(profile: String, connectionId: String, id: String, on: Boolean) = update { it.switched(pluginKey(profile, connectionId), id, on) }

    /** A connection deleted: its own settings go, in both files. */
    @Synchronized
    fun forget(profile: String, connectionId: String) {
        val key = pluginKey(profile, connectionId)
        if (key in all.connections) save(all.withoutConnection(key))
        scrubLegacy { it != connectionKey(profile, connectionId) }
    }

    /** A profile forgotten: its connections' own settings go, in both files. */
    @Synchronized
    fun forgetProfile(profile: String) {
        val kept = all.withoutProfile(profile)
        if (kept != all) save(kept)
        scrubLegacy { profileOfKey(it) != profile }
    }

    /** The last profile forgotten: both files go, app level included. */
    @Synchronized
    fun wipe() {
        file.delete()
        legacy.delete()
        corruptCopy.delete()
        loaded = PluginsFile()
        unreadable = false
        _changes.value++
    }

    private fun load(): PluginsFile {
        val text = file.read() ?: return migrate()
        val read = runCatching { PluginsJson.decodeFromString<PluginsFile>(text) }.getOrNull()?.takeIf { it.version <= PLUGINS_VERSION }
        if (read != null) return read
        Log.w(TAG, "mqtt_plugins_v2.json does not read; the defaults apply, the file is kept as a copy")
        runCatching { if (corruptCopy.read() == null) corruptCopy.write(text) }.onFailure { Log.w(TAG, "copy not kept: ${it.message}") }
        unreadable = true
        return PluginsFile()
    }

    /** The first read after the upgrade: the old file's settings, written at once. */
    private fun migrate(): PluginsFile {
        val migrated = migratePlugins(readLegacy(legacy.read()), newId)
        // Written before any session reads it; a failure leaves it in memory, and the next start migrates again.
        runCatching { file.write(PluginsJson.encodeToString(PluginsFile.serializer(), migrated)) }
            .onFailure { Log.w(TAG, "migrated plugin settings not written: ${it.message}") }
        return migrated
    }

    /** Keeps in the old file only the keys [keep] passes; an absent or unreadable file is left as it is. */
    private fun scrubLegacy(keep: (String) -> Boolean) {
        val text = legacy.read() ?: return
        val obj = runCatching { WireJson.decodeFromString<JsonObject>(text) }.getOrNull() ?: return
        val kept = obj.filterKeys(keep)
        if (kept.size != obj.size) legacy.write(WireJson.encodeToString(JsonObject.serializer(), JsonObject(kept)))
    }

    private fun save(value: PluginsFile) {
        // In memory first: a failed write loses only the file's copy.
        loaded = value
        _changes.value++
        file.write(PluginsJson.encodeToString(PluginsFile.serializer(), value))
    }

    private companion object {
        const val TAG = "PluginStore"
    }
}
