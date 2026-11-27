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
    @Transient val original: ToolResult? = null,
    /** Why this call ran without a question (memory only: never written, so a conversation restored from the saved history shows none). */
    @Transient val noQuestion: ru.adsrv.frpjump.settings.NoQuestion? = null) {
    @Transient val chars = callId.length.toLong() + name.length + text.length
    /** An outcome name this version does not know (written by a newer one) reads as a failure: not success, not declined. */
    fun value() = original ?: ToolResult(callId, name, text, ToolOutcome.entries.firstOrNull { it.name == outcome } ?: ToolOutcome.FAILED, omittedChars)
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
/** One event of a wait that started or joined a turn, kept with the turn (the chat shows it as a line, the model reads [message]). */
@Serializable internal data class SavedEvent(val kind: String, val tab: Int? = null, val subject: String? = null, val text: String? = null,
    val minutes: Int? = null, val timedOut: Boolean = false) {
    fun value(): WaitEvent? = WaitKind.fromWire(kind)?.let { WaitEvent(it, tab, subject, text, minutes, timedOut) }
    companion object {
        fun of(e: WaitEvent) = SavedEvent(e.kind.wire, e.tabId, e.subject, e.text, e.minutes, e.timedOut)
    }
}
@Serializable internal enum class SavedTurnState { RUNNING, COMPLETE, FAILED, INTERRUPTED }
@Serializable internal data class SavedTurn(val user: String, val exchanges: List<SavedExchange> = emptyList(),
    val sources: List<SavedSource> = emptyList(), val status: SavedTurnState = SavedTurnState.RUNNING, val problem: String? = null, val problemStatus: Int? = null,
    /**
     * Events of waits delivered in this turn: with [eventOnly] the turn was started by them ([user] is then their messages, app-generated, never the
     * owner's words); else they came with the owner's message and the model reads them after it. Not written while empty.
     */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val events: List<SavedEvent> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val eventOnly: Boolean = false,
    /** Notes for the model only (the owner cancelled a wait): read after the turn's text, not shown. Not written while empty. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val notes: List<String> = emptyList()) {
    @Transient val chars = user.length.toLong() + events.sumOf { 64L + (it.subject?.length ?: 0) + (it.text?.length ?: 0) } + notes.sumOf { it.length.toLong() } + exchanges.sumOf { it.chars } + sources.sumOf { it.title.length.toLong() + it.url.length }
    fun model(provider: AgentProvider): AgentTurn {
        val blocks = exchanges.map { exchange ->
            if (exchange.reply.calls.size == exchange.results.size) AgentExchange(exchange.reply.value(provider), exchange.results.map { it.value() })
            else AgentExchange(AgentReply("Unfinished historical proposal; missing results are UNKNOWN, not success. Do not replay it.\n" +
                exchange.reply.calls.joinToString("\n") { "${it.name} ${it.arguments}" } + "\n" +
                exchange.results.joinToString("\n") { "${it.name}: ${it.outcome}: ${it.text}" }))
        }
        // Events that came with the owner's message, and notes, follow it as their own lines (the turn started by events already has them in [user]).
        val extra = (if (eventOnly) emptyList() else events.mapNotNull { it.value()?.message() }) + notes
        return AgentTurn(withApp(user, extra), blocks + if (status == SavedTurnState.COMPLETE) emptyList() else listOf(
            AgentExchange(AgentReply("Previous attempt: $status${problem?.let { "; $it" }.orEmpty()}. Preserve received facts; do not blindly repeat actions with unknown outcomes."))))
    }
    /** Projection only; no private continuation is exposed and parsing stays on IO. */
    fun view(): ChatTurn {
        val receipts = exchanges.flatMap { exchange -> exchange.reply.calls.map { call ->
            val saved = exchange.results.firstOrNull { it.callId == call.id }
            ChatToolReceipt(call.value(), saved?.value(), saved?.noQuestion)
        } }
        val failures = receipts.mapNotNull { receipt -> receipt.result?.takeIf { it.outcome != ToolOutcome.SUCCESS && it.outcome != ToolOutcome.DECLINED }?.let { result ->
            ChatToolFailure(result, if (receipt.call.name == AssistantToolId.WEB_SEARCH.wire) try { StrictArgs(receipt.call.arguments).optionalString("query", 256, allowBlank = true) } catch (_: InvalidArguments) { null } else null)
        } }
        val information = receipts.mapNotNull { receipt -> receipt.result?.takeIf { it.name == AssistantToolId.PROFILE_INFORMATION.wire && it.outcome == ToolOutcome.SUCCESS }?.let {
            ChatProfileInformation(it, runCatching { Json.parseToJsonElement(it.text) as? JsonObject }.getOrNull())
        } }
        val webTools = setOf(AssistantToolId.WEB_SEARCH.wire, AssistantToolId.READ_PAGE.wire, AssistantToolId.INSPECT_IMAGE.wire)
        // A first ask is told by its fixed result text, so a repeat ask, a declined one or an invalid call never counts.
        val asked = if (status != SavedTurnState.COMPLETE) emptyList() else receipts.mapNotNull { receipt ->
            val result = receipt.result ?: return@mapNotNull null
            if (receipt.call.name != AssistantRequestTool.WIRE || result.outcome != ToolOutcome.SUCCESS) return@mapNotNull null
            val name = try { StrictArgs(receipt.call.arguments).string("name") } catch (_: InvalidArguments) { return@mapNotNull null }
            name.takeIf { AssistantToolId.fromWire(it) != null && result.text == AssistantRequestTool.askedText(it) }
        }.distinct()
        return ChatTurn(user, if (status == SavedTurnState.COMPLETE) exchanges.lastOrNull()?.reply?.takeIf { it.calls.isEmpty() }?.text.orEmpty() else "",
            sources.map { ChatSource(it.title, it.url) }, failures.isNotEmpty(), failures, information,
            interrupted = status != SavedTurnState.COMPLETE, receipts = receipts,
            unanswered = status == SavedTurnState.INTERRUPTED && exchanges.lastOrNull()?.results?.lastOrNull()
                ?.let { it.outcome == ToolOutcome.DECLINED.name && it.text == ru.adsrv.frpjump.settings.ConfirmationTexts.NO_ANSWER } == true,
            askedTools = asked, readWeb = receipts.any { it.call.name in webTools },
            events = events.mapNotNull { it.value() }, eventOnly = eventOnly)
    }
    companion object {
        fun of(turn: AgentTurn, provider: AgentProvider? = null) = SavedTurn(turn.user, turn.exchanges.map {
            SavedExchange(SavedReply.of(it.reply, provider), it.results.map(SavedResult::of))
        }, status = SavedTurnState.COMPLETE)
    }
}

/** The working summary and the position it covers up to. [byModel] false: the app's own digest, written without a model (not written while true, so older chats are unchanged). */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable internal data class SavedSummary(val text: String, val turn: Int, val exchange: Int,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val byModel: Boolean = true)

/**
 * The longest summary text that can be saved: a summary is at most its share of the context window in UTF-8 bytes, so at most that many characters;
 * the bound is the biggest window the agent accepts times the biggest summary size the Limits screen allows.
 */
internal const val MAX_SUMMARY_CHARS = AgentLimits.MAX_CONTEXT_TOKENS * ru.adsrv.frpjump.settings.WindowSplit.SUMMARY_MAX / 100

/** What the saved chat's summary is, for the chat's calm line. */
internal fun SavedChat.compaction(): Compaction? = summary?.let { if (it.byModel) Compaction.MODEL else Compaction.NO_MODEL }

/** The sole saved representation; UI and request windows are derived from these records. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable internal data class SavedChat(val version: Int = 1, val binding: ChatBinding? = null, val records: List<SavedTurn> = emptyList(),
    val draft: String = "", val omitted: Boolean = false, val contextStart: Int = 0, val summary: SavedSummary? = null,
    /**
     * Wire names of the tools the owner declined with "No thanks" in this conversation (Р56). Kept with the history, so it survives a
     * restart and goes with a clear. Not written while empty, so a chat without one is byte-identical to an older saved chat.
     */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val declinedTools: List<String> = emptyList()) {
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
        summary?.let { require(it.text.isNotBlank() && it.text.length <= MAX_SUMMARY_CHARS && it.turn in contextStart..records.lastIndex && it.exchange in 0..records[it.turn].exchanges.size) }
        return copy(records = records.map { if (it.status == SavedTurnState.RUNNING) it.copy(status = SavedTurnState.INTERRUPTED) else it },
            declinedTools = declinedTools.filter { AssistantToolId.fromWire(it) != null }.distinct())
    }
}
