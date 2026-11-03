package ru.adsrv.frpjump.agent

import kotlinx.serialization.json.JsonObject

/** Provider-neutral values. No Android, credentials, HTTP, SSH or connection discovery here. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)
data class ToolCall(val id: String, val name: String, val arguments: JsonObject)
/** Opaque, provider-owned continuation state. Not UI text; implementations must redact toString(). */
interface AgentContinuation { val estimatedTokens: Long; val sizeChars: Int }
data class AgentReply(val text: String = "", val calls: List<ToolCall> = emptyList(), val continuation: AgentContinuation? = null)
enum class ToolOutcome { SUCCESS, FAILED, TIMEOUT, UNKNOWN_TOOL }
data class ToolOutput(val text: String, val outcome: ToolOutcome = ToolOutcome.SUCCESS)
data class ToolResult(
    val callId: String,
    val name: String,
    val text: String,
    val outcome: ToolOutcome,
    /** Explicit UTF-16 character count, not bytes or tokens. */
    val omittedChars: Int = 0,
)

/** An indivisible protocol block: never keep a tool call without all of its matching results. */
data class AgentExchange(val reply: AgentReply, val results: List<ToolResult> = emptyList()) {
    init {
        require(reply.calls.map { it.id }.distinct().size == reply.calls.size) { "Duplicate tool call IDs" }
        require(reply.calls.size == results.size) { "Incomplete tool exchange" }
        require(reply.calls.zip(results).all { (call, result) -> call.id == result.callId && call.name == result.name }) { "Mismatched tool results" }
    }
}
data class AgentTurn(val user: String, val exchanges: List<AgentExchange> = emptyList())
data class AgentRequest(val instructions: String, val tools: List<ToolSpec>, val turns: List<AgentTurn>, val maxOutputTokens: Int)
fun interface AgentProvider { suspend fun complete(request: AgentRequest): AgentReply }
interface AgentTool {
    val spec: ToolSpec
    /** Adapters must bound capture while reading streams; clipping a returned String is not a heap limit. */
    suspend fun execute(arguments: JsonObject): ToolOutput
}

/** Provider adapters may replace the estimate with an appropriate tokenizer/accounting scheme. */
fun interface TokenCounter { fun count(request: AgentRequest): Long }
object Utf8TokenEstimate : TokenCounter {
    override fun count(request: AgentRequest): Long {
        fun size(text: String) = text.toByteArray(Charsets.UTF_8).size.toLong()
        var total = 256L + size(request.instructions)
        request.tools.forEach { total += 128 + size(it.name) + size(it.description) + size(it.parameters.toString()) }
        request.turns.forEach { turn ->
            total += 64 + size(turn.user)
            turn.exchanges.forEach { exchange ->
                val continuationCost = exchange.reply.continuation?.estimatedTokens ?: 0L
                require(continuationCost in 0..1_000_000) { "Invalid continuation budget" }
                total += 64 + size(exchange.reply.text) + continuationCost
                exchange.reply.calls.forEach { total += 64 + size(it.id) + size(it.name) + size(it.arguments.toString()) }
                exchange.results.forEach { total += 96 + size(it.callId) + size(it.name) + size(it.text) }
            }
        }
        return total
    }
}

data class AgentLimits(
    val contextTokens: Int = 16_384,
    val outputReserveTokens: Int = 2_048,
    val maxSteps: Int = 12,
    val maxCallsPerStep: Int = 4,
    val maxToolChars: Int = 8_000,
    val maxReplyChars: Int = 32_000,
    val modelTimeoutMs: Long = 60_000,
    val toolTimeoutMs: Long = 30_000,
) {
    init {
        require(contextTokens in 512..1_000_000 && outputReserveTokens in 1 until contextTokens)
        require(maxSteps in 1..100 && maxCallsPerStep in 1..16)
        require(maxToolChars in 32..1_000_000 && maxReplyChars in 32..1_000_000)
        require(modelTimeoutMs in 1..600_000 && toolTimeoutMs in 1..600_000)
    }
}

enum class AgentFinish { COMPLETE, DISABLED, BUSY, CONTEXT_FULL, STEP_LIMIT, MODEL_TIMEOUT, PROVIDER_FAILED, INVALID_REPLY }
data class AgentRunResult(val turn: AgentTurn, val finish: AgentFinish)
sealed interface AgentEvent {
    data class Window(val omittedTurns: Int, val omittedExchanges: Int, val estimatedTokens: Long) : AgentEvent
    data class Calling(val call: ToolCall) : AgentEvent
    data class FinishedTool(val result: ToolResult) : AgentEvent
    data class Replied(val reply: AgentReply) : AgentEvent
}
