package ru.adsrv.frpjump.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The assistant's owner-set limits (Settings → Assistant → Limits). Every one has a default and a range; no other copy of these numbers
 * exists: the tool texts and the registry read them from here. A file without them keeps the defaults.
 */
@Serializable
data class AssistantLimits(
    /** How many background waits (`wait_for`) may run at once. */
    @SerialName("waits_at_once") val waitsAtOnce: Int = DEFAULT_WAITS_AT_ONCE,
    /** The longest one wait may be, in minutes. */
    @SerialName("wait_max_minutes") val waitMaxMinutes: Int = DEFAULT_WAIT_MAX_MINUTES,
    /** How many assistant turns started by events may follow one another without a message from the owner. */
    @SerialName("event_replies") val eventReplies: Int = DEFAULT_EVENT_REPLIES,
) {
    companion object {
        const val DEFAULT_WAITS_AT_ONCE = 3
        const val DEFAULT_WAIT_MAX_MINUTES = 30
        const val DEFAULT_EVENT_REPLIES = 5
        val WAITS_AT_ONCE_RANGE = 1..10
        val WAIT_MAX_MINUTES_RANGE = 1..120
        val EVENT_REPLIES_RANGE = 1..20
    }
}

fun validAssistantLimits(l: AssistantLimits): Boolean =
    l.waitsAtOnce in AssistantLimits.WAITS_AT_ONCE_RANGE && l.waitMaxMinutes in AssistantLimits.WAIT_MAX_MINUTES_RANGE &&
        l.eventReplies in AssistantLimits.EVENT_REPLIES_RANGE
