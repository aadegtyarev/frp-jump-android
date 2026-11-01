package ru.adsrv.frpjump.ssh

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/** A host key as it is pinned: its algorithm and OpenSSH SHA256 fingerprint. */
@Serializable
data class PinnedHostKey(val type: String, val fingerprint: String)

sealed interface HostKeyCheck {
    data object Trusted : HostKeyCheck

    /** Nothing pinned for this device and port yet: ask the owner (trust on first use). */
    data object Unknown : HostKeyCheck

    /** Another key was pinned: block, show both. */
    data class Changed(val pinned: PinnedHostKey) : HostKeyCheck
}

/**
 * Host keys pinned per (device name, target port), not per local address:
 * every tunnel lands on 127.0.0.1, so the address says nothing about the host.
 */
class HostKeyStore(private val storage: TextStorage) {
    private var keys: Map<String, Map<String, PinnedHostKey>> = read()

    @Synchronized
    fun check(device: String, port: Int, presented: PinnedHostKey): HostKeyCheck {
        val pinned = keys[device]?.get(port.toString()) ?: return HostKeyCheck.Unknown
        return if (pinned == presented) HostKeyCheck.Trusted else HostKeyCheck.Changed(pinned)
    }

    @Synchronized
    fun pinned(device: String, port: Int): PinnedHostKey? = keys[device]?.get(port.toString())

    @Synchronized
    fun pin(device: String, port: Int, key: PinnedHostKey) {
        save(keys + (device to (keys[device].orEmpty() + (port.toString() to key))))
    }

    /** "Forget the old key": the next connection asks again. */
    @Synchronized
    fun forget(device: String, port: Int) {
        val remaining = keys[device].orEmpty() - port.toString()
        save(if (remaining.isEmpty()) keys - device else keys + (device to remaining))
    }

    @Synchronized
    fun wipe() {
        storage.delete()
        keys = emptyMap()
    }

    private fun read(): Map<String, Map<String, PinnedHostKey>> =
        runCatching { storage.read()?.let { WireJson.decodeFromString<Map<String, Map<String, PinnedHostKey>>>(it) } }
            .getOrNull().orEmpty()

    private fun save(value: Map<String, Map<String, PinnedHostKey>>) {
        storage.write(WireJson.encodeToString(value))
        keys = value
    }
}
