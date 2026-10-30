package ru.adsrv.frpjump.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** App-wide UI facts that outlive a screen, owned by the app container. */
class AppSession {
    private val _notice = MutableStateFlow<String?>(null)

    /**
     * A one-time message for the screen the owner lands on, e.g. after
     * "Forget profile": the device list shows it as a snackbar, the
     * enrollment screen above its form.
     */
    val notice: StateFlow<String?> = _notice

    fun postNotice(text: String) {
        _notice.value = text
    }

    /** Returns the pending notice once. */
    fun takeNotice(): String? = _notice.getAndUpdate { null }
}
