package ru.adsrv.frpjump.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.agent.AgentModule
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.settings.SettingsStore

/**
 * Back-stack scoped, without SavedStateHandle: recreation retains it; leaving the chat releases it. The conversation itself belongs to
 * the app-lived [AssistantChatOwner]: this model only attaches to it and detaches in [onCleared], so a running attempt outlives the screen.
 * A model built from its parts (tests) owns a private owner and ends it with itself.
 */
class AssistantChatViewModel(
    private val settingsStore: SettingsStore,
    providerStore: AssistantProviderStore,
    private val owner: AssistantChatOwner,
    private val ownsOwner: Boolean = false,
) : ViewModel() {
    constructor(app: FrpJumpApp) : this(app.settings, app.assistantProvider, app.assistantChatOwner)
    constructor(
        settingsStore: SettingsStore,
        providerStore: AssistantProviderStore,
        module: AgentModule,
        profileInformation: ru.adsrv.frpjump.profiles.ProfileInformation? = null,
        sshCommands: AssistantSshCommands? = null,
        historyStore: AssistantChatHistory? = null,
        historyUndo: ru.adsrv.frpjump.undo.PendingUndo? = null,
        prompts: Lazy<AssistantPrompts>,
        prepare: (AssistantProviderSnapshot) -> AgentProvider = {
            providerStore.provider(AssistantProviderDraft(checkNotNull(it.settings), it.revision))
        },
    ) : this(settingsStore, providerStore, AssistantChatOwner(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), settingsStore, providerStore, module,
        profileInformation, sshCommands, historyStore, historyUndo, prompts, prepare, { System.nanoTime() / 1_000_000 }), true)
    init { require(settingsStore.assistantChat === providerStore.chatRuntime) }
    val conversation: AssistantConversation = owner.attach()
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
        else undo.enqueue("assistant-chat-history", commit = { conversation.deleteHistory() }, undo = { conversation.reloadHistory() })
    }
    val settings = settingsStore.settings
    val provider = providerStore.state
    /** Turns confirmation requests back on (the Chat indicator's action); the Tools switch does the same through its own binding. */
    fun turnConfirmationsOn() { tools.changeConfirmations(true) }
    internal val tools = AssistantToolsBinding(settingsStore, viewModelScope)
    override fun onCleared() { owner.detach(); if (ownsOwner) owner.shutdown() }
}
