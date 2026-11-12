package ru.adsrv.frpjump.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.agent.AgentModule
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.settings.SettingsStore

/** Back-stack scoped, without SavedStateHandle: recreation retains memory; pop releases it. */
class AssistantChatViewModel(
    private val settingsStore: SettingsStore,
    providerStore: AssistantProviderStore,
    module: AgentModule,
    profileInformation: ru.adsrv.frpjump.profiles.ProfileInformation? = null,
    sshCommands: AssistantSshCommands? = null,
    historyStore: AssistantChatHistory? = null,
    private val historyUndo: ru.adsrv.frpjump.undo.PendingUndo? = null,
    prompts: Lazy<AssistantPrompts>,
    prepare: (AssistantProviderSnapshot) -> AgentProvider = {
        providerStore.provider(AssistantProviderDraft(checkNotNull(it.settings), it.revision))
    },
) : ViewModel() {
    constructor(app: FrpJumpApp) : this(app.settings, app.assistantProvider, app.assistant, app.profileInformation, app.assistantSshCommands,
        app.assistantHistory, app.pendingUndo, app.assistantPrompts)
    init { require(settingsStore.assistantChat === providerStore.chatRuntime) }
    val conversation = AssistantConversation(viewModelScope,
        availability = { settingsStore.assistantAvailability }, snapshot = { providerStore.currentSnapshot },
        prepare = prepare, requireRevision = providerStore::requireRevision, module = module, runtime = settingsStore.assistantChat, profileInformation = profileInformation, sshCommands = sshCommands,
        historyStore = historyStore, awaitHistoryClear = { historyUndo?.awaitDone("assistant-chat-history") }, prompts = prompts)
    fun clearHistory() {
        if (!conversation.hideHistory()) return
        if (historyUndo == null) viewModelScope.launch { conversation.deleteHistory() }
        else historyUndo.enqueue("assistant-chat-history", commit = { conversation.deleteHistory() }, undo = { conversation.reloadHistory() })
    }
    val settings = settingsStore.settings
    val provider = providerStore.state
    init {
        viewModelScope.launch { settingsStore.assistantDisables.collect { conversation.refresh() } }
        viewModelScope.launch { settingsStore.assistantChat.busy.collect { conversation.refresh() } }
        viewModelScope.launch { provider.collect { conversation.refresh() } }
    }
    internal val tools = AssistantToolsBinding(settingsStore, viewModelScope)
    override fun onCleared() { conversation.close() }
}
