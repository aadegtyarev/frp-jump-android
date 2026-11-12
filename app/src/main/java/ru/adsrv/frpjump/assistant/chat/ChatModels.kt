package ru.adsrv.frpjump.assistant.chat

import kotlinx.serialization.json.*
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.ChatHistoryProblem
import ru.adsrv.frpjump.assistant.ChatSource

/** Display-only projections; opaque provider continuation never enters these values. */
data class ChatToolFailure(val result: ToolResult, val query: String? = null)
data class ChatProfileInformation(val result: ToolResult, val page: JsonObject?) {
    val presentationChars: Int get() = if (page == null) 0 else result.text.length
}
data class ChatToolReceipt(val call: ToolCall, val result: ToolResult? = null)
data class ChatTurn(val user: String, val answer: String, val sources: List<ChatSource> = emptyList(), val toolFailed: Boolean = false,
    val toolFailures: List<ChatToolFailure> = emptyList(), val information: List<ChatProfileInformation> = emptyList(),
    val interrupted: Boolean = false, val receipts: List<ChatToolReceipt> = emptyList())
sealed interface ChatToolProgress {
    /** A call of a known tool is running. */
    data class Tool(val id: AssistantToolId) : ChatToolProgress
    data object SUMMARY : ChatToolProgress
    data object FAILED : ChatToolProgress
}
/** The progress line after a tool finished: nothing for success and for a decline (not a failure), the failed marker otherwise. */
internal fun progressAfter(outcome: ToolOutcome): ChatToolProgress? =
    if (outcome == ToolOutcome.SUCCESS || outcome == ToolOutcome.DECLINED) null else ChatToolProgress.FAILED
enum class ChatProblem { SETUP, READ, KEY, TIMEOUT, CONTEXT_FULL, INVALID_REPLY, PROVIDER, TOO_LONG, INTERRUPTED, SUMMARY, HISTORY_FULL }
data class ChatState(
    val turns: List<ChatTurn> = emptyList(), val draft: String = "", val pendingText: String? = null,
    val busy: Boolean = false, val stopping: Boolean = false, val omitted: Boolean = false,
    val problem: ChatProblem? = null, val toolProgress: ChatToolProgress? = null,
    val loading: Boolean = false, val historyProblem: ChatHistoryProblem? = null, val contextChanged: Boolean = false,
    val summarized: Boolean = false, val problemStatus: Int? = null,
    val receipts: List<ChatToolReceipt> = emptyList(), val currentSources: List<ChatSource> = emptyList(), val toolFailed: Boolean = false,
    val toolFailures: List<ChatToolFailure> = emptyList(), val information: List<ChatProfileInformation> = emptyList(),
)

/** Chat limits shared by the saved document and the conversation; `AssistantConversation` re-exports them. */
object ChatLimits {
    const val MAX_INPUT = 8_000
    const val MAX_TURNS = 32
    const val MAX_HISTORY_CHARS = 512_000
}
