package ru.adsrv.frpjump.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.chat.ChatState
import ru.adsrv.frpjump.settings.AssistantTools
import ru.adsrv.frpjump.settings.SettingsStore

/**
 * The "Turn on" button under a reply that asked for a tool that is off (Р56). Pressing it writes the same persisted switch as Tools (the
 * Tools binding's save path, no network) and, only when that switch really is on afterwards, hands [send] the owner's fixed message, which
 * the conversation sends as a new Send. A press while one runs, or for a tool that is already on, does nothing. Main thread only.
 */
internal class AssistantToolRequest(
    private val store: SettingsStore,
    private val tools: AssistantToolsBinding,
    private val scope: CoroutineScope,
    private val send: (String) -> Unit,
) {
    private var running = false

    fun turnOn(id: AssistantToolId, message: String) {
        if (running || store.assistantAvailability.tools[id]) return
        running = true
        scope.launch {
            try {
                tools.change(id, true).join()
                if (store.assistantAvailability.tools[id]) send(message)
            } finally { running = false }
        }
    }
}

/**
 * The tools whose "Turn on" / "No thanks" buttons show under the latest reply (Р56). None while an attempt runs or a message waits, with
 * the assistant off, after a Send or a clear (the latest turn is then another one or none), and none for a tool that is on or declined.
 * What the latest completed attempt asked for comes from its saved calls and results, so nothing here is stored.
 */
internal fun requestedTools(state: ChatState, enabled: Boolean, tools: AssistantTools): List<AssistantToolId> {
    if (!enabled || state.busy || state.pendingText != null || state.loading || state.clearing) return emptyList()
    val turn = state.turns.lastOrNull() ?: return emptyList()
    return turn.askedTools.mapNotNull(AssistantToolId::fromWire).filter { !tools[it] && it.wire !in state.declinedTools }
}
