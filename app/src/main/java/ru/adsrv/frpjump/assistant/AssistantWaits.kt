package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.assistant.chat.receiptText
import ru.adsrv.frpjump.settings.AssistantLimits
import java.util.concurrent.atomic.AtomicLong

/** What a background wait (`wait_for`) waits for. */
enum class WaitKind(val wire: String) {
    TAB_CONNECTED("tab_connected"), TAB_CLOSED("tab_closed"), TAB_TEXT("tab_text"), TAB_QUIET("tab_quiet"),
    TUNNEL_ON("tunnel_on"), TUNNEL_OFF("tunnel_off"), TIME("time");

    val isTab get() = this == TAB_CONNECTED || this == TAB_CLOSED || this == TAB_TEXT || this == TAB_QUIET
    val isTunnel get() = this == TUNNEL_ON || this == TUNNEL_OFF

    companion object { fun fromWire(name: String) = entries.firstOrNull { it.wire == name } }
}

/** One wait as asked for: [tabId] for the tab events, [profileId] and [connectionId] for the tunnel events, [text] for `tab_text`. */
data class WaitSpec(
    val kind: WaitKind, val minutes: Int, val tabId: Int? = null, val profileId: String? = null, val connectionId: String? = null, val text: String? = null,
)

/** A wait that runs: [subject] is the target in the owner's words (a tab's name or automatic label, a tunnel's name); [untilMs] wall-clock time it ends at the latest. */
data class ActiveWait(val id: Long, val spec: WaitSpec, val subject: String?, val untilMs: Long)

/**
 * What happened: the event [kind] of [subject] (the owner's words) in tab [tabId], or, with [timedOut], that the wait ended because its time ran
 * out (or its target went and it was not waiting for that). [text] is the awaited text of `tab_text`, [minutes] the wait of `time`.
 */
data class WaitEvent(
    val kind: WaitKind, val tabId: Int? = null, val subject: String? = null, val text: String? = null, val minutes: Int? = null, val timedOut: Boolean = false,
)

/** How a source ends a wait: the event happened, or the target is gone. */
enum class SourceResult { FIRED, GONE }

/** What the waits watch; the app's implementation reads the tabs and tunnels, a test passes its own. */
interface WaitSources {
    /** The target in the owner's words (tab name or automatic label, tunnel name); null when the tab or the saved tunnel is unknown. Not for time waits. */
    fun subject(spec: WaitSpec): String?
    /**
     * Suspends until the event of [spec] happens ([SourceResult.FIRED]; a tab or tunnel already in the state waited for fires at once) or the
     * target is gone ([SourceResult.GONE]). Cancelled when the wait ends otherwise.
     */
    suspend fun await(spec: WaitSpec): SourceResult
}

/** What the `wait_for` tool needs: to add one wait. The conversation hands the tool a guarded one (see [AssistantConversation]); a test passes the registry. */
fun interface WaitAdder {
    fun add(spec: WaitSpec): AddWait
}

sealed interface AddWait {
    data class Added(val wait: ActiveWait) : AddWait
    /** [atOnce] waits already run, the most the owner allows. */
    data class TooMany(val atOnce: Int) : AddWait
    data object Invalid : AddWait
}

/**
 * The waits of one conversation, in memory: each runs until its event happens, its time runs out, the owner cancels it or [cancelAll] ends them
 * all (a new conversation, the assistant or the switch off, Stop). A wait that ends by itself hands its event to [deliver]; a cancelled one hands
 * nothing. The caps come from [limits] at the moment a wait is added. Process death drops every wait.
 */
