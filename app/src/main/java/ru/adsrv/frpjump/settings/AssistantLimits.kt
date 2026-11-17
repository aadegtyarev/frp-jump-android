package ru.adsrv.frpjump.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The owner-set limits (Settings → Assistant → Limits) of what the assistant, its terminal tools, the tunnels it starts and the terminal's
 * reconnect do. Every one has a default and a range; no other copy of these numbers exists: the consumers read them from here, at use. A file
 * without them keeps the defaults, and each is read on its own ([LIMITS]).
 */
@Serializable
data class AssistantLimits(
    /** How many background waits (`wait_for`) may run at once. */
    @SerialName("waits_at_once") val waitsAtOnce: Int = DEFAULT_WAITS_AT_ONCE,
    /** The longest one wait may be, in minutes. */
    @SerialName("wait_max_minutes") val waitMaxMinutes: Int = DEFAULT_WAIT_MAX_MINUTES,
    /** How many assistant turns started by events may follow one another without a message from the owner. */
    @SerialName("event_replies") val eventReplies: Int = DEFAULT_EVENT_REPLIES,
    /** How many terminal sessions of its own the assistant may have at once (`terminal_open`). */
    @SerialName("sessions_at_once") val sessionsAtOnce: Int = DEFAULT_SESSIONS_AT_ONCE,
    /** The longest one tool call may run, in seconds. */
    @SerialName("tool_timeout_s") val toolTimeoutSeconds: Int = DEFAULT_TOOL_TIMEOUT_S,
    /** The longest the model may take to answer once, in seconds. */
    @SerialName("model_timeout_s") val modelTimeoutSeconds: Int = DEFAULT_MODEL_TIMEOUT_S,
    /** The longest a whole reply (all its steps) may take, in seconds; never less than one call plus one model answer ([replyLimitMs]). */
    @SerialName("reply_s") val replySeconds: Int = DEFAULT_REPLY_S,
    /** How many model steps one reply may take. */
    @SerialName("steps_per_reply") val stepsPerReply: Int = DEFAULT_STEPS_PER_REPLY,
    /** How many tool calls the model may make in one step. */
    @SerialName("calls_per_step") val callsPerStep: Int = DEFAULT_CALLS_PER_STEP,
    /** How many skills the owner's skill store may hold. */
    @SerialName("skills_max") val skillsMax: Int = DEFAULT_SKILLS_MAX,
    /** How many characters one skill's text may have. */
    @SerialName("skill_chars") val skillChars: Int = DEFAULT_SKILL_CHARS,
    /** How many characters the list of skills in a request's system prompt may have. */
    @SerialName("skills_list_chars") val skillsListChars: Int = DEFAULT_SKILLS_LIST_CHARS,
    /** How many characters of one tool's output go to the model. */
    @SerialName("tool_output_chars") val toolOutputChars: Int = DEFAULT_TOOL_OUTPUT_CHARS,
    /** How many characters one model reply may have. */
    @SerialName("reply_chars") val replyChars: Int = DEFAULT_REPLY_CHARS,
    /** How many characters `terminal_input` types at once. */
    @SerialName("input_chars") val inputChars: Int = DEFAULT_INPUT_CHARS,
    /** How many keys `terminal_input` presses at once. */
    @SerialName("keys_at_once") val keysAtOnce: Int = DEFAULT_KEYS_AT_ONCE,
    /** The most history lines one `terminal_read` may ask for. */
    @SerialName("read_lines_max") val readLinesMax: Int = DEFAULT_READ_LINES_MAX,
    /** How long a tunnel the app brings up is waited for, in seconds (the owner's Start and `start_tunnel`). */
    @SerialName("tunnel_wait_s") val tunnelWaitSeconds: Int = DEFAULT_TUNNEL_WAIT_S,
    /** How long a dropped terminal keeps reconnecting, in minutes. */
    @SerialName("reconnect_minutes") val reconnectMinutes: Int = DEFAULT_RECONNECT_MINUTES,
) {
    /** The whole reply's limit in ms: the setting, but never less than one call plus one model answer. */
    fun replyLimitMs(): Long = maxOf(replySeconds, toolTimeoutSeconds + modelTimeoutSeconds) * 1000L

    companion object {
        const val DEFAULT_WAITS_AT_ONCE = 3
        const val DEFAULT_WAIT_MAX_MINUTES = 30
        const val DEFAULT_EVENT_REPLIES = 5
        const val DEFAULT_SESSIONS_AT_ONCE = 3
        const val DEFAULT_TOOL_TIMEOUT_S = 30
        const val DEFAULT_MODEL_TIMEOUT_S = 60
        const val DEFAULT_REPLY_S = 120
        const val DEFAULT_STEPS_PER_REPLY = 12
        const val DEFAULT_CALLS_PER_STEP = 4
        const val DEFAULT_SKILLS_MAX = 50
        const val DEFAULT_SKILL_CHARS = 4_000
        const val DEFAULT_SKILLS_LIST_CHARS = 2_000
        const val DEFAULT_TOOL_OUTPUT_CHARS = 8_000
        const val DEFAULT_REPLY_CHARS = 32_000
        const val DEFAULT_INPUT_CHARS = 2_000
        const val DEFAULT_KEYS_AT_ONCE = 32
        const val DEFAULT_READ_LINES_MAX = 1_000
        const val DEFAULT_TUNNEL_WAIT_S = 45
        const val DEFAULT_RECONNECT_MINUTES = 10
        val WAITS_AT_ONCE_RANGE = 1..10
        val WAIT_MAX_MINUTES_RANGE = 1..120
        val EVENT_REPLIES_RANGE = 1..20
        val SESSIONS_AT_ONCE_RANGE = 1..10
        val TOOL_TIMEOUT_RANGE = 10..300
        val MODEL_TIMEOUT_RANGE = 10..600
        val REPLY_RANGE = 30..1800
        val STEPS_RANGE = 1..50
        val CALLS_RANGE = 1..10
        val SKILLS_MAX_RANGE = 1..200
        val SKILL_CHARS_RANGE = 500..20_000
        val SKILLS_LIST_CHARS_RANGE = 500..8_000
        val TOOL_OUTPUT_RANGE = 2_000..32_000
        val REPLY_CHARS_RANGE = 4_000..128_000
        val INPUT_CHARS_RANGE = 100..10_000
        val KEYS_RANGE = 1..100
        val READ_LINES_RANGE = 100..10_000
        val TUNNEL_WAIT_RANGE = 5..120
        val RECONNECT_RANGE = 1..60
    }
}

