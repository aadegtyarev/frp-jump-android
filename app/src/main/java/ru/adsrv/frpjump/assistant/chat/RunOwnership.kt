package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.CancellationException
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.settings.AssistantAvailability
import ru.adsrv.frpjump.settings.AssistantOwnerInstructions
import ru.adsrv.frpjump.settings.AssistantTools

/** What one admitted Send needs; captured under the gate, used outside it. */
internal class Admission(val owner: Pending, val saved: AssistantProviderSnapshot, val settings: AssistantProviderSettings, val text: String,
    val past: List<SavedTurn>, val tools: AssistantTools, val retained: AgentProvider?,
    /** The owner's own text captured at admission with the tool set; text for the model only. */
    val ownerInstructions: String = "",
    /** The wire names the owner declined to turn on in this conversation (read by `request_tool`). */
    val declinedTools: Set<String> = emptySet(),
    /** True when [text] is the app's fixed message and not the owner's draft: the draft is then left as it is. */
    val fixedText: Boolean = false,
    /** The owner's limits at admission (the wait tool's description and caps read them). */
    val limits: ru.adsrv.frpjump.settings.AssistantLimits = ru.adsrv.frpjump.settings.AssistantLimits(),
    /** What the model reads as this turn's text: [text], and after it, one line each, the events that came with the owner's message and the notes. */
    val modelText: String = text,
    val temporaryGeneration: Long? = null, val temporaryOffCount: Long = 0, val httpGetOffCount: Long = 0, val httpUploadOffCount: Long = 0)

/** What a Send carries besides its text: [events] of waits, [notes] for the model only; with [eventOnly] the events are the turn. */
internal class Delivery(val events: List<WaitEvent>, val notes: List<String> = emptyList(), val eventOnly: Boolean = false)

