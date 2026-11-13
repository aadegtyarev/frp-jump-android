package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.assistant.AssistantChatHistory
import ru.adsrv.frpjump.assistant.ChatHistoryProblem
import ru.adsrv.frpjump.settings.AssistantAvailability

/** The one live run owner: its authority snapshot, its job and whether Stop was requested. */
internal class Pending(val revision: String?, val version: Long) { lateinit var job: Job; var stopped = false
    /** Set when the Send finished by itself while still owned: true for a reply, false for a failure or timeout (the notification's end). */
    @Volatile var replied: Boolean? = null }

/**
 * The conversation's mutable cells. They have no lock of their own: read and write them only while holding
 * `AssistantChatRuntime.gate` (`synchronized(gate)` in the conversation and `RunOwnership`), never across a suspension.
 */
internal class ChatCells(var document: SavedChat, var authority: AssistantAvailability, var revision: String?,
    private val historyStore: AssistantChatHistory?, val confirmations: ConfirmationSupport? = null) {
    /** The question the run is waiting on; cleared with the card in [clearConfirmation]. */
    var confirmation: ConfirmationSlot? = null
    /** Caller holds the gate. Id-based, so a late clear can never remove a newer question. [id] null clears whatever is pending. */
    fun clearConfirmation(id: Long? = null) {
        val slot = confirmation ?: return
        if (id != null && slot.id != id) return
        confirmation = null
        if (!closed) mutable.value = mutable.value.copy(confirmation = null)
    }
    val mutable = MutableStateFlow(ChatState())
    /** Whether a history archive backs the document (turns are then never trimmed or dropped). */
    val archive: Boolean get() = historyStore != null
    var provider: AgentProvider? = null
    var closed = false
    var loading = false
    /** From Clear until the clear is done or taken back and read again; the document is the new empty conversation, the store is not yet. */
    var clearing = false
    /** The clear of the last [clearing] period failed: a Send waiting for it must not go. */
    var clearFailed = false
    var pending: Pending? = null
    fun persist() { if (!closed && !loading && !clearing && mutable.value.historyProblem != ChatHistoryProblem.READ) historyStore?.save(document) }
    fun interrupt() { document = document.interrupted() }
    /** Only copies already projected data under the short guard. */
    fun render(value: SavedChat, projected: List<ChatTurn>) {
        if (closed || document !== value) return
        mutable.value = projectChat(value, projected, archive, pending != null, mutable.value)
    }
}
