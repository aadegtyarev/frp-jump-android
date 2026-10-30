package ru.adsrv.frpjump.ports

import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/**
 * The (profile, device, port) -> local port map ([profilePortKey]), one for
 * all profiles: one range, no port handed out twice. See [LocalPortAllocator].
 */
class LocalPortStore(private val storage: TextStorage) {
    @Synchronized
    fun load(): MutableMap<String, Int> = runCatching {
        storage.read()?.let { WireJson.decodeFromString<Map<String, Int>>(it).toMutableMap() }
    }.getOrNull() ?: mutableMapOf()

    /**
     * Reads the map, lets [change] edit it in place and saves it if it did,
     * all under one lock: profiles planning their tunnels at the same time
     * see each other's ports.
     */
    @Synchronized
    fun <T> update(change: (MutableMap<String, Int>) -> T): T {
        val ports = load()
        val before = ports.toMap()
        val result = change(ports)
        if (ports != before) storage.write(WireJson.encodeToString<Map<String, Int>>(ports))
        return result
    }

    /** Drops every port of [profile] ("Forget profile"). */
    fun removeProfile(profile: String) = update { ports -> ports.keys.removeAll { it.startsWith(profilePortPrefix(profile)) } }

    companion object {
        /**
         * v1 keyed the map by (device, port) alone; on upgrade every such key
         * becomes [profile]'s. Keys already of [profile] stay, so running it
         * again changes nothing.
         */
        fun migrateLegacy(ports: Map<String, Int>, profile: String): Map<String, Int> =
            ports.mapKeys { (key, _) -> if (key.startsWith(profilePortPrefix(profile))) key else profilePortPrefix(profile) + key }
    }
}
