package ru.adsrv.frpjump.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.settings.SettingsStore

/**
 * Back-stack scoped, without SavedStateHandle: recreation retains it. The conversation belongs to the app-lived [AssistantChatOwner]; this
 * model holds a lease on it ([AssistantChatLease]) and gives it up when the chat's back-stack entry is destroyed ([screenGone], also when
 * navigation keeps this model with a saved state) or when the model is cleared, so a running attempt outlives the screen.
 */
class AssistantChatViewModel(
    private val settingsStore: SettingsStore,
    providerStore: AssistantProviderStore,
    private val owner: AssistantChatOwner,
) : ViewModel() {
    constructor(app: FrpJumpApp) : this(app.settings, app.assistantProvider, app.assistantChatOwner)
    init { require(settingsStore.assistantChat === providerStore.chatRuntime) }
    private val lease = AssistantChatLease(owner)
    private val shown = androidx.compose.runtime.mutableStateOf(lease.conversation)
    /** The conversation this screen shows; a new one only after the entry came back from a saved state with the old one closed. */
    val conversation: AssistantConversation get() = shown.value
    /** The chat's entry is gone (popped, possibly with this model kept for a restore): the screen no longer holds the conversation. */
    fun screenGone() = lease.gone()
    /** The entry is shown again (restored): take the conversation again. */
    fun screenBack() { shown.value = lease.back() }
    /** The live conversation's confirmation support: a chat built here asks before a changing call. */
    internal val confirmations get() = owner.confirmations
    /** A terminal quote this chat has taken and not yet put in the draft. Memory only: it goes with the model, never saved or restored. */
    val heldQuote = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Counts quotes taken, so the screen can come back from its Tools sub-screen when one arrives (the flow above is cleared once applied). */
    val quotesTaken = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun hold(quote: String?) {
        if (quote != null) quotesTaken.update { it + 1 }
        if (quote != null) heldQuote.update { old -> if (old == null) quote else ru.adsrv.frpjump.assistant.chat.ChatQuote.appended(old, quote) }
    }
    fun clearHistory() {
        if (!conversation.hideHistory()) return
        val undo = owner.historyUndo
        if (undo == null) viewModelScope.launch { conversation.deleteHistory() }
        else undo.enqueue(AssistantChatOwner.HISTORY_UNDO_KEY, commit = { conversation.deleteHistory() }, undo = { conversation.reloadHistory() })
    }
    val settings = settingsStore.settings
    val provider = providerStore.state
    /** Turns confirmation requests back on (the Chat indicator's action); the Tools switch does the same through its own binding. */
    fun turnConfirmationsOn() { tools.changeConfirmations(true) }
    internal val tools = AssistantToolsBinding(settingsStore, viewModelScope)
    override fun onCleared() { lease.gone() }
}
