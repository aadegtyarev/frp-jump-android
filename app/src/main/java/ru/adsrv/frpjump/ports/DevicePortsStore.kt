package ru.adsrv.frpjump.ports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/** The owner's own ports per device (presets are not stored), one JSON document ([ru.adsrv.frpjump.profiles.ProfileFiles.DEVICE_PORTS]). */
class DevicePortsStore(private val file: TextStorage) {
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
        file.read()?.let { text ->
            runCatching { WireJson.decodeFromString<Map<String, List<DevicePort>>>(text) }.getOrNull()
        } ?: emptyMap()

    private fun write(value: Map<String, List<DevicePort>>) {
        file.write(WireJson.encodeToString(value))
        _ports.value = value
    }
}
