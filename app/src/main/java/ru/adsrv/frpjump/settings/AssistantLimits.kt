package ru.adsrv.frpjump.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The assistant's owner-set limits (Settings → Assistant → Limits). Every one has a default and a range; no other copy of these numbers
 * exists: the tool texts, the registry and the terminal tools read them from here. A file without them keeps the defaults.
 */
@Serializable
data class AssistantLimits(
    /** How many background waits (`wait_for`) may run at once. */
    @SerialName("waits_at_once") val waitsAtOnce: Int = DEFAULT_WAITS_AT_ONCE,
    /** The longest one wait may be, in minutes. */
    @SerialName("wait_max_minutes") val waitMaxMinutes: Int = DEFAULT_WAIT_MAX_MINUTES,
    /** How many assistant turns started by events may follow one another without a message from the owner. */
    @SerialName("event_replies") val eventReplies: Int = DEFAULT_EVENT_REPLIES,
    /** How long `terminal_input` (and the `tab_quiet` event) waits for a tab's output to go quiet, in seconds. */
    @SerialName("after_input_seconds") val afterInputSeconds: Int = DEFAULT_AFTER_INPUT_SECONDS,
) {
    companion object {
        const val DEFAULT_WAITS_AT_ONCE = 3
        const val DEFAULT_WAIT_MAX_MINUTES = 30
        const val DEFAULT_EVENT_REPLIES = 5
        const val DEFAULT_AFTER_INPUT_SECONDS = 10
        val WAITS_AT_ONCE_RANGE = 1..10
        val WAIT_MAX_MINUTES_RANGE = 1..120
        val EVENT_REPLIES_RANGE = 1..20
        val AFTER_INPUT_SECONDS_RANGE = 2..60
    }
}

fun validAssistantLimits(l: AssistantLimits): Boolean =
    l.waitsAtOnce in AssistantLimits.WAITS_AT_ONCE_RANGE && l.waitMaxMinutes in AssistantLimits.WAIT_MAX_MINUTES_RANGE &&
        l.eventReplies in AssistantLimits.EVENT_REPLIES_RANGE && l.afterInputSeconds in AssistantLimits.AFTER_INPUT_SECONDS_RANGE
