package ru.adsrv.frpjump.files

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage
import java.security.MessageDigest

/** A folder's listing as last seen, for the offline view. */
@Serializable
data class CachedListing(
    @SerialName("path") val path: String,
    /** Milliseconds since the epoch. */
    @SerialName("saved_at") val savedAt: Long,
    @SerialName("entries") val entries: List<RemoteEntry>,
    /** The folder had more than [ListingCache.MAX_ENTRIES]; only those were kept. */
    @SerialName("truncated") val truncated: Boolean = false,
)

@Serializable
private data class CachedHome(@SerialName("path") val path: String)

/**
 * The last listing of each folder per (profile, device, port), one small
 * JSON file each in the app's cache, so a folder opened before shows with
 * "No connection" while the device cannot be reached. [storageFor] gives
 * the file of a profile's key; a file that does not parse counts as none.
 */
class ListingCache(
    private val storageFor: (profile: String, key: String) -> TextStorage,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun save(profile: String, device: String, port: Int, path: String, entries: List<RemoteEntry>) {
        val listing = CachedListing(path, now(), entries.take(MAX_ENTRIES), truncated = entries.size > MAX_ENTRIES)
        storageFor(profile, key(device, port, path)).write(WireJson.encodeToString(listing))
    }

    fun load(profile: String, device: String, port: Int, path: String): CachedListing? {
        val text = runCatching { storageFor(profile, key(device, port, path)).read() }.getOrNull() ?: return null
        return runCatching { WireJson.decodeFromString<CachedListing>(text) }.getOrNull()?.takeIf { it.path == path }
    }

    /** Remembers where [user]'s home folder is, so an offline start knows which listing to show. */
    fun saveHome(profile: String, device: String, port: Int, user: String, home: String) {
        storageFor(profile, key(device, port, homeKey(user))).write(WireJson.encodeToString(CachedHome(home)))
    }

    fun loadHome(profile: String, device: String, port: Int, user: String): String? {
        val text = runCatching { storageFor(profile, key(device, port, homeKey(user))).read() }.getOrNull() ?: return null
        return runCatching { WireJson.decodeFromString<CachedHome>(text).path }.getOrNull()?.takeIf { it.startsWith("/") }
    }

    /** Never a folder's path, which always starts with `/`. */
    private fun homeKey(user: String) = "~$user"

    companion object {
        /** Enough for any folder one scrolls through; a bigger one is cut (and marked so) rather than written whole. */
        const val MAX_ENTRIES = 5000

        /** A file name for (device, port, path): no path characters reach the file system. */
        fun key(device: String, port: Int, path: String): String =
            MessageDigest.getInstance("SHA-256").digest("$device\n$port\n$path".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