/** The groups of the Limits screen, in order. */
enum class LimitGroup { ASSISTANT, WAITS, TERMINAL, TUNNELS }

/** One limit: its settings-file key, range, step of the screen's buttons, group, and how to read and set it. The ONE table the file, the checks and the screen use. */
class LimitSpec(
    val key: String, val group: LimitGroup, val range: IntRange, val step: Int,
    val get: (AssistantLimits) -> Int, val set: (AssistantLimits, Int) -> AssistantLimits,
)

val LIMITS: List<LimitSpec> = listOf(
    LimitSpec("tool_timeout_s", LimitGroup.ASSISTANT, AssistantLimits.TOOL_TIMEOUT_RANGE, 5, { it.toolTimeoutSeconds }, { l, v -> l.copy(toolTimeoutSeconds = v) }),
    LimitSpec("model_timeout_s", LimitGroup.ASSISTANT, AssistantLimits.MODEL_TIMEOUT_RANGE, 10, { it.modelTimeoutSeconds }, { l, v -> l.copy(modelTimeoutSeconds = v) }),
    LimitSpec("reply_s", LimitGroup.ASSISTANT, AssistantLimits.REPLY_RANGE, 30, { it.replySeconds }, { l, v -> l.copy(replySeconds = v) }),
    LimitSpec("steps_per_reply", LimitGroup.ASSISTANT, AssistantLimits.STEPS_RANGE, 1, { it.stepsPerReply }, { l, v -> l.copy(stepsPerReply = v) }),
    LimitSpec("calls_per_step", LimitGroup.ASSISTANT, AssistantLimits.CALLS_RANGE, 1, { it.callsPerStep }, { l, v -> l.copy(callsPerStep = v) }),
    LimitSpec("skills_max", LimitGroup.ASSISTANT, AssistantLimits.SKILLS_MAX_RANGE, 1, { it.skillsMax }, { l, v -> l.copy(skillsMax = v) }),
    LimitSpec("skill_chars", LimitGroup.ASSISTANT, AssistantLimits.SKILL_CHARS_RANGE, 500, { it.skillChars }, { l, v -> l.copy(skillChars = v) }),
    LimitSpec("skills_list_chars", LimitGroup.ASSISTANT, AssistantLimits.SKILLS_LIST_CHARS_RANGE, 500, { it.skillsListChars }, { l, v -> l.copy(skillsListChars = v) }),
    LimitSpec("tool_output_chars", LimitGroup.ASSISTANT, AssistantLimits.TOOL_OUTPUT_RANGE, 1_000, { it.toolOutputChars }, { l, v -> l.copy(toolOutputChars = v) }),
    LimitSpec("reply_chars", LimitGroup.ASSISTANT, AssistantLimits.REPLY_CHARS_RANGE, 4_000, { it.replyChars }, { l, v -> l.copy(replyChars = v) }),
    LimitSpec("waits_at_once", LimitGroup.WAITS, AssistantLimits.WAITS_AT_ONCE_RANGE, 1, { it.waitsAtOnce }, { l, v -> l.copy(waitsAtOnce = v) }),
    LimitSpec("wait_max_minutes", LimitGroup.WAITS, AssistantLimits.WAIT_MAX_MINUTES_RANGE, 1, { it.waitMaxMinutes }, { l, v -> l.copy(waitMaxMinutes = v) }),
    LimitSpec("event_replies", LimitGroup.WAITS, AssistantLimits.EVENT_REPLIES_RANGE, 1, { it.eventReplies }, { l, v -> l.copy(eventReplies = v) }),
    LimitSpec("sessions_at_once", LimitGroup.WAITS, AssistantLimits.SESSIONS_AT_ONCE_RANGE, 1, { it.sessionsAtOnce }, { l, v -> l.copy(sessionsAtOnce = v) }),
    LimitSpec("input_chars", LimitGroup.TERMINAL, AssistantLimits.INPUT_CHARS_RANGE, 100, { it.inputChars }, { l, v -> l.copy(inputChars = v) }),
    LimitSpec("keys_at_once", LimitGroup.TERMINAL, AssistantLimits.KEYS_RANGE, 1, { it.keysAtOnce }, { l, v -> l.copy(keysAtOnce = v) }),
    LimitSpec("read_lines_max", LimitGroup.TERMINAL, AssistantLimits.READ_LINES_RANGE, 100, { it.readLinesMax }, { l, v -> l.copy(readLinesMax = v) }),
    LimitSpec("tunnel_wait_s", LimitGroup.TUNNELS, AssistantLimits.TUNNEL_WAIT_RANGE, 5, { it.tunnelWaitSeconds }, { l, v -> l.copy(tunnelWaitSeconds = v) }),
    LimitSpec("reconnect_minutes", LimitGroup.TUNNELS, AssistantLimits.RECONNECT_RANGE, 1, { it.reconnectMinutes }, { l, v -> l.copy(reconnectMinutes = v) }),
)

fun validAssistantLimits(l: AssistantLimits): Boolean = LIMITS.all { it.get(l) in it.range }
