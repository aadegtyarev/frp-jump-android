package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
 * How the last attempt ended, for the notification: nothing (running, or stopped by the owner or a disable: no end to report), a reply,
 * a failure or timeout, or an unanswered question that ended it with no screen attached.
 */
enum class ChatEnd { NONE, COMPLETED, FAILED, UNANSWERED }

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
    private val endOut: MutableStateFlow<ChatEnd> = MutableStateFlow(ChatEnd.NONE),
    /** True while a Stop is requested for the running attempt and it is winding down (the notification goes at once). */
    private val stoppingOut: MutableStateFlow<Boolean> = MutableStateFlow(false),
    /** True while the current profile's server has removed this phone (the REMOVED screen): a running attempt is stopped, screen or not. */
    private val removed: Flow<Boolean> = emptyFlow(),
) {
    private class Live(val conversation: AssistantConversation, val scope: CoroutineScope, val confirmations: ConfirmationSupport)
    private val lock = Any()
    private var live: Live? = null
    private var screens = 0
    val activity: StateFlow<ChatActivity> = activityOut
    /** How the last attempt ended (see [ChatEnd]); back to NONE when a screen attaches or a Send starts. */
    val lastEnd: StateFlow<ChatEnd> = endOut
    /** Screen attach/detach moments (real time), newest last: whether a screen was attached at a past moment (a card's expiry). */
    private val moments = ArrayDeque<Pair<Long, Boolean>>()

    /** The live conversation's confirmation support (tests inspect its grants); null while none lives. */
    internal val confirmations: ConfirmationSupport? get() = synchronized(lock) { live?.confirmations }

    /** A screen takes the conversation (building it when none lives); the same one while an attempt runs. Main thread. */
    fun attach(): AssistantConversation {
        val conversation = synchronized(lock) {
            if (live == null) { moments.clear(); build().also { live = it } }
            if (screens++ == 0) { moments.addLast(realtime() to true); while (moments.size > 64) moments.removeFirst() }
            endOut.value = ChatEnd.NONE
            live!!.conversation
        }
        // A card that ran out of time while no screen was looking is "no answer" by the time the owner returns.
        conversation.expireCard()
        return conversation
    }

    /** The screen was left for good (its back-stack entry is gone): the draft is saved; the conversation closes unless an attempt still runs. */
    fun detach() {
        val conversation = synchronized(lock) {
            if (screens > 0 && --screens == 0) { moments.addLast(realtime() to false); while (moments.size > 64) moments.removeFirst() }
            live?.conversation
        } ?: return
        conversation.saveDraft()
        settled()
    }

    /** Stop of the running attempt (the REMOVED screen; the chat's own Stop is the conversation's). Nothing runs after it. */
    fun stop() { synchronized(lock) { live?.conversation }?.stop() }

    /** Ends everything now, attempt included (a test or a private owner going away). */
    fun shutdown() {
        val old = synchronized(lock) { screens = 0; live.also { live = null }.also { activityOut.value = ChatActivity.IDLE; stoppingOut.value = false } } ?: return
        old.conversation.close(); old.scope.cancel()
    }

    /** Whether a screen was attached at [time] (the state of the latest attach/detach at or before it). */
    private fun attachedAt(time: Long): Boolean = synchronized(lock) {
        var state: Boolean? = null
        for ((at, attached) in moments) if (at <= time) state = attached
        state ?: moments.firstOrNull()?.second?.not() ?: false
    }

    /** The activity indicator is written under the lock and only for the live conversation, so a closing one cannot overwrite a newer one. */
    internal fun publish(chat: AssistantConversation, value: ChatActivity) {
        synchronized(lock) { if (live?.conversation === chat) activityOut.value = value }
    }

    /** An attempt ended or a screen went: close the conversation when neither remains. */
    private fun settled() {
        synchronized(lock) {
            val old = live ?: return
            if (screens == 0 && old.conversation.closeIfIdle()) { live = null; old.scope.cancel(); activityOut.value = ChatActivity.IDLE; stoppingOut.value = false }
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
            realtime = realtime, onSettled = ::settled, screenAttachedAt = ::attachedAt,
            onUnanswered = { synchronized(lock) { endOut.value = ChatEnd.UNANSWERED } },
            onEnded = { replied -> synchronized(lock) { if (endOut.value != ChatEnd.UNANSWERED) endOut.value = if (replied) ChatEnd.COMPLETED else ChatEnd.FAILED } })
        child.launch { settingsStore.assistantDisables.collect { chat.refresh() } }
        child.launch { settingsStore.assistantChat.busy.collect { chat.refresh() } }
        child.launch { providerStore.state.collect { chat.refresh() } }
        // A tool that is switched off loses its session grants, even though the Tools screen opens without leaving Chat.
        child.launch { settingsStore.settings.map { it.assistantTools() }.distinctUntilChanged().collect { chat.toolsChanged(it) } }
        child.launch {
            var before = ChatActivity.IDLE
            chat.state.map { if (!it.busy) ChatActivity.IDLE else if (it.confirmation != null && !it.stopping) ChatActivity.WAITING else ChatActivity.WORKING }
                .distinctUntilChanged().collect {
                    publish(chat, it)
                    // A new Send (any step from idle) clears the last end; the WAITING to WORKING step of an attempt that ends unanswered does not.
                    if (it != ChatActivity.IDLE && before == ChatActivity.IDLE) synchronized(lock) { if (live?.conversation === chat) endOut.value = ChatEnd.NONE }
                    before = it
                }
        }
        child.launch { chat.state.map { it.busy && it.stopping }.distinctUntilChanged().collect { synchronized(lock) { if (live?.conversation === chat) stoppingOut.value = it } } }
        // The server removed this phone's profile: the attempt stops even when no screen (so no AppNav) is there to do it.
        child.launch { removed.collect { gone -> if (gone) chat.stop() } }
        return Live(chat, child, confirmations)
    }
}

/**
 * One chat screen's hold on the conversation. It is attached when made and gives the conversation up in [gone]: when its back-stack entry
 * is destroyed (popped, also with its state saved, where the ViewModel itself would stay) or the ViewModel is cleared. [back] takes the
 * conversation again if the entry comes back (restored); an idle one was closed meanwhile, so it may be a new instance.
 */
class AssistantChatLease(private val owner: AssistantChatOwner) {
    private var attached = true
    var conversation: AssistantConversation = owner.attach(); private set
    fun gone() { synchronized(this) { if (!attached) return; attached = false }; owner.detach() }
    fun back(): AssistantConversation {
        synchronized(this) { if (attached) return conversation; attached = true }
        conversation = owner.attach()
        return conversation
    }
}
