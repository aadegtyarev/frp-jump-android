package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.*
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.profiles.ProfileInformation
import ru.adsrv.frpjump.settings.ConfirmationTexts

/** Conversation-lived dependencies of every Send. */
internal class TurnEnv(val own: RunOwnership, val runtime: AssistantChatRuntime, val module: AgentModule, val io: CoroutineDispatcher,
    val limits: (AssistantProviderSettings) -> AgentLimits, val prepare: (AssistantProviderSnapshot) -> AgentProvider,
    val requireRevision: (String?) -> Unit, val profileInformation: ProfileInformation?, val sshCommands: AssistantSshCommands?,
    val archive: Boolean, val prompts: Lazy<AssistantPrompts>, val confirm: ConfirmationSupport?,
    val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Clock of the card's five-minute limit (elapsed real time, device sleep included). */
    val realtime: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Whether a chat screen was attached at that real time: a card that runs out while none was ends the attempt. */
    val screenAttachedAt: (Long) -> Boolean = { true },
    /** Told once when an attempt ended that way (the owner keeps it for the notification). */
    val onUnanswered: () -> Unit = {}) {
    val confirmIds = java.util.concurrent.atomic.AtomicLong()
}

private class HistoryFull : Exception()

/**
 * One admitted Send: provider, tools, summary, kernel run, events into the document and the final problem. All commits go
 * through [RunOwnership.guarded]; nothing here takes the gate directly or suspends inside it. The flags below are written
 * and read on the run's IO threads exactly as the former captured locals were.
 */
internal class TurnRunner(private val env: TurnEnv, private val a: Admission) {
    private val owner = a.owner
    private val own = env.own
    private val deadline = ActiveDeadline(SEND_LIMIT_MS, env.clock)
    private companion object { const val SEND_LIMIT_MS = 120_000L; const val CARD_POLL_MS = 5_000L }
    private var transportTimeout = false
    private var invalidReply = false
    private var summaryProblem: ChatProblem? = null
    private var summaryStatus: Int? = null
    private var summaryProvider: AgentProvider? = null
    /** Set by [ask] when a question ran out of time with no chat screen attached. */
    private var unanswered = false

