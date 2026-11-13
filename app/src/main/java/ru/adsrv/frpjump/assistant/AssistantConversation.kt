package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.chat.*
import ru.adsrv.frpjump.settings.AssistantAvailability

/**
 * Route-owned execution over one canonical document. File IO and projections run on IO.
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
     * changing calls to run passes a support whose policy is off. The chat ViewModel always passes a real one.
     */
    private val confirmations: ConfirmationSupport?,
    /** Millisecond clock of the Send's active-time limit; tests pass a virtual one. */
    private val activeClock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val gate = runtime.gate
    private val c = ChatCells(SavedChat().bind(snapshot().settings), availability(), snapshot().revision, historyStore, confirmations)
    private val own = RunOwnership(c, runtime, availability, snapshot)
    private val env = TurnEnv(own, runtime, module, io, limits, prepare, requireRevision, profileInformation, sshCommands, historyStore != null, prompts, confirmations, activeClock)
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
    fun close() {
        val cancel = synchronized(gate) {
            if (c.closed) return
            c.pending?.let(own::stopLocked); c.interrupt(); c.persist(); c.closed = true; c.provider = null; c.confirmation = null
            confirmations?.grants?.clear(); c.mutable.value = ChatState(); c.pending?.job
        }; cancel?.cancel()
    }

    /** The owner's answer to the pending card. A stale id, no pending card, a stopped or closed run, or [ConfirmationDecision.NO_ANSWER] is ignored. */
    fun answer(id: Long, decision: ConfirmationDecision) {
        if (decision == ConfirmationDecision.NO_ANSWER) return
        val slot = synchronized(gate) {
            if (c.closed || c.pending?.stopped != false) null
            else c.confirmation?.takeIf { it.id == id }?.also { c.clearConfirmation(id) }
        }
        slot?.answer?.complete(decision)
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
        owner.job.invokeOnCompletion { own.completed(owner); runtime.notifyBusy(); repaint() }
        return owner.job
    }
    companion object {
        const val MAX_INPUT = ChatLimits.MAX_INPUT
        const val MAX_TURNS = ChatLimits.MAX_TURNS
        const val MAX_HISTORY_CHARS = ChatLimits.MAX_HISTORY_CHARS
    }
}
