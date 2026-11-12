package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.CancellationException
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.settings.AssistantAvailability
import ru.adsrv.frpjump.settings.AssistantTools

/** What one admitted Send needs; captured under the gate, used outside it. */
internal class Admission(val owner: Pending, val saved: AssistantProviderSnapshot, val settings: AssistantProviderSettings, val text: String,
    val past: List<SavedTurn>, val tools: AssistantTools, val retained: AgentProvider?)

/** Who may commit: authority refresh, stop, the ownership check and Send admission. Every method documents whether it needs the gate. */
internal class RunOwnership(private val c: ChatCells, private val runtime: AssistantChatRuntime,
    private val availability: () -> AssistantAvailability, private val snapshot: () -> AssistantProviderSnapshot) {
    private val gate = runtime.gate

    /** Caller holds the gate. */
    fun refreshLocked() {
        if (c.closed) return
        val now = availability(); val saved = snapshot()
        if (now.disableVersion != c.authority.disableVersion || !now.enabled || saved.revision != c.revision || saved.problem != null) {
            c.provider = null
            c.clearConfirmation()
            if (!now.enabled || now.disableVersion != c.authority.disableVersion) c.confirmations?.grants?.clear()
            if (!c.archive) {
                c.document = SavedChat(draft = c.document.draft).bind(saved.settings)
                c.mutable.value = c.mutable.value.copy(turns = emptyList(), omitted = false, problem = null, currentSources = emptyList(), toolProgress = null,
                    toolFailed = false, toolFailures = emptyList(), information = emptyList(), receipts = emptyList())
            } else c.document = c.document.bind(saved.settings)
            c.pending?.let(::stopLocked)
            c.mutable.value = c.mutable.value.copy(contextChanged = c.document.contextStart > 0, summarized = c.document.summary != null)
        }
        c.authority = now; c.revision = saved.revision
        if (c.pending == null) c.mutable.value = c.mutable.value.copy(busy = runtime.occupied(), stopping = runtime.occupied())
    }

    /** Takes the gate itself; id-based and ownership-free, so a cancelled waiter can always remove its own card. */
    fun clearConfirmation(id: Long) = synchronized(gate) { c.clearConfirmation(id) }

    /** Caller holds the gate. */
    fun stopLocked(owner: Pending) { owner.stopped = true; c.interrupt(); c.mutable.value = c.mutable.value.copy(stopping = true); c.persist() }

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
            if (!c.closed) c.mutable.value = c.mutable.value.copy(busy = false, stopping = false, pendingText = null)
        }
    }

    /** Caller holds the gate. Returns null when nothing starts (and has recorded the visible reason, if any). */
    fun admit(): Admission? {
        refreshLocked()
        if (c.closed || c.pending != null || runtime.occupied() || c.mutable.value.loading || c.mutable.value.historyProblem == ChatHistoryProblem.READ) return null
        val saved = snapshot(); val settings = saved.settings; val text = c.document.draft
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
        val owner = Pending(saved.revision, availability().disableVersion); val enabledTools = availability().tools; val retained = c.provider
        check(runtime.acquire(owner)); c.pending = owner
        c.document = c.document.withTurn(text, c.archive); c.persist()
        c.mutable.value = c.mutable.value.copy(busy = true, stopping = false, pendingText = text, problem = null, toolProgress = null,
            currentSources = emptyList(), toolFailed = false, toolFailures = emptyList(), information = emptyList(), receipts = emptyList(),
            confirmationNotSaved = false)
        return Admission(owner, saved, settings, text, past, enabledTools, retained)
    }
}
