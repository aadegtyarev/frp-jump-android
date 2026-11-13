package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.agent.AgentModule
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.assistant.chat.ConfirmationSupport
import ru.adsrv.frpjump.assistant.chat.chatConfirmationSupport
import ru.adsrv.frpjump.profiles.ProfileInformation
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.settings.assistantTools
import ru.adsrv.frpjump.undo.PendingUndo

/** What the assistant is doing right now, for the indicator that is seen while the chat is not: nothing, working, or waiting for a card's answer. */
enum class ChatActivity { IDLE, WORKING, WAITING }

/**
 * The app-lived owner of the one chat conversation (Р32). A chat screen [attach]es and [detach]es; the conversation lasts while a screen
 * is attached or an attempt (a Send, a card wait included) runs, and is closed (draft saved, "For session" grants cleared, memory
 * released) only when both have ended. The next [attach] then builds a new one from the saved history.
 *
 * It is built on first use (the first chat opening), never at app start. The reactions that used to live in the chat's ViewModel
 * (disable, provider revision, the busy slot, a tool switched off) run here, in [scope], so they work while no screen exists.
 * Lock order: this owner's monitor, then the runtime gate; nothing takes the owner's monitor while holding the gate.
 */
class AssistantChatOwner(
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val providerStore: AssistantProviderStore,
    private val module: AgentModule,
    private val profileInformation: ProfileInformation?,
    private val sshCommands: AssistantSshCommands?,
    private val historyStore: AssistantChatHistory?,
    val historyUndo: PendingUndo?,
    private val prompts: Lazy<AssistantPrompts>,
    private val prepare: (AssistantProviderSnapshot) -> AgentProvider,
    /** Elapsed real time in milliseconds, device sleep included (`SystemClock::elapsedRealtime` in the app). */
    private val realtime: () -> Long,
    private val activityOut: MutableStateFlow<ChatActivity> = MutableStateFlow(ChatActivity.IDLE),
) {
    private class Live(val conversation: AssistantConversation, val scope: CoroutineScope, val confirmations: ConfirmationSupport)
    private val lock = Any()
    private var live: Live? = null
    private var screens = 0
    val activity: StateFlow<ChatActivity> = activityOut

    /** The live conversation's confirmation support (tests inspect its grants); null while none lives. */
    internal val confirmations: ConfirmationSupport? get() = synchronized(lock) { live?.confirmations }

    /** A screen takes the conversation (building it when none lives); the same one while an attempt runs. Main thread. */
    fun attach(): AssistantConversation {
        val conversation = synchronized(lock) { screens++; (live ?: build().also { live = it }).conversation }
        // A card that ran out of time while no screen was looking is "no answer" by the time the owner returns.
        conversation.expireCard()
        return conversation
    }

    /** The screen was left for good (its back-stack entry is gone): the draft is saved; the conversation closes unless an attempt still runs. */
    fun detach() {
        val conversation = synchronized(lock) { screens = maxOf(0, screens - 1); live?.conversation } ?: return
        conversation.saveDraft()
        settled()
    }

    /** Stop of the running attempt (the REMOVED screen; the chat's own Stop is the conversation's). Nothing runs after it. */
    fun stop() { synchronized(lock) { live?.conversation }?.stop() }

    /** Ends everything now, attempt included (a test or a private owner going away). */
    fun shutdown() {
        val old = synchronized(lock) { screens = 0; live.also { live = null } } ?: return
        old.conversation.close(); old.scope.cancel(); activityOut.value = ChatActivity.IDLE
    }

    /** An attempt ended or a screen went: close the conversation when neither remains. */
    private fun settled() {
        synchronized(lock) {
            val old = live ?: return
            if (screens == 0 && old.conversation.closeIfIdle()) { live = null; old.scope.cancel(); activityOut.value = ChatActivity.IDLE }
        }
    }

    private fun build(): Live {
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val confirmations: ConfirmationSupport = chatConfirmationSupport(settingsStore, sshCommands)
        val chat = AssistantConversation(child,
            availability = { settingsStore.assistantAvailability }, snapshot = { providerStore.currentSnapshot },
            prepare = prepare, requireRevision = providerStore::requireRevision, module = module, runtime = settingsStore.assistantChat,
            profileInformation = profileInformation, sshCommands = sshCommands, historyStore = historyStore,
            awaitHistoryClear = { historyUndo?.awaitDone("assistant-chat-history") }, prompts = prompts, confirmations = confirmations,
            realtime = realtime, onSettled = ::settled)
        child.launch { settingsStore.assistantDisables.collect { chat.refresh() } }
        child.launch { settingsStore.assistantChat.busy.collect { chat.refresh() } }
        child.launch { providerStore.state.collect { chat.refresh() } }
        // A tool that is switched off loses its session grants, even though the Tools screen opens without leaving Chat.
        child.launch { settingsStore.settings.map { it.assistantTools() }.distinctUntilChanged().collect { chat.toolsChanged(it) } }
        child.launch {
            chat.state.map { if (!it.busy) ChatActivity.IDLE else if (it.confirmation != null && !it.stopping) ChatActivity.WAITING else ChatActivity.WORKING }
                .distinctUntilChanged().collect { activityOut.value = it }
        }
        return Live(chat, child, confirmations)
    }
}
