package ru.adsrv.frpjump.ports

import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/** The (device, port) -> local port map, see [LocalPortAllocator]. */
class LocalPortStore(private val storage: TextStorage) {
    @Synchronized
    fun load(): MutableMap<String, Int> = runCatching {
        storage.read()?.let { WireJson.decodeFromString<Map<String, Int>>(it).toMutableMap() }
    }.getOrNull() ?: mutableMapOf()

    @Synchronized
    fun save(ports: Map<String, Int>) = storage.write(WireJson.encodeToString(ports))

    @Synchronized
    fun wipe() = storage.delete()
}
