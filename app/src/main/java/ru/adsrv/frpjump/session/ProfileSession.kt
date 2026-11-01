package ru.adsrv.frpjump.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One profile's facts that outlive a screen. */
class ProfileSession {
    private val _removed = MutableStateFlow(false)

    /** Set when the profile's server rejects its token (401): it was deleted there. Its polling stops. */
    val removed: StateFlow<Boolean> = _removed

    fun markRemoved() {
        _removed.value = true
    }

    fun clearRemoved() {
        _removed.value = false
    }
}