    suspend fun run() {
        // Active time only: a pause (owner confirmation, from the next change on) does not count against the Send.
        try { deadline.guard { exchange() } }
        catch (_: ActiveDeadlineExpired) { withContext(NonCancellable + env.io) { finish(ChatProblem.TIMEOUT) } }
        catch (_: HistoryFull) { withContext(NonCancellable + env.io) { finish(ChatProblem.CONTEXT_FULL) } }
        catch (_: TimeoutCancellationException) { withContext(NonCancellable + env.io) { finish(ChatProblem.TIMEOUT) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { withContext(NonCancellable + env.io) { finish(setupProblem(failure, ChatProblem.SETUP), "setup-error") } }
    }

    private suspend fun exchange() {
        // First use of the lazy prompts reads assets: off Main, on the Send's IO path, before any key decrypt or provider build;
        // a broken asset is an ordinary setup problem.
        val (active, prompts) = withContext(env.io) {
            env.requireRevision(a.saved.revision)
            val loaded = env.prompts.value
            (a.retained ?: env.prepare(a.saved)) to loaded
        }
        own.adopt(owner, active)
        withContext(env.io + SideEffectAdmission { category -> own.guarded(owner, category) { true } ?: false }) {
            val past = a.past.map { it to it.model(active) }
            val tools = AssistantToolFactory.create(a.tools, env.runtime, active, env.profileInformation, env.sshCommands, ::publishSource, a.declinedTools)
            val summary = summary(active, prompts.summary)
            val byName = tools.associateBy { it.spec.name }
            val result = env.module.run(toolProvider(active, past), tools, prompts.system, a.text, past.map { it.second },
                env.limits(a.settings), ownerInstructions = a.ownerInstructions, prepareRequest = { request -> prepared(summary, request) }, onEvent = { event -> onEvent(active, event) },
                authorize = { call -> authorize(byName, call) })
            env.requireRevision(a.saved.revision)
            val problem = finishProblem(summaryProblem, result.finish, transportTimeout, invalidReply)
            finish(problem, if (problem == null) "answer" else "finish-error", summaryStatus)
        }
    }

    /** The kernel's `authorize` hook: see [authorizeCall]. Pauses the Send's active-time clock while the owner is asked. */
    private suspend fun authorize(tools: Map<String, AgentTool>, call: ToolCall): ToolOutput? {
        val output = authorizeCall(env.confirm, tools, call, wait = { request, info -> deadline.paused { ask(request, info) } },
            onNotSaved = { own.guarded(owner, "notice") { mutable.value = mutable.value.copy(confirmationNotSaved = true) } }, io = env.io)
        if (unanswered) endUnanswered(call)
        return output
    }

    /** Shows the card and waits at most five minutes; a timeout is [ConfirmationDecision.NO_ANSWER], never a cancellation. */
    private suspend fun ask(request: ChangeRequest, info: ConfirmationTargetInfo?): ConfirmationDecision {
        val answer = CompletableDeferred<ConfirmationDecision>()
        val id = env.confirmIds.incrementAndGet()
        val started = env.realtime()
        val slot = ConfirmationSlot(id, answer, started)
        own.guarded(owner, "confirmation") {
            confirmation = slot
            mutable.value = mutable.value.copy(confirmation = PendingConfirmation(id, request.tool, request.command, request.target, info))
        } ?: throw CancellationException("Send no longer owned")
        try {
            // The coroutine timer does not count device sleep, so the wait also looks at the real clock every few seconds.
            val decision = withTimeoutOrNull(ConfirmationTexts.WAIT_LIMIT_MS) {
                var given: ConfirmationDecision? = null
                while (given == null && !slot.expired(env.realtime())) given = withTimeoutOrNull(CARD_POLL_MS) { answer.await() }
                given
            } ?: ConfirmationDecision.NO_ANSWER
            // Only running out of time is NO_ANSWER. Whether it ended the attempt depends on the screen at the moment of expiry, not now.
            if (decision == ConfirmationDecision.NO_ANSWER && !env.screenAttachedAt(slot.startedAt + ConfirmationTexts.WAIT_LIMIT_MS)) unanswered = true
            return decision
        } finally {
            own.clearConfirmation(id)
        }
    }

    /**
     * The question ran out with no chat screen attached: nothing runs and the whole attempt ends as Stop does. The call's "no answer" result
     * is recorded (so the turn reads "No answer", and a later model request sees the usual declined result), then the run is stopped.
     */
    private fun endUnanswered(call: ToolCall) {
        val result = SavedResult.of(ToolResult(call.id, call.name, ConfirmationTexts.NO_ANSWER, ToolOutcome.DECLINED))
        own.guarded(owner, "history") {
            document = document.withLast(archive) { turn ->
                val last = turn.exchanges.lastOrNull() ?: return@withLast turn
                turn.copy(exchanges = turn.exchanges.dropLast(1) + last.copy(results = last.results + result))
            }
            persist()
        }
        env.onUnanswered()
        own.stop(owner)
    }

    private fun publishSource(source: ChatSource) {
        own.guarded(owner, "source") {
            val last = document.records.last()
            document = document.withLast(archive) { it.copy(sources = it.sources + SavedSource(source.title, source.url)) }
            mutable.value = mutable.value.copy(currentSources = webSources(last.sources) + source); persist()
        } ?: throw CancellationException()
    }

    private fun toolProvider(active: AgentProvider, past: List<Pair<SavedTurn, AgentTurn>>) = AgentProvider { request ->
        val retained = own.guarded(owner, "history") { document.records.toList() } ?: throw CancellationException()
        val included = if (env.archive) request.turns.dropLast(1) else request.turns.dropLast(1).filter { turn -> past.any { (record, model) -> model === turn && retained.any { it === record } } }
        val bounded = request.copy(turns = included + request.turns.last())
        if (included.size != request.turns.size - 1) own.guarded(owner, "window") { document = document.withOmitted(); mutable.value = mutable.value.copy(omitted = true) }
        try {
            complete(active, bounded)
        } catch (failure: ProviderException) {
            transportTimeout = isTransportTimeout(failure.kind)
            invalidReply = isInvalidReply(failure.kind)
            throw failure
        }
    }

    private fun summary(active: AgentProvider, prompt: String) = AssistantSummary(a.settings, active,
        read = { own.guarded(owner, "history") { document } ?: throw CancellationException() },
        save = { value -> own.guarded(owner, "summary") {
            document = document.withSummary(value); persist()
            mutable.value = mutable.value.copy(summarized = true, toolProgress = null)
        } ?: throw CancellationException() },
        complete = { request ->
            own.guarded(owner, "progress") { mutable.value = mutable.value.copy(toolProgress = ChatToolProgress.SUMMARY) } ?: throw CancellationException()
            val selected = summaryProvider ?: (a.settings.summaryModel?.let { model -> env.prepare(a.saved.copy(settings = a.settings.copy(model = model))) } ?: active).also { summaryProvider = it }
            complete(selected, request)
        }, prompt)

    private suspend fun prepared(summary: AssistantSummary, request: AgentRequest): AgentRequest =
        if (!env.archive) request else try { summary.prepare(request) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            summaryProblem = setupProblem(failure, ChatProblem.SUMMARY)
            summaryStatus = (failure as? ProviderException)?.status
            throw failure
        }

    private fun onEvent(active: AgentProvider, event: AgentEvent) {
        when (event) {
            is AgentEvent.Replied -> {
                val reply = SavedReply.of(event.reply, active)
                val value = update { it.copy(exchanges = it.exchanges + SavedExchange(reply)) }
                project(value)
                if (value.records.last().chars > ChatLimits.MAX_HISTORY_CHARS) throw HistoryFull()
            }
            is AgentEvent.Calling -> own.guarded(owner, "progress") { mutable.value = mutable.value.copy(toolProgress =
                AssistantToolId.fromWire(event.call.name)?.let { ChatToolProgress.Tool(it) } ?: ChatToolProgress.FAILED) }
            is AgentEvent.FinishedTool -> {
                val received = SavedResult.of(event.result)
                val value = update { record -> val last = record.exchanges.last(); record.copy(exchanges = record.exchanges.dropLast(1) + last.copy(results = last.results + received)) }
                project(value)
                if (value.records.last().chars > ChatLimits.MAX_HISTORY_CHARS) throw HistoryFull()
                own.guarded(owner, "progress") { mutable.value = mutable.value.copy(toolProgress = progressAfter(event.result.outcome)) }
            }
            is AgentEvent.Window -> if (event.omittedTurns > 0 || event.omittedExchanges > 0) own.guarded(owner, "window") {
                document = document.withOmitted(); mutable.value = mutable.value.copy(omitted = true)
            }
        }
    }

    /** The start check precedes every provider call, whatever the provider; the exchange itself runs outside the guard. */
    private suspend fun complete(active: AgentProvider, request: AgentRequest): AgentReply {
        admitSideEffect("dispatch")
        return active.complete(request)
    }

    private fun update(change: (SavedTurn) -> SavedTurn): SavedChat = own.guarded(owner, "history") {
        document = document.withLast(archive, change); persist(); document
    } ?: throw CancellationException()

    /** Kernel callbacks run on IO, so JSON projection never runs under the guard/Main. */
    private fun project(value: SavedChat) {
        val projected = value.views()
        own.guarded(owner, "presentation") { render(value, projected) }
    }

    private fun finish(problem: ChatProblem?, category: String = "finish-error", status: Int? = null) {
        val value = own.guarded(owner, category) {
            document = document.finished(problem, status, archive, clearDraft = !a.fixedText); persist()
            owner.replied = problem == null
            document
        } ?: return
        project(value)
    }

    private fun webSources(values: List<SavedSource>) = values.map { ChatSource(it.title, it.url) }
}
