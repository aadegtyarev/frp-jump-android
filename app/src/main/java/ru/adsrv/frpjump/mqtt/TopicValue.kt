package ru.adsrv.frpjump.mqtt

/** The protocol a session speaks; it decides how the retained mark is read. */
enum class MqttVersion { V5, V3_1_1 }

/**
 * One value of a topic as the session took it. [payload] is the session's
 * own copy and is never changed; readers must not change it either.
 */
class TopicValue(
    val payload: ByteArray,
    val qos: Int,
    /** Milliseconds since the epoch, when it arrived. */
    val receivedAt: Long,
    /** The retained mark. */
    val retained: Boolean,
    /** An empty message that removed the retained value: shown as "(cleared)", without the mark. */
    val cleared: Boolean,
    /**
     * Delivered as retained on a subscribe, as a topic's first value: the
     * broker kept it from before the session saw it, so when it was set is
     * not known ([receivedAt] is only when it arrived). Shown as "before
     * connecting" rather than an age, until a new value comes.
     */
    val beforeSession: Boolean = false,
) {
    /** An empty message that is an ordinary value: shown as `(empty value)`. */
    val isEmptyValue: Boolean get() = payload.isEmpty() && !cleared

    /** When it changed as far as the session knows; null for a value from [beforeSession]. */
    val changedAt: Long? get() = if (beforeSession) null else receivedAt

    companion object {
        /**
         * A message's value with its marks. MQTT 5 (Retain As Published):
         * the flag is the publisher's; empty and retained is a clear.
         * 3.1.1, whose brokers drop the flag on live messages: the mark is
         * sticky, set by any non-empty message with the flag and kept from
         * [previous]; an empty message drops it and counts as a clear when
         * it carried the flag or the topic was marked.
         */
        fun of(payload: ByteArray, retain: Boolean, qos: Int, at: Long, version: MqttVersion, previous: TopicValue?, beforeSession: Boolean = false): TopicValue {
            val empty = payload.isEmpty()
            val wasMarked = previous?.retained == true
            val retained: Boolean
            val cleared: Boolean
            when (version) {
                MqttVersion.V5 -> {
                    retained = retain && !empty
                    cleared = retain && empty
                }
                MqttVersion.V3_1_1 -> {
                    retained = !empty && (retain || wasMarked)
                    cleared = empty && (retain || wasMarked)
                }
            }
            return TopicValue(payload.copyOf(), qos, at, retained, cleared, beforeSession && retained)
        }
    }
}
