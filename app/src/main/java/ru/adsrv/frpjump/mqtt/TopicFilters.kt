package ru.adsrv.frpjump.mqtt

/**
 * Whether the topic filter [filter] matches [topic] by MQTT rules: `+` is
 * exactly one level (which may be empty), `#` this level and all below,
 * including the level above it (`a/#` matches `a`). A filter starting with
 * a wildcard never matches a topic starting with `$`. The filter is taken
 * as valid; checking it is the caller's job.
 */
fun topicMatches(filter: String, topic: MqttPath): Boolean {
    if (topic.isRoot) return false
    val parts = filter.split('/')
    val levels = topic.levels
    if (levels[0].startsWith("$") && (parts[0] == "+" || parts[0] == "#")) return false
    parts.forEachIndexed { k, part ->
        if (part == "#") return true
        if (k >= levels.size) return false
        if (part != "+" && part != levels[k]) return false
    }
    return parts.size == levels.size
}

/**
 * The session's filters in order (the open branch, then the pins): the
 * index of the first that matches [topic], or -1. Under MQTT 5 a message is
 * taken through that subscription only.
 */
fun firstMatchingFilter(filters: List<String>, topic: MqttPath): Int = filters.indexOfFirst { topicMatches(it, topic) }

/** How many of the session's [filters] match [topic]: how many times the broker may deliver one publish. */
fun matchingFilters(filters: List<String>, topic: MqttPath): Int = filters.count { topicMatches(it, topic) }

/**
 * Drops the extra copies of one publish that arrive when several of the
 * session's subscriptions match its topic (3.1.1): a copy is a message
 * identical to the one just taken for that topic (payload, retain, QoS),
 * arriving before any other message for the topic, at most one fewer than
 * the number of matching subscriptions.
 *
 * Memory is bounded: only topics still expecting a copy are remembered,
 * each by the payload's size and hash (not its bytes); an expectation
 * older than [WINDOW_MS] is forgotten (the broker sends the copies of one
 * publish back to back), and past [MAX_EXPECTED] topics the oldest goes
 * first. A forgotten expectation lets a late copy through as a value; a
 * hash collision could drop a different message of the same size and
 * flags right after its predecessor, which is accepted as negligible.
 * Called from the network thread only; not thread-safe.
 */
class CopyFilter(private val now: () -> Long = System::currentTimeMillis) {
    private class Expected(val size: Int, val hash: Int, val retain: Boolean, val qos: Int, val at: Long, var left: Int)

    /** In the order the expectations were made, oldest first. */
    private val expected = object : LinkedHashMap<MqttPath, Expected>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MqttPath, Expected>) = size > MAX_EXPECTED
    }

    /** Topics still expecting a copy. */
    val size: Int get() = expected.size

    /** True for a copy, which must not be shown, counted or kept; [matching] is from [matchingFilters]. */
    fun isCopy(topic: MqttPath, payload: ByteArray, retain: Boolean, qos: Int, matching: Int): Boolean {
        val at = now()
        forgetOlderThan(at - WINDOW_MS)
        val hash = payload.contentHashCode()
        val pending = expected.remove(topic)
        if (pending != null && pending.at >= at - WINDOW_MS && pending.retain == retain && pending.qos == qos && pending.size == payload.size && pending.hash == hash) {
            if (--pending.left > 0) expected[topic] = pending
            return true
        }
        if (matching > 1) expected[topic] = Expected(payload.size, hash, retain, qos, at, matching - 1)
        return false
    }

    fun clear() = expected.clear()

    private fun forgetOlderThan(time: Long) {
        val oldest = expected.values.iterator()
        while (oldest.hasNext() && oldest.next().at < time) oldest.remove()
    }

    companion object {
        const val WINDOW_MS = 2_000L
        const val MAX_EXPECTED = 1_000
    }
}
