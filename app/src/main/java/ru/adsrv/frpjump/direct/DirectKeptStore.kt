package ru.adsrv.frpjump.direct

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/** "Keep through the server" for [device] of [profile] while on [network] ([networkIdentity]). */
@Serializable
data class KeptThroughServer(
    @SerialName("profile") val profile: String,
    @SerialName("device") val device: String,
    @SerialName("network") val network: String,
)

/**
 * Devices the owner chose to keep through the server, per profile and
 * network: their tunnels make no direct attempts and no "Direct didn't
 * work" notification there, until the owner retries direct. One small
 * JSON list for all profiles, newest last, at most [MAX_ENTRIES].
 *
 * [load], [keep], [release] and [removeProfile] touch storage: call them
 * off the main thread. [isKept] only reads memory.
 */
class DirectKeptStore(private val storage: TextStorage) {
    private val _kept = MutableStateFlow<List<KeptThroughServer>>(emptyList())
    val kept: StateFlow<List<KeptThroughServer>> = _kept

    @Synchronized
    fun load() {
        _kept.value = runCatching { storage.read()?.let { WireJson.decodeFromString<List<KeptThroughServer>>(it) } }.getOrNull().orEmpty()
    }

    fun isKept(profile: String, device: String, network: String?): Boolean =
        network != null && KeptThroughServer(profile, device, network) in _kept.value

    /** No-op without a network: there is nothing to tie the choice to. */
    @Synchronized
    fun keep(profile: String, device: String, network: String?) {
        if (network == null) return
        val entry = KeptThroughServer(profile, device, network)
        save((_kept.value - entry + entry).takeLast(MAX_ENTRIES))
    }

    /** "Retry direct": [device] tries direct again on [network]. */
    @Synchronized
    fun release(profile: String, device: String, network: String?) {
        if (network == null) return
        save(_kept.value - KeptThroughServer(profile, device, network))
    }

    /** "Forget profile". */
    @Synchronized
    fun removeProfile(profile: String) = save(_kept.value.filterNot { it.profile == profile })

    private fun save(next: List<KeptThroughServer>) {
        if (next == _kept.value) return
        storage.write(WireJson.encodeToString(next))
        _kept.value = next
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
