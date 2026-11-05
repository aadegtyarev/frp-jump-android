package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.settings.AssistantAvailability

/** UI values deliberately exclude opaque provider state. Nothing here is saved in a Bundle or file. */
data class ChatTurn(val user: String, val answer: String)
enum class ChatProblem { SETUP, READ, KEY, TIMEOUT, CONTEXT_FULL, INVALID_REPLY, PROVIDER, TOO_LONG }
data class ChatState(
    val turns: List<ChatTurn> = emptyList(), val draft: String = "", val pendingText: String? = null,
    val busy: Boolean = false, val stopping: Boolean = false, val omitted: Boolean = false,
    val problem: ChatProblem? = null,
)

/** One route's memory-only owner. [scope] survives recreation, not a popped back-stack entry.
 * Blocking credential work runs on [io]; the pending slot includes its entire exit.
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
) {
    private val gate = Any()
    private val mutable = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = mutable
    private var authority = availability()
    private var revision = snapshot().revision
    private var provider: AgentProvider? = null
    private var history = emptyList<AgentTurn>()
    private var closed = false
    private class Pending(val revision: String?, val version: Long) {
        lateinit var job: Job
        var stopped = false
    }
    private var pending: Pending? = null

    fun edit(text: String) = synchronized(gate) {
        if (!closed && pending == null) {
            if (text.length > MAX_INPUT) mutable.value = mutable.value.copy(problem = ChatProblem.TOO_LONG)
            else mutable.value = mutable.value.copy(draft = text, problem = null)
        }
    }

    /** Called by observers AND synchronously at admission/publication: conflation cannot revive work. */
    fun refresh() = synchronized(gate) { refreshLocked() }
    private fun refreshLocked() {
        if (closed) return
        val now = availability()
        val saved = snapshot()
        if (now.disableVersion != authority.disableVersion || !now.enabled || saved.revision != revision || saved.problem != null) {
            history = emptyList(); provider = null
            mutable.value = mutable.value.copy(turns = emptyList(), omitted = false, problem = null)
            pending?.let { stopLocked(it) }
        }
        authority = now; revision = saved.revision
    }
    private fun stopLocked(owner: Pending) {
        owner.stopped = true
        mutable.value = mutable.value.copy(stopping = true)
        owner.job.cancel()
    }
    fun stop() = synchronized(gate) { if (!closed) pending?.let(::stopLocked) }
    fun close() = synchronized(gate) {
        closed = true
        pending?.let(::stopLocked)
        history = emptyList(); provider = null
        mutable.value = ChatState()
    }
    private fun owns(owner: Pending): Boolean {
        refreshLocked()
        return !closed && pending === owner && !owner.stopped && availability().enabled &&
            availability().disableVersion == owner.version && snapshot().revision == owner.revision && snapshot().problem == null
    }

    fun send() = synchronized(gate) {
        refreshLocked()
        if (closed || pending != null) return@synchronized
        val saved = snapshot()
        val settings = saved.settings
        val text = mutable.value.draft
        if (!availability().enabled || settings == null || settings.model.isBlank() || saved.problem != null) {
            mutable.value = mutable.value.copy(problem = if (saved.problem != null) ChatProblem.READ else ChatProblem.SETUP)
            return@synchronized
        }
        if (text.length > MAX_INPUT) { mutable.value = mutable.value.copy(problem = ChatProblem.TOO_LONG); return@synchronized }
        if (text.isBlank()) return@synchronized
        val owner = Pending(saved.revision, availability().disableVersion)
        val past = history
        val retainedProvider = provider
        pending = owner
        mutable.value = mutable.value.copy(busy = true, stopping = false, pendingText = text, problem = null)
        owner.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val activeProvider = withContext(io) {
                    requireRevision(saved.revision)
                    retainedProvider ?: prepare(saved)
                }
                synchronized(gate) { if (!owns(owner)) throw CancellationException(); provider = activeProvider }
                // Reject tool proposals before the kernel can start a tool-driven second model call.
                var invalidReply = false
                var transportTimeout = false
                val textOnly = AgentProvider { request ->
                    try {
                        activeProvider.complete(request).also {
                            if (it.calls.isNotEmpty()) { invalidReply = true; throw IllegalStateException() }
                        }
                    } catch (failure: ru.adsrv.frpjump.agent.openai.OpenAiProviderException) {
                        transportTimeout = failure.failure == ru.adsrv.frpjump.agent.openai.ProviderFailure.TIMEOUT
                        invalidReply = failure.failure in setOf(
                            ru.adsrv.frpjump.agent.openai.ProviderFailure.INVALID_RESPONSE,
                            ru.adsrv.frpjump.agent.openai.ProviderFailure.INCOMPLETE_RESPONSE,
                            ru.adsrv.frpjump.agent.openai.ProviderFailure.RESPONSE_TOO_LARGE,
                        )
                        throw failure
                    }
                }
                var omitted = false
                val result = module.run(textOnly, emptyList(), INSTRUCTIONS, text, past, limits(settings), onEvent = {
                    if (it is AgentEvent.Window && it.omittedTurns > 0) {
                        omitted = true
                        synchronized(gate) {
                            if (owns(owner)) mutable.value = mutable.value.copy(omitted = true)
                        }
                    }
                })
                withContext(io) { requireRevision(saved.revision) }
                synchronized(gate) {
                    if (owns(owner)) {
                        if (result.finish == AgentFinish.COMPLETE) {
                            history = (history + result.turn).takeLast(MAX_TURNS)
                            mutable.value = mutable.value.copy(
                                turns = history.map { ChatTurn(it.user, it.exchanges.last().reply.text) }, draft = "",
                                omitted = mutable.value.omitted || omitted || past.size == MAX_TURNS,
                            )
                        } else mutable.value = mutable.value.copy(problem = when (result.finish) {
                            AgentFinish.MODEL_TIMEOUT -> ChatProblem.TIMEOUT
                            AgentFinish.CONTEXT_FULL -> ChatProblem.CONTEXT_FULL
                            AgentFinish.INVALID_REPLY, AgentFinish.STEP_LIMIT -> ChatProblem.INVALID_REPLY
                            else -> when {
                                transportTimeout -> ChatProblem.TIMEOUT
                                invalidReply -> ChatProblem.INVALID_REPLY
                                else -> ChatProblem.PROVIDER
                            }
                        })
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                synchronized(gate) { if (owns(owner)) mutable.value = mutable.value.copy(problem = when ((failure as? ProviderSetupException)?.problem) {
                    ProviderSetupProblem.CRYPTO_FAILED, ProviderSetupProblem.ADDRESS_CHANGED, ProviderSetupProblem.INVALID_KEY -> ChatProblem.KEY
                    ProviderSetupProblem.READ_FAILED -> ChatProblem.READ
                    else -> ChatProblem.SETUP
                }) }
            }
        }
        // A lazy job cancelled before its first dispatch never enters the body's finally.
        owner.job.invokeOnCompletion {
            synchronized(gate) {
                if (pending === owner) {
                    pending = null
                    if (!closed) mutable.value = mutable.value.copy(busy = false, stopping = false, pendingText = null)
                }
            }
        }
        owner.job.start()
    }

    companion object {
        const val MAX_INPUT = 8_000
        const val MAX_TURNS = 32
        const val INSTRUCTIONS = "You are a text-only assistant. Answer the user's message. No tools or app data are available."
    }
}
