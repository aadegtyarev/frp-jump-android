package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.*
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.profiles.ProfileInformation

/** Conversation-lived dependencies of every Send. */
internal class TurnEnv(val own: RunOwnership, val runtime: AssistantChatRuntime, val module: AgentModule, val io: CoroutineDispatcher,
    val limits: (AssistantProviderSettings) -> AgentLimits, val prepare: (AssistantProviderSnapshot) -> AgentProvider,
    val requireRevision: (String?) -> Unit, val profileInformation: ProfileInformation?, val sshCommands: AssistantSshCommands?,
    val archive: Boolean, val prompts: Lazy<AssistantPrompts>)

private class HistoryFull : Exception()

/**
 * One admitted Send: provider, tools, summary, kernel run, events into the document and the final problem. All commits go
 * through [RunOwnership.guarded]; nothing here takes the gate directly or suspends inside it. The flags below are written
 * and read on the run's IO threads exactly as the former captured locals were.
 */
internal class TurnRunner(private val env: TurnEnv, private val a: Admission) {
    private val owner = a.owner
    private val own = env.own
    private var transportTimeout = false
    private var invalidReply = false
    private var summaryProblem: ChatProblem? = null
    private var summaryStatus: Int? = null
    private var summaryProvider: AgentProvider? = null

    suspend fun run() {
        try { withTimeout(120_000) { exchange() } }
        catch (_: HistoryFull) { withContext(NonCancellable + env.io) { finish(ChatProblem.CONTEXT_FULL) } }
        catch (_: TimeoutCancellationException) { withContext(NonCancellable + env.io) { finish(ChatProblem.TIMEOUT) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { withContext(NonCancellable + env.io) { finish(setupProblem(failure, ChatProblem.SETUP), "setup-error") } }
    }

    private suspend fun exchange() {
        // First use of the lazy prompts reads assets: off Main, on the Send's IO path; a failure is an ordinary setup problem.
        val (active, prompts) = withContext(env.io) { env.requireRevision(a.saved.revision); (a.retained ?: env.prepare(a.saved)) to env.prompts.value }
        own.adopt(owner, active)
        withContext(env.io + SideEffectAdmission { category -> own.guarded(owner, category) { true } ?: false }) {
            val past = a.past.map { it to it.model(active) }
            val tools = AssistantToolFactory.create(a.tools, env.runtime, active, env.profileInformation, env.sshCommands, ::publishSource)
            val summary = summary(active, prompts.summary)
            val result = env.module.run(toolProvider(active, past), tools, prompts.system, a.text, past.map { it.second },
                env.limits(a.settings), prepareRequest = { request -> prepared(summary, request) }, onEvent = { event -> onEvent(active, event) })
            env.requireRevision(a.saved.revision)
            val problem = finishProblem(summaryProblem, result.finish, transportTimeout, invalidReply)
            finish(problem, if (problem == null) "answer" else "finish-error", summaryStatus)
        }
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
                own.guarded(owner, "progress") { mutable.value = mutable.value.copy(toolProgress = if (event.result.outcome == ToolOutcome.SUCCESS) null else ChatToolProgress.FAILED) }
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
            document = document.finished(problem, status, archive); persist(); document
        } ?: return
        project(value)
    }

    private fun webSources(values: List<SavedSource>) = values.map { ChatSource(it.title, it.url) }
}
