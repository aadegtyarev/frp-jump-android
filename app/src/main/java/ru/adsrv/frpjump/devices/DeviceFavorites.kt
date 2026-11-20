package ru.adsrv.frpjump.devices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/** Local presentation preference owned by one profile; writes commit before publication. */
class DeviceFavorites(private val storage: TextStorage) {
    private val mutable = MutableStateFlow(runCatching { storage.read()?.let { WireJson.decodeFromString<Set<String>>(it) } }.getOrNull().orEmpty())
    val favorites: StateFlow<Set<String>> = mutable
    private var closed = false

    @Synchronized fun toggle(device: String) {
        check(!closed) { "Profile is no longer available" }
        val next = if (device in mutable.value) mutable.value - device else mutable.value + device
        storage.write(WireJson.encodeToString(next))
        mutable.value = next
    }

    @Synchronized fun close() { closed = true }
    @Synchronized fun reopen() { closed = false }
}

/** Search and online filtering precede stable favorite-first ordering. */
fun visibleDevices(devices: List<DeviceRow>, query: String, onlyOnline: Boolean, favorites: Set<String>): List<DeviceRow> =
    filterDevices(devices, query).filter { !onlyOnline || it.online }.sortedBy { it.name !in favorites }
