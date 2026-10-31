package ru.adsrv.frpjump.mqtt

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/**
 * What the publish sheet remembers of one topic: how it was last sent
 * ([retain], [qos], JSON or text) and the last payloads sent, newest first.
 */
@Serializable
data class SentTopic(
    val topic: String,
    val retain: Boolean = false,
    val qos: Int = 1,
    val json: Boolean = false,
    val payloads: List<String> = emptyList(),
)

/**
 * The publish sheet's memory, per connection of a profile, kept after
 * sessions and tunnels close: per topic its retain, QoS and format as last
 * sent and its last [MAX_PAYLOADS] payloads. Bounded: [MAX_TOPICS] topics
 * a connection, the least recently sent dropped first; a payload over
 * [MAX_PAYLOAD_BYTES] is not kept, a topic over [MAX_TOPIC_BYTES] not at all; at most [MAX_CHARS] of payloads a
 * connection, the oldest of the least recent topics dropped first. Read on
 * first use, off the main thread.
 */
class PublishMemory(private val storage: TextStorage) {
    private var loaded: Map<String, List<SentTopic>>? = null

    private val memory: Map<String, List<SentTopic>>
        get() = loaded ?: read().also { loaded = it }

    /** The topics of one connection, the most recently sent first. */
    @Synchronized
    fun get(profile: String, connectionId: String): List<SentTopic> = memory[connectionKey(profile, connectionId)].orEmpty()

    /**
     * Remembers a send of [payload] to [topic] with [retain], [qos] and the
     * format; returns the connection's topics after it. A topic over
     * [MAX_TOPIC_BYTES] is not remembered. Kept in memory even when writing
     * the file fails (which then throws).
     */
    @Synchronized
    fun sent(profile: String, connectionId: String, topic: String, payload: String, retain: Boolean, qos: Int, json: Boolean): List<SentTopic> {
        val key = connectionKey(profile, connectionId)
        val topics = memory[key].orEmpty()
        if (utf8Length(topic) > MAX_TOPIC_BYTES) return topics
        val before = topics.firstOrNull { it.topic == topic }?.payloads.orEmpty()
        val payloads = if (utf8Length(payload) > MAX_PAYLOAD_BYTES) before else (listOf(payload) + (before - payload)).take(MAX_PAYLOADS)
        val updated = bounded(listOf(SentTopic(topic, retain, qos.coerceIn(0, 2), json, payloads)) + topics.filter { it.topic != topic })
        save(memory + (key to updated))
        return updated
    }

    @Synchronized
    fun forget(profile: String, connectionId: String) {
        val key = connectionKey(profile, connectionId)
        if (key in memory) save(memory - key)
    }

    @Synchronized
    fun forgetProfile(profile: String) {
        val kept = memory.filterKeys { profileOfKey(it) != profile }
        if (kept != memory) save(kept)
    }

    @Synchronized
    fun wipe() {
        storage.delete()
        loaded = emptyMap()
    }

    private fun read(): Map<String, List<SentTopic>> =
        runCatching { storage.read()?.let { WireJson.decodeFromString<Map<String, List<SentTopic>>>(it) } }.getOrNull().orEmpty()
            // A QoS the sheet cannot show is taken as the nearest one.
            .mapValues { (_, topics) -> topics.map { if (it.qos in 0..2) it else it.copy(qos = it.qos.coerceIn(0, 2)) } }

    private fun save(value: Map<String, List<SentTopic>>) {
        // In memory first: a failed write loses only the file's copy.
        loaded = value
        storage.write(WireJson.encodeToString(value))
    }

    companion object {
        /** Payloads kept a topic. */
        const val MAX_PAYLOADS = 10

        /** Topics kept a connection. */
        const val MAX_TOPICS = 100

        /** A longer payload (UTF-8 bytes) is sent but not kept. */
        const val MAX_PAYLOAD_BYTES = 4 * 1024

        /** A longer topic (UTF-8 bytes) is sent but nothing of it is kept. */
        const val MAX_TOPIC_BYTES = 1024

        /** Characters of payloads kept a connection in all. */
        const val MAX_CHARS = 64 * 1024

        /** [topics], most recent first, cut to [MAX_TOPICS] and [MAX_CHARS] of payloads. */
        internal fun bounded(topics: List<SentTopic>): List<SentTopic> {
            var left = MAX_CHARS
            var full = false
            return topics.take(MAX_TOPICS).map { t ->
                // Once a payload does not fit, nothing older is kept: no gaps in what is remembered.
                val kept = t.payloads.takeWhile { p ->
                    (!full && p.length <= left).also { fits -> if (fits) left -= p.length else full = true }
                }
                if (kept.size == t.payloads.size) t else t.copy(payloads = kept)
            }
        }
    }
}
