package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.chat.*
import ru.adsrv.frpjump.settings.AssistantAvailability

/**
 * Execution over one canonical document, owned by the app-lived [AssistantChatOwner] (not by a screen). File IO and projections run on IO.
 * State lives in [ChatCells] under the runtime gate, ownership in [RunOwnership], one Send in [TurnRunner].
 */
class AssistantConversation(
    private val scope: CoroutineScope,
    private val availability: () -> AssistantAvailability,
    private val snapshot: () -> AssistantProviderSnapshot,
    private val prepare: (AssistantProviderSnapshot) -> AgentProvider,
    private val requireRevision: (String?) -> Unit,
    private val module: AgentModule,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The agent's limits of one Send: the provider's token limits and the owner's limits (steps, calls, characters, time) at its start. */
    private val limits: (AssistantProviderSettings, ru.adsrv.frpjump.settings.AssistantLimits) -> AgentLimits = { provider, owner -> provider.limits().withOwnerLimits(owner) },
    private val runtime: AssistantChatRuntime = AssistantChatRuntime(),
    private val profileInformation: ru.adsrv.frpjump.profiles.ProfileInformation? = null,
    private val sshCommands: AssistantSshCommands? = null,
    private val terminalTabs: AssistantTerminalTabs? = null,
    private val tunnels: AssistantTunnels? = null,
    /** The assistant's own terminal sessions for `terminal_open`, `terminal_show` and `terminal_close`; null: none in this conversation. */
    private val sessions: AssistantSessions? = null,
    /** The skills (built-in and the owner's) for `read_skill` and the skills section of the system prompt; null: none in this conversation. */
    private val skills: SkillRegistry? = null,
    /** What background waits watch (tabs, tunnels, the clock); null: no waits in this conversation. */
    private val waitSources: WaitSources? = null,
    /** The waits' pause between looks and their clock are the registry's own; tests pass the registry's sleep here. */
    private val waitSleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val historyStore: AssistantChatHistory? = null,
    private val awaitHistoryClear: suspend () -> Unit = {},
    /** Does a pending Clear now (its Undo notice goes) and returns when that is done or was taken back; a Send calls it before it starts. */
    private val commitHistoryClear: suspend () -> Unit = {},
    /** Required: no compiled-in fallback prompt exists. */
    private val prompts: Lazy<AssistantPrompts>,
    /**
     * Required, no default: leaving it out must be a compile error. Null refuses every changing call (nothing runs); a test that needs
     * changing calls to run passes a support whose policy is off. The chat owner always passes a real one.
     */
    private val confirmations: ConfirmationSupport?,
    /** Millisecond clock of the Send's active-time limit; tests pass a virtual one. */
    private val activeClock: () -> Long = { System.nanoTime() / 1_000_000 },
    /**
     * Millisecond clock of the confirmation card's five-minute limit. Production passes the elapsed-realtime clock, which keeps counting
     * while the device sleeps (the coroutine timers do not); tests pass a virtual one.
     */
    private val realtime: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Called (outside the gate, on the finishing thread) after a Send's owner has been released; the app-lived owner closes an unattended chat here. */
    private val onSettled: () -> Unit = {},
    /** Whether a chat screen was attached at the given real time; a card that runs out while none was ends the attempt (Р43). */
    private val screenAttachedAt: (Long) -> Boolean = { true },
    /** Told when an attempt ended that way; the app-lived owner keeps it for the notification. */
    private val onUnanswered: () -> Unit = {},
    /** Told once when an attempt finished by itself (not stopped, disabled or removed): true for a reply, false for a failure or timeout. */
    private val onEnded: (Boolean) -> Unit = {},
    private val mqttRead: AssistantMqttRead? = null,
    private val mqttPublish: AssistantMqttPublish? = null,
    private val webHttp: AssistantWebHttp? = null,
    private val attachments: MutableStateFlow<List<ChatAttachment>> = MutableStateFlow(emptyList()),
) {
    private val gate = runtime.gate
    /** Events that fired and wait for a turn (under the gate), notes for the model's next turn, and the event replies in a row since the owner last wrote. */
    private val queued = ArrayList<WaitEvent>()
    private val notes = ArrayList<String>()
    private var streak = 0
    /** The conversation's background waits; null without sources. In memory only: a new conversation, the assistant or the switch off end them. */
    private val waits: AssistantWaits? = waitSources?.let { sources ->
        // [changed] is told while the gate may be held (the owner lock is taken by [onSettled], and it is taken before the gate elsewhere): post it.
        AssistantWaits(scope, sources, { availability().limits }, sleep = waitSleep, deliver = ::onWaitEvents, changed = { scope.launch { onSettled() } })
    }

    /**
     * The tool's way to add a wait: only while the assistant is on, the switch is on and the conversation is open, checked under the same gate that
     * ends every wait, so a turn that was running when the switch went off cannot add one (and a wait added just before is ended by the switch).
     */
    internal val waitAdder: WaitAdder? = waits?.let { w ->
        WaitAdder { spec -> synchronized(gate) { val now = availability(); if (c.closed || !now.enabled || !now.tools.waits) AddWait.Invalid else w.add(spec) } }
    }
    private val c = ChatCells(SavedChat().bind(snapshot().settings), availability(), snapshot().revision, historyStore, confirmations)
    private val own = RunOwnership(c, runtime, availability, snapshot, onRevoked = { disabled -> endWaitsLocked(note = disabled) })
    private val env = TurnEnv(own, runtime, module, io, limits, prepare, requireRevision, profileInformation, sshCommands, terminalTabs, tunnels, waitAdder, sessions, skills, historyStore != null, prompts, confirmations, activeClock, realtime, screenAttachedAt, onUnanswered, mqttRead, mqttPublish, webHttp)
    val state: StateFlow<ChatState> = c.mutable

    init {
        waits?.let { w -> scope.launch { w.waits.collect { list -> synchronized(gate) { if (!c.closed) c.mutable.value = c.mutable.value.copy(waits = list) } } } }
        if (historyStore != null) {
            reloadHistory()
            scope.launch { historyStore.problem.collect { problem -> synchronized(gate) {
                if (!c.closed && c.mutable.value.historyProblem != ChatHistoryProblem.READ) c.mutable.value = c.mutable.value.copy(historyProblem = problem)
            } } }
        }
    }
    private fun repaint() {
        val value = synchronized(gate) { if (c.closed) return; c.document }
        scope.launch(io) { val projected = value.views(); synchronized(gate) { c.render(value, projected) } }
    }
    fun reloadHistory() {
        if (historyStore == null) return
        // After a Clear the chat shows empty and typing stays free: the read-back is "restoring", not "loading".
        synchronized(gate) {
            if (c.closed || c.pending != null || c.loading) return
            c.loading = true; val restore = c.clearing
            c.mutable.value = c.mutable.value.copy(loading = !restore, restoring = restore)
        }
        scope.launch {
            try {
                awaitHistoryClear()
                val value = historyStore.load() ?: SavedChat()
                val projected = withContext(io) { value.views() }
                synchronized(gate) {
                    if (!c.closed) {
                        // The draft saved at Clear, then what was typed while the clear waited; nothing of either is lost.
                        val typed = if (c.clearing) c.document.draft else ""
                        val draft = listOf(value.draft, typed).filter { it.isNotEmpty() }.joinToString("\n")
                        // A decline of a tool that is on now is over (it ends when the tool is turned on, also while no chat was open).
                        val tools = availability().tools
                        val declined = value.declinedTools.filter { wire -> AssistantToolId.fromWire(wire)?.let { !tools[it] } == true }
                        c.document = value.bind(snapshot().settings).copy(draft = draft, declinedTools = declined); c.provider = null
                        // The flag that drops saves ends in the same step that publishes the loaded state: a Send or draft saved right after the state turns
                        // loaded must reach the file (#302), so the flag goes first, before anything can observe the state.
                        c.clearing = false; c.loading = false
                        c.mutable.value = c.overlay(c.mutable.value.copy(loading = false, restoring = false, clearing = false, historyProblem = historyStore.problem.value,
                            busy = runtime.occupied(), stopping = runtime.occupied()))
                        c.render(c.document, projected)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { synchronized(gate) { c.loading = false; if (!c.closed) c.mutable.value = c.mutable.value.copy(loading = false, restoring = false, clearing = false, historyProblem = ChatHistoryProblem.READ) } }
            finally { synchronized(gate) { c.loading = false; c.clearing = false }; onSettled(); drainEvents() }
        }
    }
    fun retryHistory() {
        val problem = synchronized(gate) { if (c.closed || c.pending != null || c.loading || c.mutable.value.loading) return; c.mutable.value.historyProblem }
        when (problem) {
            ChatHistoryProblem.WRITE -> historyStore?.retry()
            ChatHistoryProblem.CLEAR -> scope.launch { deleteHistory() }
            else -> reloadHistory()
        }
    }
    fun hideHistory(): Boolean = synchronized(gate) {
        if (c.closed || c.pending != null || runtime.occupied() || c.mutable.value.loading || c.clearing) false else {
            // The saved chat (draft included) is written first, so Undo reads it all back; the document is the new empty conversation.
            c.persist(); c.clearing = true; c.clearFailed = false
            endWaitsLocked(note = false)
            c.document = SavedChat().bind(snapshot().settings); c.provider = null
            c.mutable.value = ChatState(clearing = true); true
        }
    }
    suspend fun deleteHistory() {
        var stopWaiting: Job? = null
        try { historyStore?.clear() }
        catch (cancelled: CancellationException) {
            // Not done and not known to be: read the saved chat back instead of unblocking with the empty document.
            reloadHistory()
            throw cancelled
        }
        catch (_: Exception) { synchronized(gate) { c.clearFailed = true }; reloadHistory(); return }
        synchronized(gate) {
            // The draft (typed while the clear waited, or kept through a Retry) goes into the new conversation.
            val draft = c.document.draft
            c.document = SavedChat().bind(snapshot().settings).copy(draft = draft); c.provider = null; confirmations?.grants?.clear()
            endWaitsLocked(note = false)
            c.clearing = false; c.clearFailed = false
            // A running Send that is waiting for the owner is stopped (nothing runs) rather than left with its card wiped from the state.
            stopWaiting = if (c.confirmation != null) c.pending?.also(own::stopLocked)?.job else null
            if (!c.closed) c.mutable.value = c.overlay(ChatState(draft = draft))
        }
        stopWaiting?.cancel()
        onSettled()
    }
    fun edit(text: String) = synchronized(gate) {
        if (!c.closed && c.pending == null && c.settlingText == null && !c.mutable.value.loading && c.mutable.value.historyProblem != ChatHistoryProblem.READ) {
            if (text.length > MAX_INPUT) c.mutable.value = c.mutable.value.copy(problem = ChatProblem.TOO_LONG)
            else { c.document = c.document.copy(draft = text); c.mutable.value = c.mutable.value.copy(draft = text, problem = null) }
        }
    }
    fun refresh() {
        val cancel = synchronized(gate) { own.refreshLocked(); c.pending?.takeIf { it.stopped }?.job }
        cancel?.cancel()
    }
    fun stop() { val cancel = synchronized(gate) { if (c.closed) null else c.pending?.also(own::stopLocked)?.job }; cancel?.cancel() }
    /** Stops any attempt and ends the conversation at once (app shutdown, tests). The app-lived owner uses [closeIfIdle] instead. */
    fun close() {
        val cancel = synchronized(gate) { closeLocked() }; cancel?.cancel()
    }
    /**
     * Ends the conversation (draft saved, memory released; the "For session" grants are the app's and stay) only when no attempt runs, a card wait included, no clear
     * waits for its Undo or is being done, and no Send waits for that clear: the typed draft and the waiting message are not lost.
     * True when it is closed (also when it already was); false while an attempt still owns it.
     */
    fun closeIfIdle(): Boolean = synchronized(gate) {
        // A wait keeps the conversation alive: its event will start a turn here.
        if (!c.closed && (c.pending != null || c.clearing || c.settlingText != null || waits?.active == true || queued.isNotEmpty())) false else { closeLocked(); true }
    }
    /** Caller holds the gate. */
    private fun closeLocked(): Job? {
        if (c.closed) return null
        endWaitsLocked(note = false)
        c.pending?.let(own::stopLocked); c.interrupt(); c.persist(); c.closed = true; c.provider = null; c.confirmation = null
        c.mutable.value = ChatState(); return c.pending?.job
    }
    /** Saves the draft as it is now (the screen was left); no-op while the history loads or cannot be read. */
    fun saveDraft() { synchronized(gate) { c.persist() } }

    /**
     * The owner's answer to the pending card. A stale id, no pending card, a stopped or closed run, or [ConfirmationDecision.NO_ANSWER]
     * is ignored. A card older than five real minutes is never answered: the press ends it as "no answer" and nothing runs.
     */
    fun answer(id: Long, decision: ConfirmationDecision) {
        if (decision == ConfirmationDecision.NO_ANSWER) return
        val slot = synchronized(gate) {
            if (c.closed || c.pending?.stopped != false) null
            else c.confirmation?.takeIf { it.id == id }?.also { c.clearConfirmation(id) }
        }
        slot?.answer?.complete(if (slot.expired(realtime())) ConfirmationDecision.NO_ANSWER else decision)
    }
    /** Ends a card whose five real minutes have run out (the screen came back and looks): "no answer", nothing runs. */
    fun expireCard() {
        val slot = synchronized(gate) {
            if (c.closed || c.pending?.stopped != false) null
            else c.confirmation?.takeIf { it.expired(realtime()) }?.also { c.clearConfirmation(it.id) }
        }
        slot?.answer?.complete(ConfirmationDecision.NO_ANSWER)
    }
    /** The tools' switches changed (the Tools screen can be opened without leaving Chat). A tool that goes off leaves its session grants: that is the app's cleaner's. */
    fun toolsChanged(enabled: ru.adsrv.frpjump.settings.AssistantTools) {
        // The wait switch off ends every wait and drops what was waiting to be delivered.
        if (!enabled.waits) synchronized(gate) { endWaitsLocked(note = true) }
        // A tool that is on is no longer declined: turning it on (in Tools) ends that "No thanks" (Р56).
        val changed = synchronized(gate) {
            val on = c.document.declinedTools.filter { wire -> AssistantToolId.fromWire(wire)?.let { enabled[it] } == true }
            if (c.closed || on.isEmpty()) false else { setDeclined(c.document.declinedTools - on.toSet()); true }
        }
        if (changed) repaint()
    }

    /**
     * The owner pressed "No thanks" under a reply that asked for [id]: nothing is turned on and nothing is sent; the decline is saved with the
     * conversation, so `request_tool` answers the model with the fixed text until the history is cleared or the tool is turned on.
     * Ignored while a Send runs, the history loads, a clear waits or the history cannot be read.
     */
    fun declineTool(id: AssistantToolId) {
        synchronized(gate) {
            if (c.closed || c.pending != null || c.settlingText != null || c.clearing || c.mutable.value.loading || c.mutable.value.historyProblem == ChatHistoryProblem.READ) return
            if (id.wire !in c.document.declinedTools) setDeclined(c.document.declinedTools + id.wire)
        }
    }
    /** Caller holds the gate. */
    private fun setDeclined(names: List<String>) {
        c.document = c.document.copy(declinedTools = names); c.persist()
        c.mutable.value = c.mutable.value.copy(declinedTools = names.toSet())
    }

    /**
     * A Send while a Clear waits for its Undo commits the clear first (the notice goes, the history is deleted as when it times out), then
     * goes into the new empty conversation. If Undo already won, it goes into the restored one. If the clear failed, nothing is sent and
     * the draft stays.
     */
    fun send() {
        runtime.probes.before("admission")
        // 0: no clear waits, send as usual; 1: nothing to send or one already waits; 2: wait for the clear.
        val mode = synchronized(gate) {
            if (c.closed || !c.clearing) 0
            else if (c.settlingText != null || c.document.draft.isBlank()) 1
            else { c.settlingText = c.document.draft; c.mutable.value = c.overlay(c.mutable.value); 2 }
        }
        if (mode == 0) { admitAndStart(); return }
        if (mode == 1) return
        scope.launch {
            commitHistoryClear()
            state.first { !it.clearing }
            var start: Job? = null
            synchronized(gate) {
                val ok = !c.closed && !c.clearing && !c.clearFailed && c.settlingText != null
                c.settlingText = null
                if (!c.closed) c.mutable.value = c.mutable.value.copy(sending = false, pendingText = null, draft = c.document.draft)
                if (ok) start = admitOwnerLocked(null)?.let(::launch)
            }
            runtime.notifyBusy(); start?.start(); repaint(); onSettled()
        }
    }
    /**
     * The "Turn on" button's follow-up: [text] (the app's fixed "Turned on ..." message) goes as the owner's new Send and the typed draft is
     * left alone. Like [send], it starts nothing while a Send runs, the history loads or a clear waits.
     */
    fun sendOwnerMessage(text: String) {
        runtime.probes.before("admission")
        admitAndStart(text)
    }
    private fun admitAndStart(fixed: String? = null) {
        var start: Job? = null
        synchronized(gate) { start = admitOwnerLocked(fixed)?.let(::launch) }
        runtime.notifyBusy(); start?.start()
    }

    /**
     * Caller holds the gate. The owner's own Send: events that fired and notes for the model ride with it, the count of event replies in a row
     * starts again, and the cap line goes. Nothing is delivered while the switch is off.
     */
    private fun admitOwnerLocked(fixed: String?): Admission? {
        // Nothing that fired is delivered while the switch is off; the notes about waits the owner or the switch ended still go.
        if (!availability().tools.waits) queued.clear()
        val delivery = if (queued.isEmpty() && notes.isEmpty()) null else Delivery(queued.toList(), notes.toList())
        val selected = if (fixed == null) attachments.value else emptyList()
        val admission = own.admit(fixed, delivery, selected) ?: return null
        if (selected.isNotEmpty()) attachments.value = emptyList()
        queued.clear(); notes.clear(); streak = 0
        if (c.mutable.value.eventCap != null) c.mutable.value = c.mutable.value.copy(eventCap = null)
        return admission
    }

    /** Caller holds the gate. Every wait ends, nothing fired is delivered and nothing is noted: a new conversation, the assistant or the switch off. */
    private fun endWaitsLocked(note: Boolean) {
        val ended = waits?.cancelAll().orEmpty()
        queued.clear(); streak = 0
        // The switch or the assistant going off is told to the model on its next turn, once for each wait; a new conversation has nobody to tell.
        if (note) { if (!c.closed) ended.forEach { notes += cancelNote(it) } } else notes.clear()
        if (!c.closed && c.mutable.value.eventCap != null) c.mutable.value = c.mutable.value.copy(eventCap = null)
    }

    /** The owner cancelled one wait («Отменить»): no turn starts; the model is told on its next turn. */
    fun cancelWait(id: Long) {
        val wait = waits?.cancel(id) ?: return
        synchronized(gate) { if (!c.closed) notes += cancelNote(wait) }
    }

    /** The owner cancelled every wait (the chat menu, the notification): one note for each. */
    fun cancelAllWaits() {
        val ended = waits?.cancelAll().orEmpty()
        synchronized(gate) { if (!c.closed) ended.forEach { notes += cancelNote(it) } }
    }

    private fun cancelNote(wait: ActiveWait) =
        "The owner cancelled the wait for ${waitWhat(wait.spec.kind, wait.spec.tabId, wait.subject?.let { ru.adsrv.frpjump.assistant.chat.receiptText(it, 64) }, wait.spec.text, wait.spec.minutes)}."

    /** A wait ended by itself (from any thread): its event waits for a turn. */
    private fun onWaitEvents(events: List<WaitEvent>) {
        synchronized(gate) { if (!c.closed) queued += events }
        drainEvents()
    }

    /**
     * Starts a turn for the events that wait, when nothing stops it: no turn runs and no card waits (they queue until it ends), the switch is on,
     * and fewer than the owner's limit of event replies in a row were given since the owner last wrote; at the limit nothing starts and the chat says so.
     * Several queued events go in one turn.
     */
    private fun drainEvents() {
        var start: Job? = null
        synchronized(gate) {
            if (c.closed || queued.isEmpty()) return
            val now = availability()
            if (!now.enabled || !now.tools.waits) { queued.clear(); return }
            if (c.pending != null || runtime.occupied() || c.clearing || c.loading || c.settlingText != null || c.mutable.value.loading) return
            if (streak >= now.limits.eventReplies) { if (c.mutable.value.eventCap != streak) c.mutable.value = c.mutable.value.copy(eventCap = streak); return }
            val admission = own.admit(delivery = Delivery(queued.toList(), notes.toList(), eventOnly = true)) ?: return
            queued.clear(); notes.clear(); streak++
            start = launch(admission)
        }
        runtime.notifyBusy(); start?.start()
    }
    /** Under the gate: the lazy job and its completion hook exist before the owner is visible to anyone else. */
    private fun launch(admission: Admission): Job {
        val owner = admission.owner
        owner.job = scope.launch(start = CoroutineStart.LAZY) { TurnRunner(env, admission).run() }
        owner.job.invokeOnCompletion {
            // The end is told before the owner is released (so before the activity goes idle), and never for an attempt the owner stopped.
            synchronized(gate) { owner.replied.takeIf { !owner.stopped } }?.let(onEnded)
            own.completed(owner); runtime.notifyBusy(); onSettled(); repaint(); drainEvents()
        }
        return owner.job
    }
    companion object {
        const val MAX_INPUT = ChatLimits.MAX_INPUT
        const val MAX_TURNS = ChatLimits.MAX_TURNS
        const val MAX_HISTORY_CHARS = ChatLimits.MAX_HISTORY_CHARS
    }
}
