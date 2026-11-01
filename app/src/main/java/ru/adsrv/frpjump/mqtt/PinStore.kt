package ru.adsrv.frpjump.mqtt

import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/** A pin's form for a topic (see the contract's "Pins"). */
enum class PinMask {
    /** `<t>` */
    TOPIC,

    /** `<t>/+`: the direct children, not `<t>` itself. */
    ONE_LEVEL_BELOW,

    /** `<t>/#`: `<t>` and everything below it. */
    WHOLE_BRANCH,
}

/**
 * The masks a pin on [path] can take: all three, except for the empty
 * first level alone (the branch of the topics starting with `/`), which is
 * no topic (its text is empty) and is pinned only as `/+` or `/#`.
 */
fun pinMasksFor(path: MqttPath): List<PinMask> =
    if (path.format().isEmpty()) listOf(PinMask.ONE_LEVEL_BELOW, PinMask.WHOLE_BRANCH) else PinMask.entries.toList()

/** The topic filter of a pin on [topic] (not the root) with [mask]. */
fun pinFilter(topic: MqttPath, mask: PinMask): String {
    require(!topic.isRoot) { "the root cannot be pinned" }
    val t = topic.format()
    return when (mask) {
        PinMask.TOPIC -> t
        PinMask.ONE_LEVEL_BELOW -> "$t/+"
        PinMask.WHOLE_BRANCH -> "$t/#"
    }
}

/**
 * The masks of [topic] not pinned themselves whose filter a broader pin
 * among [pins] already delivers, each with that pin: the Pin sheet offers
 * them off, saying so, as the search and the Subscriptions tab's "+" do.
 */
fun maskCoveredBy(topic: MqttPath, pins: Collection<String>): Map<PinMask, String> =
    if (topic.isRoot) {
        emptyMap()
    } else {
        pinMasksFor(topic).mapNotNull { mask ->
            val filter = pinFilter(topic, mask)
            if (filter in pins) null else coveringPin(filter, pins)?.let { mask to it }
        }.toMap()
    }

/** The masks [topic] is pinned with among [pins], in [PinMask] order; empty for the root. */
fun pinnedMasks(topic: MqttPath, pins: Collection<String>): List<PinMask> =
    if (topic.isRoot) emptyList() else PinMask.entries.filter { pinFilter(topic, it) in pins }

/**
 * Pins as topic filters in the order they were made, per connection of a
 * profile, kept after sessions and tunnels close. Read on first use, off
 * the main thread.
 */
class PinStore(private val storage: TextStorage) {
    private var loaded: Map<String, List<String>>? = null

    private val pins: Map<String, List<String>>
        get() = loaded ?: read().also { loaded = it }

    @Synchronized
    fun get(profile: String, connectionId: String): List<String> = pins[connectionKey(profile, connectionId)].orEmpty()

    /** Replaces the pins of one connection; an empty list forgets them. */
    @Synchronized
    fun set(profile: String, connectionId: String, filters: List<String>) {
        val key = connectionKey(profile, connectionId)
        save(if (filters.isEmpty()) pins - key else pins + (key to filters))
    }

    @Synchronized
    fun forget(profile: String, connectionId: String) {
        val key = connectionKey(profile, connectionId)
        if (key in pins) save(pins - key)
    }

    @Synchronized
    fun forgetProfile(profile: String) {
        val kept = pins.filterKeys { profileOfKey(it) != profile }
        if (kept != pins) save(kept)
    }

    @Synchronized
    fun wipe() {
        storage.delete()
        loaded = emptyMap()
    }

    private fun read(): Map<String, List<String>> =
        runCatching { storage.read()?.let { WireJson.decodeFromString<Map<String, List<String>>>(it) } }.getOrNull().orEmpty()

    private fun save(value: Map<String, List<String>>) {
        storage.write(WireJson.encodeToString(value))
        loaded = value
    }
}
