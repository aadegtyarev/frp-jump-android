package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-lived, text-free admission and authority linearization boundary.
 * Lock order: store IO monitor -> gate; chat uses gate only, never a store monitor.
 * No disk/credential IO, suspension, or cancellation is performed while holding gate.
 * Notifications are emitted after store monitors/gate have been released.
 */
class AssistantChatRuntime {
    internal val gate = Any()
    // Narrow deterministic probes for the chat/store race regressions; absent in production.
    internal var beforeAction: ((String) -> Unit)? = null
    internal var checkedAction: ((String) -> Unit)? = null
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
