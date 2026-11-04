package ru.adsrv.frpjump.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Single-flight, opt-in module. Disabling invalidates the run BEFORE cancelling its child job. */
class AgentModule(
    initiallyEnabled: Boolean = false,
    private val disableVersion: () -> Long = { 0 },
    private val allowedNow: () -> Boolean = { true },
) {
    private class Active(val job: Job, val disableVersion: Long) { @Volatile var stopped: Boolean = false }
    private val gate = Any()
    @Volatile private var enabled = initiallyEnabled
    private var active: Active? = null
    val isEnabled: Boolean get() = enabled && allowedNow()
    val isRunning: Boolean get() = synchronized(gate) { active != null }

    fun setEnabled(value: Boolean) {
        val stopped = synchronized(gate) {
            enabled = value
            if (value) null else active?.also { it.stopped = true }
        }
        stopped?.job?.cancel(CancellationException("Assistant disabled"))
    }

    /** Cancel stale work after an app commit notification, even if off/on was conflated.
     * [disableVersion] must advance on every committed disable and never move backwards.
     * Ownership checks use it directly too, without waiting for this asynchronous notification.
     */
    fun refreshAvailability() {
        val stopped = synchronized(gate) {
            active?.takeIf { it.disableVersion != disableVersion() || !isEnabled }?.also { it.stopped = true }
        }
        stopped?.job?.cancel(CancellationException("Assistant disabled"))
    }

    fun stop() {
        val stopped = synchronized(gate) { active?.also { it.stopped = true } }
        stopped?.job?.cancel(CancellationException("Assistant stopped"))
    }

    /** No implicit retries, transport discovery or permission grants. Cancellation propagates to the caller. */
    suspend fun run(
        provider: AgentProvider,
        tools: List<AgentTool>,
        instructions: String,
        user: String,
        history: List<AgentTurn> = emptyList(),
        limits: AgentLimits = AgentLimits(),
        counter: TokenCounter = Utf8TokenEstimate,
        onEvent: (AgentEvent) -> Unit = {},
    ): AgentRunResult = coroutineScope {
        val initial = AgentTurn(user)
        val mine = Active(currentCoroutineContext()[Job]!!, disableVersion())
        val rejection = synchronized(gate) {
            when {
                !isEnabled -> AgentFinish.DISABLED
                active != null -> AgentFinish.BUSY
                else -> { active = mine; null }
            }
        }
        if (rejection != null) return@coroutineScope AgentRunResult(initial, rejection)
        suspend fun checkOwner() {
            currentCoroutineContext().ensureActive()
            if (mine.stopped || mine.disableVersion != disableVersion() || !isEnabled) throw CancellationException("Assistant run no longer active")
        }
        try {
            val registry = tools.toList().associateBy { it.spec.name }
            require(registry.size == tools.size) { "Duplicate agent tool names" }
            val specs = registry.values.map { it.spec }
            val past = history.toList()
            val exchanges = ArrayList<AgentExchange>()
            val usedCalls = HashSet<String>()
            fun finish(reason: AgentFinish) = AgentRunResult(AgentTurn(user, exchanges.toList()), reason)
            repeat(limits.maxSteps) {
                checkOwner()
                val window = selectAgentWindow(instructions, specs, past, AgentTurn(user, exchanges.toList()), limits, counter)
                val request = window.request ?: return@coroutineScope finish(AgentFinish.CONTEXT_FULL)
                onEvent(AgentEvent.Window(window.omittedTurns, window.omittedExchanges, window.estimatedTokens))
                checkOwner()
                val received = try {
                    withTimeout(limits.modelTimeoutMs) { provider.complete(request) }
                } catch (timeout: TimeoutCancellationException) {
                    checkOwner()
                    return@coroutineScope finish(AgentFinish.MODEL_TIMEOUT)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    checkOwner()
                    return@coroutineScope finish(AgentFinish.PROVIDER_FAILED)
                }
                checkOwner()
                if (received.calls.size > limits.maxCallsPerStep) return@coroutineScope finish(AgentFinish.INVALID_REPLY)
                val reply = received.copy(calls = received.calls.toList())
                if (reply.continuation?.let { it.sizeChars < 0 || it.estimatedTokens !in 0..1_000_000 } == true) return@coroutineScope finish(AgentFinish.INVALID_REPLY)
                val size = reply.text.length.toLong() + (reply.continuation?.sizeChars ?: 0) + reply.calls.sumOf { it.id.length.toLong() + it.name.length + it.arguments.toString().length }
                if (size > limits.maxReplyChars ||
                    reply.calls.any { it.id.isBlank() || it.name.isBlank() || it.id in usedCalls } ||
                    reply.calls.map { it.id }.distinct().size != reply.calls.size ||
                    (reply.calls.isEmpty() && reply.text.isBlank())) return@coroutineScope finish(AgentFinish.INVALID_REPLY)
                usedCalls += reply.calls.map { it.id }
                onEvent(AgentEvent.Replied(reply))
                checkOwner()
                if (reply.calls.isEmpty()) {
                    exchanges += AgentExchange(reply)
                    return@coroutineScope finish(AgentFinish.COMPLETE)
                }
                val results = ArrayList<ToolResult>()
                for (call in reply.calls) {
                    checkOwner()
                    onEvent(AgentEvent.Calling(call))
                    checkOwner()
                    val tool = registry[call.name]
                    val output = if (tool == null) ToolOutput("Unknown tool", ToolOutcome.UNKNOWN_TOOL) else try {
                        withTimeout(limits.toolTimeoutMs) { tool.execute(call.arguments) }
                    } catch (timeout: TimeoutCancellationException) {
                        checkOwner()
                        ToolOutput("Tool timed out; remote outcome may be unknown. Do not automatically repeat.", ToolOutcome.TIMEOUT)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        checkOwner()
                        ToolOutput("Tool failed: ${failure.javaClass.simpleName}; do not assume it had no side effects.", ToolOutcome.FAILED)
                    }
                    checkOwner()
                    val result = toolResult(call, output, limits.maxToolChars)
                    results += result
                    onEvent(AgentEvent.FinishedTool(result))
                }
                exchanges += AgentExchange(reply, results.toList())
            }
            finish(AgentFinish.STEP_LIMIT)
        } finally {
            synchronized(gate) { if (active === mine) active = null }
        }
    }
}
