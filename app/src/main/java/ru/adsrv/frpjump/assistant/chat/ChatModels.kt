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
    val interrupted: Boolean = false, val receipts: List<ChatToolReceipt> = emptyList(),
    /** The attempt ended because a question went unanswered while no chat screen was attached (Р43): its own note, not the interrupted one. */
    val unanswered: Boolean = false)
sealed interface ChatToolProgress {
    /** A call of a known tool is running. */
    data class Tool(val id: AssistantToolId) : ChatToolProgress
    data object SUMMARY : ChatToolProgress
    data object FAILED : ChatToolProgress
}
/** The progress line after a tool finished: nothing for success and for a decline (not a failure), the failed marker otherwise. */
internal fun progressAfter(outcome: ToolOutcome): ChatToolProgress? =
    if (outcome == ToolOutcome.SUCCESS || outcome == ToolOutcome.DECLINED) null else ChatToolProgress.FAILED
enum class ChatProblem { SETUP, READ, KEY, TIMEOUT, CONTEXT_FULL, INVALID_REPLY, PROVIDER, TOO_LONG, SUMMARY, HISTORY_FULL }
data class ChatState(
    val turns: List<ChatTurn> = emptyList(), val draft: String = "", val pendingText: String? = null,
    val busy: Boolean = false, val stopping: Boolean = false, val omitted: Boolean = false,
    val problem: ChatProblem? = null, val toolProgress: ChatToolProgress? = null,
    val loading: Boolean = false,
    /** A clear waits for its Undo to run out: the chat shows empty and the draft is free; a Send commits the clear first. */
    val clearing: Boolean = false,
    /** The saved chat is being read back (Undo, or a clear that failed): typing is free, Send waits. */
    val restoring: Boolean = false,
    /** A Send waits for a pending clear to be committed (or the restore to end): its text shows as sent, nothing more can be edited. */
    val sending: Boolean = false,
    val historyProblem: ChatHistoryProblem? = null, val contextChanged: Boolean = false,
    val summarized: Boolean = false, val problemStatus: Int? = null,
    val receipts: List<ChatToolReceipt> = emptyList(), val currentSources: List<ChatSource> = emptyList(), val toolFailed: Boolean = false,
    val toolFailures: List<ChatToolFailure> = emptyList(), val information: List<ChatProfileInformation> = emptyList(),
    /** The owner's pending question for a changing call; never persisted. */
    val confirmation: PendingConfirmation? = null,
    /** "Always" could not be saved: that call was allowed once and requests stay on. Cleared by the next Send. */
    val confirmationNotSaved: Boolean = false,
)

/** A question to the owner is on screen: the message field is hidden and the card takes the room. Ends with an answer, Stop or a timeout. */
val ChatState.asking: Boolean get() = confirmation != null && !stopping

/** Chat limits shared by the saved document and the conversation; `AssistantConversation` re-exports them. */
object ChatLimits {
    const val MAX_INPUT = 8_000
    const val MAX_TURNS = 32
    const val MAX_HISTORY_CHARS = 512_000
}

/** What the chat screen enables, in one place so a test can pin it. */
val ChatState.fieldEnabled: Boolean get() = !busy && !loading && !sending && historyProblem != ChatHistoryProblem.READ
/** The history menu: not while a Send runs or waits, the history reads back, or a clear already waits. */
val ChatState.menuEnabled: Boolean get() = !busy && !loading && !restoring && !clearing && !sending
/** The history Retry (a failed read, write or clear). */
val ChatState.retryEnabled: Boolean get() = !loading && !busy && !restoring && !clearing && !sending
/** Send: a draft, no read in progress and none waiting; a pending clear does not disable it (the Send commits it). */
fun ChatState.sendEnabled(setupDone: Boolean): Boolean =
    setupDone && draft.isNotBlank() && !loading && !restoring && !sending && historyProblem != ChatHistoryProblem.READ
