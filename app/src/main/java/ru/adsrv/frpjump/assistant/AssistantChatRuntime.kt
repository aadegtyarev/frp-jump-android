package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.adsrv.frpjump.assistant.chat.ChatProbes

/** App-lived, text-free admission and authority linearization boundary.
 * Lock order: store IO monitor -> gate. Chat code takes the gate only; while holding it, the only store monitor it
 * takes is the history store's short `queue` monitor through `persist()` (gate -> queue; the store never takes the
 * gate while holding `queue`). Notifications take their own monitor and then the gate, never the reverse.
 * Nothing suspends while holding gate. `persist()` only hands the document to the history store: it launches the
 * store's save job on the store's `io` dispatcher and cancels the previous save job. Sealing and the file write run
 * in that job, outside the gate when `io` dispatches (production uses `Dispatchers.IO`; tests that pass
 * `Dispatchers.Unconfined` run them inline under the gate).
 * Notifications are emitted after store monitors/gate have been released.
 */
class AssistantChatRuntime {
    internal val gate = Any()
    internal val webSearch = WebSearchState()
    internal var publicWeb: PublicFetcher = PublicWebTransport()
    // Narrow deterministic probes for the chat/store race regressions; absent in production.
    internal var probes: ChatProbes = ChatProbes.None
    private var active: Any? = null
    private val changes = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = changes
    internal fun occupied(): Boolean = active != null
    internal fun acquire(token: Any): Boolean {
        if (active != null) return false
        active = token
        return true
    }
    internal fun release(token: Any) { if (active === token) active = null }
    private val notifications = Any()
    internal fun notifyBusy() = synchronized(notifications) {
        changes.value = synchronized(gate) { occupied() }
    }
}
