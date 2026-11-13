package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
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
    private val limits: (AssistantProviderSettings) -> AgentLimits = { it.limits() },
    private val runtime: AssistantChatRuntime = AssistantChatRuntime(),
    private val profileInformation: ru.adsrv.frpjump.profiles.ProfileInformation? = null,
    private val sshCommands: AssistantSshCommands? = null,
    private val historyStore: AssistantChatHistory? = null,
    private val awaitHistoryClear: suspend () -> Unit = {},
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
) {
    private val gate = runtime.gate
    private val c = ChatCells(SavedChat().bind(snapshot().settings), availability(), snapshot().revision, historyStore, confirmations)
    private val own = RunOwnership(c, runtime, availability, snapshot)
    private val env = TurnEnv(own, runtime, module, io, limits, prepare, requireRevision, profileInformation, sshCommands, historyStore != null, prompts, confirmations, activeClock, realtime, screenAttachedAt, onUnanswered)
    val state: StateFlow<ChatState> = c.mutable

    init {
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
        synchronized(gate) { if (c.closed || c.pending != null || c.loading) return; c.loading = true; c.mutable.value = c.mutable.value.copy(loading = true) }
        scope.launch {
            try {
                awaitHistoryClear()
                val value = historyStore.load() ?: SavedChat()
                val projected = withContext(io) { value.views() }
                synchronized(gate) {
                    if (!c.closed) {
                        c.document = value.bind(snapshot().settings); c.provider = null
                        c.mutable.value = c.mutable.value.copy(loading = false, historyProblem = historyStore.problem.value,
                            busy = runtime.occupied(), stopping = runtime.occupied())
                        c.render(c.document, projected)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { synchronized(gate) { if (!c.closed) c.mutable.value = c.mutable.value.copy(loading = false, historyProblem = ChatHistoryProblem.READ) } }
            finally { synchronized(gate) { c.loading = false } }
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
        if (c.closed || c.pending != null || runtime.occupied() || c.mutable.value.loading) false else {
            c.persist(); c.mutable.value = ChatState(loading = true); true
        }
    }
    suspend fun deleteHistory() {
        var stopWaiting: Job? = null
        try { historyStore?.clear() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reloadHistory(); return }
        synchronized(gate) {
            c.document = SavedChat().bind(snapshot().settings); c.provider = null; confirmations?.grants?.clear()
            // A running Send that is waiting for the owner is stopped (nothing runs) rather than left with its card wiped from the state.
            stopWaiting = if (c.confirmation != null) c.pending?.also(own::stopLocked)?.job else null
            if (!c.closed) c.mutable.value = ChatState()
        }
        stopWaiting?.cancel()
    }
    fun edit(text: String) = synchronized(gate) {
        if (!c.closed && c.pending == null && !c.mutable.value.loading && c.mutable.value.historyProblem != ChatHistoryProblem.READ) {
            if (text.length > MAX_INPUT) c.mutable.value = c.mutable.value.copy(problem = ChatProblem.TOO_LONG)
            else { c.document = c.document.copy(draft = text); c.mutable.value = c.mutable.value.copy(draft = text, problem = null) }
        }
    }
    /**
     * Puts [quote] after the draft (a terminal selection the owner asked to ask about). Like [edit]: nothing changes while the history
     * loads or cannot be read, or while a Send runs; a result over [MAX_INPUT] is not applied and says "too long". True when applied.
     */
    fun appendToDraft(quote: String): Boolean = synchronized(gate) {
        if (c.closed || c.pending != null || c.mutable.value.loading || c.mutable.value.historyProblem == ChatHistoryProblem.READ) return false
        val text = ChatQuote.appended(c.document.draft, quote)
        if (text.length > MAX_INPUT) { c.mutable.value = c.mutable.value.copy(problem = ChatProblem.TOO_LONG); return false }
        c.document = c.document.copy(draft = text); c.mutable.value = c.mutable.value.copy(draft = text, problem = null)
        true
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
     * Ends the conversation (draft saved, grants cleared, memory released) only when no attempt runs, a card wait included.
     * True when it is closed (also when it already was); false while an attempt still owns it.
     */
    fun closeIfIdle(): Boolean = synchronized(gate) { if (!c.closed && c.pending != null) false else { closeLocked(); true } }
    /** Caller holds the gate. */
    private fun closeLocked(): Job? {
        if (c.closed) return null
        c.pending?.let(own::stopLocked); c.interrupt(); c.persist(); c.closed = true; c.provider = null; c.confirmation = null
        confirmations?.grants?.clear(); c.mutable.value = ChatState(); return c.pending?.job
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
    /** A tool that is switched off loses its session grants (the Tools screen can be opened without leaving Chat). */
    fun toolsChanged(enabled: ru.adsrv.frpjump.settings.AssistantTools) {
        val grants = confirmations?.grants ?: return
        for (id in AssistantToolId.entries) if (!enabled[id]) grants.clear(id)
    }

    fun send() {
        runtime.probes.before("admission")
        var start: Job? = null
        synchronized(gate) { start = own.admit()?.let(::launch) }
        runtime.notifyBusy(); start?.start()
    }
    /** Under the gate: the lazy job and its completion hook exist before the owner is visible to anyone else. */
    private fun launch(admission: Admission): Job {
        val owner = admission.owner
        owner.job = scope.launch(start = CoroutineStart.LAZY) { TurnRunner(env, admission).run() }
        owner.job.invokeOnCompletion {
            // The end is told before the owner is released (so before the activity goes idle), and never for an attempt the owner stopped.
            synchronized(gate) { owner.replied.takeIf { !owner.stopped } }?.let(onEnded)
            own.completed(owner); runtime.notifyBusy(); onSettled(); repaint()
        }
        return owner.job
    }
    companion object {
        const val MAX_INPUT = ChatLimits.MAX_INPUT
        const val MAX_TURNS = ChatLimits.MAX_TURNS
        const val MAX_HISTORY_CHARS = ChatLimits.MAX_HISTORY_CHARS
    }
}
