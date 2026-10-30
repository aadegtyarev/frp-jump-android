package ru.adsrv.frpjump.web

import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/**
 * Which tunnel the web view showed last. Cookies ignore ports and every
 * tunnel is `127.0.0.1`, so one device's login cookie would be sent to the
 * next device's web UI: the web view clears cookies and web storage
 * whenever it opens another (device, local port) than last time.
 */
class WebSiteMemory(private val storage: TextStorage) {
    private var last: String? = null
    private var loaded = false

    /** True when [device]:[localPort] is not the site the stored web data belongs to. */
    @Synchronized
    fun isOther(device: String, localPort: Int): Boolean = current() != key(device, localPort)

    /** Call once the web data of the previous site is gone. */
    @Synchronized
    fun remember(device: String, localPort: Int) {
        val key = key(device, localPort)
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

    private fun key(device: String, localPort: Int) = WireJson.encodeToString(listOf(device, localPort.toString()))
}
