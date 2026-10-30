package ru.adsrv.frpjump.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** App-wide UI facts that outlive a screen, owned by the app container. */
class AppSession {
    private val notice = MutableStateFlow<String?>(null)
    private val _removed = MutableStateFlow(false)

    /** Set when the server rejects this phone's token (401): it was deleted. Polling stops. */
    val removed: StateFlow<Boolean> = _removed

    /** A one-time message for the enrollment screen, e.g. after "Forget this phone". */
    fun postNotice(text: String) {
        notice.value = text
    }

    /** Returns the pending notice once. */
    fun takeNotice(): String? = notice.getAndUpdate { null }

    fun markRemoved() {
        _removed.value = true
    }

    fun clearRemoved() {
        _removed.value = false
    }
}
