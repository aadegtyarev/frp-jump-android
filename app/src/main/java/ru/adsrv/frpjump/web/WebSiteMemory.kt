package ru.adsrv.frpjump.web

import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/**
 * Which tunnel the web view showed last. Cookies ignore ports and every
 * tunnel is `127.0.0.1`, so one device's login cookie would be sent to the
 * next device's web UI: the web view clears cookies and web storage
 * whenever it opens another (profile, device, local port) than last time.
 * One for the whole app, as the web view's cookie jar is.
 */
class WebSiteMemory(private val storage: TextStorage) {
    private var last: String? = null
    private var loaded = false

    /** True when [device]:[localPort] of [profile] is not the site the stored web data belongs to. */
    @Synchronized
    fun isOther(profile: String, device: String, localPort: Int): Boolean = current() != key(profile, device, localPort)

    /** Whether the stored web data belongs to a site of [profile]. */
    @Synchronized
    fun belongsTo(profile: String): Boolean =
        current()?.let { runCatching { WireJson.decodeFromString<List<String>>(it) }.getOrNull()?.firstOrNull() } == profile

    /** Call once the web data of the previous site is gone. */
    @Synchronized
    fun remember(profile: String, device: String, localPort: Int) {
        val key = key(profile, device, localPort)
        storage.write(key)
        last = key
        loaded = true
    }

    @Synchronized
    fun forget() {
        storage.delete()
        last = null
        loaded = true
    }

    private fun current(): String? {
        if (!loaded) {
            last = storage.read()
            loaded = true
        }
        return last
    }

    companion object {
        private fun key(profile: String, device: String, localPort: Int) =
            WireJson.encodeToString(listOf(profile, device, localPort.toString()))

        /**
         * v1 kept `[device, port]`; on upgrade it becomes [profile]'s site, so
         * the web view keeps its login. Null when [text] needs no change.
         */
        fun migrateLegacy(text: String, profile: String): String? {
            val parts = runCatching { WireJson.decodeFromString<List<String>>(text) }.getOrNull() ?: return null
            return if (parts.size == 2) WireJson.encodeToString(listOf(profile) + parts) else null
        }
    }
}
