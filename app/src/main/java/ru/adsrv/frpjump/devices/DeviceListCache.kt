package ru.adsrv.frpjump.devices

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.OwnedDevice
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/** The last device list the server gave, and whose it was. */
@Serializable
data class CachedDeviceList(
    @SerialName("control_url") val controlUrl: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("fetched_at_ms") val fetchedAtMs: Long,
    val devices: List<OwnedDevice>,
)

/** Keeps the last device list so the app can show it without the server. */
class DeviceListCache(private val storage: TextStorage) {
    /** The cached list if it belongs to this enrollment; null when none or unreadable. */
    @Synchronized
    fun load(controlUrl: String, deviceName: String): CachedDeviceList? {
        val cached = try {
            storage.read()?.let { WireJson.decodeFromString<CachedDeviceList>(it) }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return cached?.takeIf { it.controlUrl == controlUrl && it.deviceName == deviceName }
    }

    @Synchronized
    fun save(list: CachedDeviceList) = storage.write(WireJson.encodeToString(list))

    @Synchronized
    fun wipe() = storage.delete()
}
