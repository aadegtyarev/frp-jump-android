package ru.adsrv.frpjump.devices

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/** The server's last `features` answer, and which server gave it. */
@Serializable
data class CachedFeatures(
    @SerialName("control_url") val controlUrl: String,
    val features: List<String>,
)

/**
 * Keeps the server's last `GET /version` features next to the cached device
 * list, so a feature stays on through a restart while offline.
 */
class ServerFeaturesCache(private val storage: TextStorage) {
    /** The stored features if a server at [controlUrl] gave them; null when none, unreadable or another server's. */
    @Synchronized
    fun load(controlUrl: String): List<String>? {
        val cached = try {
            storage.read()?.let { WireJson.decodeFromString<CachedFeatures>(it) }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return cached?.takeIf { it.controlUrl == controlUrl }?.features
    }

    @Synchronized
    fun save(controlUrl: String, features: List<String>) = storage.write(WireJson.encodeToString(CachedFeatures(controlUrl, features)))

    @Synchronized
    fun wipe() = storage.delete()
}
