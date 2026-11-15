package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.assistant.chat.receiptText
import ru.adsrv.frpjump.assistant.tools.AppTool
import ru.adsrv.frpjump.assistant.tools.InvalidArguments
import ru.adsrv.frpjump.assistant.tools.StrictArgs
import ru.adsrv.frpjump.assistant.tools.objectSchema
import ru.adsrv.frpjump.settings.AssistantLimits
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `wait_for`: registers one background wait for an event (a tab, a tunnel or time) and returns at once; the event starts a new turn
 * ([AssistantWaits]). [kinds] are the events available now (tab events need the terminal tabs, tunnel events the profile information); the
 * number of waits at once and the longest wait are the owner's [limits], read when the tool is made, never written here.
 */
internal class WaitForTool(
    private val waits: WaitAdder,
    private val limits: AssistantLimits,
    private val kinds: List<WaitKind>,
    private val timeText: (Long) -> String = ::clockText,
) : AppTool<WaitSpec>(
    AssistantToolId.WAIT_FOR, description(limits.waitMaxMinutes),
    objectSchema {
        enum("event", kinds.map { it.wire })
        integer("tab_id", 1, required = false)
        string("profile_id", 256, required = false)
        string("connection_id", 256, required = false)
        string("text", MAX_TEXT, required = false, minLength = 1)
        string("from", TabCursor.MAX_CHARS, required = false, minLength = 1)
        integer("seconds", 1, required = false, maximum = limits.waitMaxMinutes * SECONDS_PER_MINUTE)
        integer("minutes", 1, maximum = limits.waitMaxMinutes)
    },
) {
    override fun parse(args: StrictArgs) = args.run {
        onlyKeys("event", "tab_id", "profile_id", "connection_id", "text", "from", "seconds", "minutes")
        val kind = WaitKind.fromWire(string("event")) ?: throw InvalidArguments()
        if (kind !in kinds) throw InvalidArguments()
        val minutes = int("minutes", min = 1, max = limits.waitMaxMinutes)
        val tab = optionalInt("tab_id", min = 1)
        val profile = optionalString("profile_id", 256)
        val connection = optionalString("connection_id", 256)
        val text = optionalString("text", MAX_TEXT)
        val from = optionalString("from", TabCursor.MAX_CHARS)?.also { if (TabCursor.parse(it) == null) throw InvalidArguments() }
        val seconds = optionalInt("seconds", min = 1, max = limits.waitMaxMinutes * SECONDS_PER_MINUTE)
        // Each kind takes its own target and nothing else.
        when {
            kind.isTab -> if (tab == null || profile != null || connection != null) throw InvalidArguments()
            kind.isTunnel -> if (profile == null || connection == null || tab != null) throw InvalidArguments()
            else -> if (tab != null || profile != null || connection != null) throw InvalidArguments()
        }
        if (kind == WaitKind.TAB_TEXT) { if (text == null || !oneLine(text)) throw InvalidArguments() } else if (text != null) throw InvalidArguments()
        // A cursor is for the waits that read the tab's output; the silence is for `tab_quiet` alone.
        if (from != null && kind != WaitKind.TAB_TEXT && kind != WaitKind.TAB_QUIET) throw InvalidArguments()
        if (seconds != null && kind != WaitKind.TAB_QUIET) throw InvalidArguments()
        WaitSpec(kind, minutes, tab, profile, connection, text, from, if (kind == WaitKind.TAB_QUIET) seconds ?: DEFAULT_QUIET_SECONDS else null)
    }

    override val invalidArguments = ToolOutput(
        "Provide event and its target: tab_id from terminal_tabs, or profile_id and connection_id from profile_information; minutes 1 to ${limits.waitMaxMinutes}.",
        ToolOutcome.FAILED,
    )

    override suspend fun run(args: WaitSpec): ToolOutput = when (val added = waits.add(args)) {
        is AddWait.Added -> {
            val what = waitWhat(args.kind, args.tabId, added.wait.subject?.let { receiptText(it, NAME_CHARS) }, args.text, args.minutes)
            ToolOutput("Waiting for $what until ${timeText(added.wait.untilMs)}. Finish your answer; a new turn starts when it happens.")
        }
        is AddWait.TooMany -> ToolOutput("Already ${added.atOnce} waits, the most at once. Nothing was added.", ToolOutcome.FAILED)
        AddWait.Invalid -> invalidArguments
    }

    override fun failure(error: Exception) = invalidArguments

    companion object {
        const val MAX_TEXT = 200
        /** The silence `tab_quiet` waits for when `seconds` is not given. */
        const val DEFAULT_QUIET_SECONDS = 10
        private const val SECONDS_PER_MINUTE = 60
        private const val NAME_CHARS = 64

        fun description(maxMinutes: Int) =
            "Wait in the background for one event; you get a new turn when it happens or the time runs out. It returns at once: finish your answer after calling it. " +
                "event: tab_connected, tab_closed, tab_text (with text), tab_quiet, tunnel_on, tunnel_off or time. " +
                "Tab events take tab_id from terminal_tabs; tunnel events take profile_id and connection_id from profile_information. " +
                "minutes: 1 to $maxMinutes. The owner sees every wait and can cancel it. " +
                "tab_text and tab_quiet count output after `from` (a cursor) when given, else after the wait starts. tab_quiet takes `seconds` of silence (default $DEFAULT_QUIET_SECONDS, up to the longest wait)."

        /** One line without control characters. */
        fun oneLine(text: String) = text.isNotEmpty() && text.none { Character.isISOControl(it) || it == ' ' || it == ' ' }

        /** The wall-clock time a wait ends at, "10:45", in the phone's time zone. */
        fun clockText(ms: Long): String = LocalTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
}

/** The wait tool's helpers the chat screen shares. */
object AssistantWaitTools {
    /** The wall-clock time a wait ends at, "10:45". */
    fun clockText(ms: Long): String = WaitForTool.clockText(ms)
}
