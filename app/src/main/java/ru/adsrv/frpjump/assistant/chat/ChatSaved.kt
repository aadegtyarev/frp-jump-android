package ru.adsrv.frpjump.assistant.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.*
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.assistant.tools.*

@Serializable internal data class ChatBinding(val root: String, val model: String)
@Serializable internal data class SavedCall(val id: String, val name: String, val arguments: JsonObject) {
    @Transient val chars = id.length.toLong() + name.length + arguments.toString().length
    fun value() = ToolCall(id, name, arguments)
}
@Serializable internal data class SavedResult(val callId: String, val name: String, val text: String, val outcome: String, val omittedChars: Int = 0,
    @Transient val original: ToolResult? = null) {
    @Transient val chars = callId.length.toLong() + name.length + text.length
    fun value() = original ?: ToolResult(callId, name, text, ToolOutcome.valueOf(outcome), omittedChars)
    companion object { fun of(value: ToolResult) = SavedResult(value.callId, value.name, value.text, value.outcome.name, value.omittedChars, value) }
}
@Serializable internal data class SavedReply(val text: String = "", val calls: List<SavedCall> = emptyList(), val continuation: String? = null,
    @Transient val localContinuation: AgentContinuation? = null) {
    @Transient val chars = text.length.toLong() + calls.sumOf { it.chars } + (continuation?.length ?: localContinuation?.sizeChars ?: 0)
    fun value(provider: AgentProvider) = AgentReply(text, calls.map { it.value() },
        continuation?.let { checkNotNull(provider as? PersistentContinuations).restoreContinuation(it) } ?: localContinuation)
    companion object {
        fun of(reply: AgentReply, provider: AgentProvider?) = SavedReply(reply.text,
            reply.calls.map { SavedCall(it.id, it.name, it.arguments) },
            reply.continuation?.let { (provider as? PersistentContinuations)?.saveContinuation(it) },
            reply.continuation.takeIf { provider !is PersistentContinuations })
    }
}
@Serializable internal data class SavedExchange(val reply: SavedReply, val results: List<SavedResult> = emptyList()) {
    @Transient val chars = reply.chars + results.sumOf { it.chars }
}
@Serializable internal data class SavedSource(val title: String, val url: String)
@Serializable internal enum class SavedTurnState { RUNNING, COMPLETE, FAILED, INTERRUPTED }
@Serializable internal data class SavedTurn(val user: String, val exchanges: List<SavedExchange> = emptyList(),
    val sources: List<SavedSource> = emptyList(), val status: SavedTurnState = SavedTurnState.RUNNING, val problem: String? = null, val problemStatus: Int? = null) {
    @Transient val chars = user.length.toLong() + exchanges.sumOf { it.chars } + sources.sumOf { it.title.length.toLong() + it.url.length }
    fun model(provider: AgentProvider): AgentTurn {
        val blocks = exchanges.map { exchange ->
            if (exchange.reply.calls.size == exchange.results.size) AgentExchange(exchange.reply.value(provider), exchange.results.map { it.value() })
            else AgentExchange(AgentReply("Unfinished historical proposal; missing results are UNKNOWN, not success. Do not replay it.\n" +
                exchange.reply.calls.joinToString("\n") { "${it.name} ${it.arguments}" } + "\n" +
                exchange.results.joinToString("\n") { "${it.name}: ${it.outcome}: ${it.text}" }))
        }
        return AgentTurn(user, blocks + if (status == SavedTurnState.COMPLETE) emptyList() else listOf(
            AgentExchange(AgentReply("Previous attempt: $status${problem?.let { "; $it" }.orEmpty()}. Preserve received facts; do not blindly repeat actions with unknown outcomes."))))
    }
    /** Projection only; no private continuation is exposed and parsing stays on IO. */
    fun view(): ChatTurn {
        val receipts = exchanges.flatMap { exchange -> exchange.reply.calls.map { call ->
            ChatToolReceipt(call.value(), exchange.results.firstOrNull { it.callId == call.id }?.value())
        } }
        val failures = receipts.mapNotNull { receipt -> receipt.result?.takeIf { it.outcome != ToolOutcome.SUCCESS && it.outcome != ToolOutcome.DECLINED }?.let { result ->
            ChatToolFailure(result, if (receipt.call.name == AssistantToolId.WEB_SEARCH.wire) try { StrictArgs(receipt.call.arguments).optionalString("query", 256, allowBlank = true) } catch (_: InvalidArguments) { null } else null)
        } }
        val information = receipts.mapNotNull { receipt -> receipt.result?.takeIf { it.name == AssistantToolId.PROFILE_INFORMATION.wire && it.outcome == ToolOutcome.SUCCESS }?.let {
            ChatProfileInformation(it, runCatching { Json.parseToJsonElement(it.text) as? JsonObject }.getOrNull())
        } }
        return ChatTurn(user, if (status == SavedTurnState.COMPLETE) exchanges.lastOrNull()?.reply?.text.orEmpty() else "",
            sources.map { ChatSource(it.title, it.url) }, failures.isNotEmpty(), failures, information,
            interrupted = status != SavedTurnState.COMPLETE, receipts = receipts)
    }
    companion object {
        fun of(turn: AgentTurn, provider: AgentProvider? = null) = SavedTurn(turn.user, turn.exchanges.map {
            SavedExchange(SavedReply.of(it.reply, provider), it.results.map(SavedResult::of))
        }, status = SavedTurnState.COMPLETE)
    }
}

@Serializable internal data class SavedSummary(val text: String, val turn: Int, val exchange: Int)

/** The sole saved representation; UI and request windows are derived from these records. */
@Serializable internal data class SavedChat(val version: Int = 1, val binding: ChatBinding? = null, val records: List<SavedTurn> = emptyList(),
    val draft: String = "", val omitted: Boolean = false, val contextStart: Int = 0, val summary: SavedSummary? = null) {
    fun bind(settings: AssistantProviderSettings?): SavedChat {
        if (settings == null) return this
        val next = ChatBinding(settings.baseUrl, settings.model)
        return if (binding == next) this else copy(binding = next, contextStart = if (binding == null) contextStart else records.size, summary = null)
    }
    fun bounded(archive: Boolean = false): SavedChat {
        if (archive) return this
        var drop = 0; var size = records.sumOf { it.chars }
        while (records.size - drop > 1 && (records.size - drop > ChatLimits.MAX_TURNS || size > ChatLimits.MAX_HISTORY_CHARS)) {
            size -= records[drop++].chars
        }
        return if (drop == 0) this else copy(records = records.drop(drop), omitted = true, contextStart = (contextStart - drop).coerceAtLeast(0))
    }
    fun restored(): SavedChat {
        require(version == 1 && draft.length <= ChatLimits.MAX_INPUT && contextStart in 0..records.size)
        require(records.size <= 1000)
        summary?.let { require(it.text.isNotBlank() && it.text.length <= 4000 && it.turn in contextStart..records.lastIndex && it.exchange in 0..records[it.turn].exchanges.size) }
        return copy(records = records.map { if (it.status == SavedTurnState.RUNNING) it.copy(status = SavedTurnState.INTERRUPTED) else it })
    }
}
