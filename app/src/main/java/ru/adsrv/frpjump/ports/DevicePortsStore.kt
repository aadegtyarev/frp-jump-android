package ru.adsrv.frpjump.ports

import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import java.io.File

/** The owner's own ports per device (presets are not stored), one JSON file in filesDir. */
class DevicePortsStore(filesDir: File) {
    private val file = AtomicFile(File(filesDir, "device_ports.json"))
    private val _ports = MutableStateFlow(read())
    val ports: StateFlow<Map<String, List<DevicePort>>> = _ports

    @Synchronized
    fun add(device: String, port: DevicePort) {
        val current = _ports.value[device].orEmpty().filter { it.port != port.port }
        write(_ports.value + (device to current + port))
    }

    @Synchronized
    fun remove(device: String, port: Int) {
        val remaining = _ports.value[device].orEmpty().filter { it.port != port }
        write(if (remaining.isEmpty()) _ports.value - device else _ports.value + (device to remaining))
    }

    @Synchronized
    fun wipe() {
        file.delete()
        _ports.value = emptyMap()
    }

    private fun read(): Map<String, List<DevicePort>> =
        if (!file.baseFile.exists()) emptyMap() else runCatching {
            WireJson.decodeFromString<Map<String, List<DevicePort>>>(String(file.readFully(), Charsets.UTF_8))
        }.getOrDefault(emptyMap())

    private fun write(value: Map<String, List<DevicePort>>) {
        writeAtomically(file, WireJson.encodeToString(value))
        _ports.value = value
    }
}

internal fun writeAtomically(file: AtomicFile, text: String) {
    val out = file.startWrite()
    try {
        out.write(text.toByteArray(Charsets.UTF_8))
        file.finishWrite(out)
    } catch (e: Exception) {
        file.failWrite(out)
        throw e
    }
}
