package ru.adsrv.frpjump.ports

import android.util.AtomicFile
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import java.io.File

/** The (device, port) -> local port map, see [LocalPortAllocator]. */
class LocalPortStore(filesDir: File) {
    private val file = AtomicFile(File(filesDir, "local_ports.json"))

    @Synchronized
    fun load(): MutableMap<String, Int> =
        if (!file.baseFile.exists()) mutableMapOf() else runCatching {
            WireJson.decodeFromString<Map<String, Int>>(String(file.readFully(), Charsets.UTF_8)).toMutableMap()
        }.getOrDefault(mutableMapOf())

    @Synchronized
    fun save(ports: Map<String, Int>) = writeAtomically(file, WireJson.encodeToString(ports))

    @Synchronized
    fun wipe() {
        file.delete()
    }
}