class AssistantWaits(
    private val scope: CoroutineScope,
    private val sources: WaitSources,
    private val limits: () -> AssistantLimits,
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Sleeps [ms] (the wait's time); tests pass their own. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val deliver: (List<WaitEvent>) -> Unit,
    /** Told after the set of waits changed; never under a lock of the registry. The caller of [cancelAll] may hold its own, so this must not take any that precede it. */
    private val changed: () -> Unit = {},
    /** How long a time wait sleeps at a time: the wall clock is looked at again after each, so a sleep the device did not count (deep sleep) ends late at most by this. */
    private val tickMs: Long = TICK_MS,
) : WaitAdder {
    private val ids = AtomicLong()
    private val jobs = HashMap<Long, Job>()
    private val _waits = MutableStateFlow<List<ActiveWait>>(emptyList())
    val waits: StateFlow<List<ActiveWait>> = _waits

    /** Whether any wait runs. */
    val active: Boolean get() = _waits.value.isNotEmpty()

    override fun add(spec: WaitSpec): AddWait {
        val limit = limits()
        if (spec.minutes !in 1..limit.waitMaxMinutes) return AddWait.Invalid
        // The owner's word for the target, cleaned of control and invisible characters and cut, so that a name cannot add lines to a message.
        val subject = if (spec.kind == WaitKind.TIME) null else sources.subject(spec)?.let { receiptText(it, SUBJECT_CHARS) } ?: return AddWait.Invalid
        val job: Job
        val wait: ActiveWait
        synchronized(jobs) {
            if (_waits.value.size >= limit.waitsAtOnce) return AddWait.TooMany(limit.waitsAtOnce)
            wait = ActiveWait(ids.incrementAndGet(), spec, subject, wallClock() + spec.minutes * MS_PER_MINUTE)
            job = scope.launch(start = CoroutineStart.LAZY) { run(wait) }
            jobs[wait.id] = job
            _waits.update { it + wait }
        }
        changed()
        job.start()
        return AddWait.Added(wait)
    }

    private suspend fun run(wait: ActiveWait) {
        val spec = wait.spec
        val result = CompletableDeferred<SourceResult?>()
        // A time wait has nothing to watch: only its timer ends it.
        val watcher = scope.launch { if (spec.kind != WaitKind.TIME) result.complete(sources.await(spec)) }
        // The time is the wall clock's: a sleep that did not count the time the device slept ends, and the check below lets it end late rather than never.
        val timer = scope.launch {
            while (wallClock() < wait.untilMs) sleep(minOf(tickMs, wait.untilMs - wallClock()))
            result.complete(null)
        }
        val outcome = try { result.await() } finally { watcher.cancel(); timer.cancel() }
        val event = when {
            outcome == SourceResult.FIRED -> WaitEvent(spec.kind, spec.tabId, wait.subject, spec.text, spec.minutes.takeIf { spec.kind == WaitKind.TIME })
            // The target went: that is what a "closed" or "off" wait waited for; any other wait ends at once, as if its time ran out.
            outcome == SourceResult.GONE && (spec.kind == WaitKind.TAB_CLOSED || spec.kind == WaitKind.TUNNEL_OFF) ->
                WaitEvent(spec.kind, spec.tabId, wait.subject, spec.text)
            spec.kind == WaitKind.TIME && outcome == null -> WaitEvent(WaitKind.TIME, minutes = spec.minutes)
            else -> WaitEvent(spec.kind, spec.tabId, wait.subject, spec.text, spec.minutes, timedOut = true)
        }
        if (!remove(wait.id)) return
        changed()
        deliver(listOf(event))
    }

    /** Takes the wait out; false when it was already removed (cancelled). */
    private fun remove(id: Long): Boolean = synchronized(jobs) {
        if (jobs.remove(id) == null) false else { _waits.update { list -> list.filter { it.id != id } }; true }
    }

    /** The owner cancels one wait: no event is delivered. The wait, or null when it had ended. */
    fun cancel(id: Long): ActiveWait? {
        val (wait, job) = synchronized(jobs) {
            val job = jobs.remove(id) ?: return null
            val wait = _waits.value.firstOrNull { it.id == id }
            _waits.update { list -> list.filter { it.id != id } }
            wait to job
        }
        job.cancel()
        changed()
        return wait
    }

    /** Ends every wait, delivering nothing. The waits that ran. */
    fun cancelAll(): List<ActiveWait> {
        val (ended, running) = synchronized(jobs) {
            val ended = _waits.value
            val running = jobs.values.toList()
            jobs.clear(); _waits.value = emptyList()
            ended to running
        }
        running.forEach { it.cancel() }
        if (ended.isNotEmpty()) changed()
        return ended
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
        const val TICK_MS = 30_000L
        const val SUBJECT_CHARS = 64
    }
}

/** What a wait waits for, in the model's English: "tab 3 to connect", "\"ready\" in tab 3", "tunnel pi ssh to turn on", "10 min". [subject] is a tunnel's name. */
fun waitWhat(kind: WaitKind, tabId: Int?, subject: String?, text: String?, minutes: Int?): String = when (kind) {
    WaitKind.TAB_CONNECTED -> "tab $tabId to connect"
    WaitKind.TAB_CLOSED -> "tab $tabId to close"
    WaitKind.TAB_TEXT -> "\"$text\" in tab $tabId"
    WaitKind.TAB_QUIET -> "tab $tabId to go quiet"
    WaitKind.TUNNEL_ON -> "tunnel $subject to turn on"
    WaitKind.TUNNEL_OFF -> "tunnel $subject to turn off"
    WaitKind.TIME -> "$minutes min"
}

/** The message a finished wait gives the model (app-generated: it starts with "Event:" or "Wait for", never the owner's words). */
fun WaitEvent.message(): String = when {
    timedOut -> "Wait for ${waitWhat(kind, tabId, subject, text, minutes)} ended: the time ran out."
    else -> when (kind) {
        WaitKind.TAB_CONNECTED -> "Event: tab $tabId connected."
        WaitKind.TAB_CLOSED -> "Event: tab $tabId closed."
        WaitKind.TAB_TEXT -> "Event: \"$text\" appeared in tab $tabId."
        WaitKind.TAB_QUIET -> "Event: tab $tabId is quiet."
        WaitKind.TUNNEL_ON -> "Event: tunnel $subject is on."
        WaitKind.TUNNEL_OFF -> "Event: tunnel $subject is off."
        WaitKind.TIME -> "Event: $minutes min passed."
    }
}
