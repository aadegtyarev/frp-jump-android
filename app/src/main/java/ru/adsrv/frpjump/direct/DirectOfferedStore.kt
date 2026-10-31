package ru.adsrv.frpjump.direct

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/** "Direct didn't work" was offered for [device] of [profile] while on [network]. */
@Serializable
data class OfferedDirect(
    @SerialName("profile") val profile: String,
    @SerialName("device") val device: String,
    @SerialName("network") val network: String,
)

/** Which devices were offered "Direct didn't work" on which network ([DirectOffers]). */
interface OfferMemory {
    operator fun contains(entry: OfferedDirect): Boolean
    fun add(entry: OfferedDirect)
    fun removeIf(drop: (OfferedDirect) -> Boolean)
}

/** An [OfferMemory] for one process only. */
class ProcessOfferMemory : OfferMemory {
    private val entries = mutableSetOf<OfferedDirect>()
    override fun contains(entry: OfferedDirect) = entry in entries
    override fun add(entry: OfferedDirect) {
        entries += entry
    }
    override fun removeIf(drop: (OfferedDirect) -> Boolean) {
        entries.removeAll(drop)
    }
}

/**
 * The offers made, kept across a killed process or a reboot: tunnels
 * brought back fail direct the same way on the same network, and the owner
 * was told already. One small JSON list for all profiles, newest last, at
 * most [MAX_ENTRIES]; an unreadable file is an empty list. Changes write
 * the file: use it off the main thread.
 */
class DirectOfferedStore(private val storage: TextStorage) : OfferMemory {
    private var entries: List<OfferedDirect> = emptyList()

    @Synchronized
    fun load() {
        entries = runCatching { storage.read()?.let { WireJson.decodeFromString<List<OfferedDirect>>(it) } }.getOrNull().orEmpty()
    }

    @Synchronized
    override fun contains(entry: OfferedDirect) = entry in entries

    @Synchronized
    override fun add(entry: OfferedDirect) {
        if (entry !in entries) save((entries + entry).takeLast(MAX_ENTRIES))
    }

    @Synchronized
    override fun removeIf(drop: (OfferedDirect) -> Boolean) = save(entries.filterNot(drop))

    private fun save(next: List<OfferedDirect>) {
        if (next == entries) return
        try {
            if (next.isEmpty()) storage.delete() else storage.write(WireJson.encodeToString(next))
            entries = next
        } catch (e: Exception) {
            Log.w(TAG, "offers not saved: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "DirectOfferedStore"
        const val MAX_ENTRIES = 200
    }
}