/** Who may commit: authority refresh, stop, the ownership check and Send admission. Every method documents whether it needs the gate. */
internal class RunOwnership(private val c: ChatCells, private val runtime: AssistantChatRuntime,
    private val availability: () -> AssistantAvailability, private val snapshot: () -> AssistantProviderSnapshot,
    private val temporary: TemporaryWorkspace? = null,
    /**
     * Called (under the gate) when this conversation's authority was reset: the assistant was switched off ([disabled] true), or the provider changed or
     * has a problem (the conversation starts again). What lives only with it (the waits) ends.
     */
    private val onRevoked: (disabled: Boolean) -> Unit = {}) {
    private val gate = runtime.gate

    /** Caller holds the gate. */
    fun refreshLocked() {
        if (c.closed) return
        val now = availability(); val saved = snapshot()
        if (now.disableVersion != c.authority.disableVersion || !now.enabled || saved.revision != c.revision || saved.problem != null) {
            c.provider = null
            c.clearConfirmation()
            onRevoked(!now.enabled)
            if (!c.archive) {
                c.document = SavedChat(draft = c.document.draft).bind(saved.settings)
                c.mutable.value = c.mutable.value.copy(turns = emptyList(), omitted = false, problem = null, currentSources = emptyList(), toolProgress = null,
                    toolFailed = false, toolFailures = emptyList(), information = emptyList(), receipts = emptyList())
            } else c.document = c.document.bind(saved.settings)
            c.pending?.let(::stopLocked)
            c.mutable.value = c.mutable.value.copy(contextChanged = c.document.contextStart > 0, summarized = c.document.summary != null, compaction = c.document.compaction())
        }
        c.authority = now; c.revision = saved.revision
        if (c.pending == null) c.mutable.value = c.mutable.value.copy(busy = runtime.occupied(), stopping = runtime.occupied())
    }

    /** Takes the gate itself; id-based and ownership-free, so a cancelled waiter can always remove its own card. */
    fun clearConfirmation(id: Long) = synchronized(gate) { c.clearConfirmation(id) }

    /** Caller holds the gate. */
    fun stopLocked(owner: Pending) { owner.stopped = true; c.interrupt(); c.mutable.value = c.mutable.value.copy(stopping = true); c.persist() }

    /** Stop by the run itself (a question that went unanswered with no screen): same as the owner's Stop, from inside the run. */
    fun stop(owner: Pending) {
        val job = synchronized(gate) { if (c.pending === owner && !owner.stopped) { stopLocked(owner); owner.job } else null }
        job?.cancel()
    }

    /** Caller holds the gate. */
    private fun owns(owner: Pending): Boolean {
        refreshLocked()
        return !c.closed && c.pending === owner && !owner.stopped && availability().enabled && availability().disableVersion == owner.version &&
            snapshot().revision == owner.revision && snapshot().problem == null
    }

    /** The probe runs before the gate; the checked probe and [action] run inside it, only for the current owner. Never suspends. */
    fun <T> guarded(owner: Pending, category: String, action: ChatCells.() -> T): T? {
        runtime.probes.before(category)
        return synchronized(gate) { if (!owns(owner)) null else { runtime.probes.checked(category); c.action() } }
    }

    /** Publishes the prepared provider for reuse, only while still the owner. */
    fun adopt(owner: Pending, active: AgentProvider) = synchronized(gate) { if (!owns(owner)) throw CancellationException(); c.provider = active }

    /** The owner's job ended: caller notifies busy listeners and repaints after this returns. */
    fun completed(owner: Pending) = synchronized(gate) {
        if (c.pending === owner) {
            c.interrupt(); c.persist(); c.pending = null; runtime.release(owner); c.clearConfirmation()
            if (!c.closed) c.mutable.value = c.mutable.value.copy(busy = false, stopping = false, pendingText = null, pendingAttachments = emptyList())
        }
    }

    /**
     * Caller holds the gate. Returns null when nothing starts (and has recorded the visible reason, if any). [fixed] is the app's own
     * message sent as the owner's new Send (the "Turn on" button): it replaces the draft as the text, and the draft is neither used nor cleared.
     */
    fun admit(fixed: String? = null, delivery: Delivery? = null, attachments: List<ChatAttachment> = emptyList()): Admission? {
        refreshLocked()
        if (c.closed || c.pending != null || runtime.occupied() || c.mutable.value.loading || c.clearing || c.mutable.value.historyProblem == ChatHistoryProblem.READ) return null
        val saved = snapshot(); val settings = saved.settings
        // Events of waits: they start a turn of their own ([Delivery.eventOnly], their messages are the text) or ride with the owner's message.
        val eventsText = delivery?.events?.joinToString("\n") { it.message() }.orEmpty()
        val text = if (delivery?.eventOnly == true) eventsText else fixed ?: c.document.draft
        if (!availability().enabled || settings == null || settings.model.isBlank() || saved.problem != null) {
            c.mutable.value = c.mutable.value.copy(problem = if (saved.problem == null) ChatProblem.SETUP else ChatProblem.READ); return null
        }
        if (text.length > ChatLimits.MAX_INPUT) { c.mutable.value = c.mutable.value.copy(problem = ChatProblem.TOO_LONG); return null }
        if (text.isBlank()) return null
        if (c.archive && (c.document.records.size >= 1000 || c.document.records.sumOf { it.chars } >= 8_000_000)) {
            c.mutable.value = c.mutable.value.copy(problem = ChatProblem.HISTORY_FULL); return null
        }
        runtime.probes.checked("admission")
        c.document = c.document.bind(settings)
        if (!c.archive) c.document = c.document.completedOnly()
        val past = c.document.records.drop(c.document.contextStart)
        val owner = Pending(saved.revision, availability().disableVersion); val captured = availability(); val enabledTools = captured.tools; val retained = c.provider
        check(runtime.acquire(owner)); c.pending = owner
        c.document = c.document.withTurn(text, c.archive, delivery?.events?.map(SavedEvent::of).orEmpty(), delivery?.eventOnly == true, delivery?.notes.orEmpty(), attachments); c.persist()
        c.mutable.value = c.mutable.value.copy(busy = true, stopping = false, pendingText = text, problem = null, toolProgress = null,
            // Events of a wait are never drawn as the owner's message: the running turn shows them as event lines at once.
            pendingEvents = delivery?.events.orEmpty(), pendingEventOnly = delivery?.eventOnly == true, pendingAttachments = attachments,
            currentSources = emptyList(), toolFailed = false, toolFailures = emptyList(), information = emptyList(), receipts = emptyList(),
            confirmationNotSaved = false)
        return Admission(owner, saved, settings, text, past, enabledTools, retained, AssistantOwnerInstructions.effective(captured.ownerInstructions),
            c.document.declinedTools.toSet(), fixed != null || delivery?.eventOnly == true, captured.limits,
            withApp(text, attachmentText(attachments) + (if (delivery?.eventOnly == true) emptyList() else delivery?.events?.map { it.message() }.orEmpty()) + delivery?.notes.orEmpty()),
            temporary?.stampLocked(), captured.toolOffs[AssistantToolId.TEMP_FILE_CREATE] ?: 0L, captured.toolOffs[AssistantToolId.HTTP_GET] ?: 0L, captured.toolOffs[AssistantToolId.HTTP_UPLOAD] ?: 0L)
    }
}

/**
 * [text], and after a blank line, one line each, what the app adds for the model: the events that came with the owner's message and the notes
 * (the owner cancelled a wait). They are lines of their own and are never glued to the owner's words.
 */
internal fun withApp(text: String, lines: List<String>): String = if (lines.isEmpty()) text else text + "\n\n" + lines.joinToString("\n")
