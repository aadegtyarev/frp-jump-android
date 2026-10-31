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
 * the number of matching subscriptions. Only topics still expecting a copy
 * are remembered. Called from the network thread only; not thread-safe.
 */
class CopyFilter {
    private class Expected(val payload: ByteArray, val retain: Boolean, val qos: Int, var left: Int)

    private val expected = HashMap<MqttPath, Expected>()

    /** True for a copy, which must not be shown, counted or kept; [matching] is from [matchingFilters]. */
    fun isCopy(topic: MqttPath, payload: ByteArray, retain: Boolean, qos: Int, matching: Int): Boolean {
        val pending = expected[topic]
        if (pending != null && pending.retain == retain && pending.qos == qos && pending.payload.contentEquals(payload)) {
            if (--pending.left <= 0) expected.remove(topic)
            return true
        }
        if (matching > 1) expected[topic] = Expected(payload.copyOf(), retain, qos, matching - 1) else expected.remove(topic)
        return false
    }

    fun clear() = expected.clear()
}
