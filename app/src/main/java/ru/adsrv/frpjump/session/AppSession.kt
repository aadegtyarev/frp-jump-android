package ru.adsrv.frpjump.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A one-time message, e.g. after "Forget profile". */
data class Notice(
    val text: String,
    /**
     * The profile the app shows once the action is done: the device list of
     * that profile shows the notice. Null: none is shown (enrollment next).
     */
    val forProfile: String?,
)

/** App-wide UI facts that outlive a screen, owned by the app container. */
class AppSession {
    private val _notice = MutableStateFlow<Notice?>(null)

    /**
     * The pending notice, for the screen the owner lands on: the device list
     * of [Notice.forProfile] as a snackbar, otherwise the enrollment screen
     * above its form. A device list of another profile (the one being left)
     * leaves it alone, so it is not lost while the app moves on.
     */
    val notice: StateFlow<Notice?> = _notice

    fun postNotice(text: String, forProfile: String?) {
        _notice.value = Notice(text, forProfile)
    }

    /** The pending notice, once, for the device list of [profile]; null when it is meant for another screen. */
    @Synchronized
    fun takeNotice(profile: String): String? =
        _notice.value?.takeIf { it.forProfile == profile }?.also { _notice.value = null }?.text

    /** The pending notice, once, whoever it was for: the enrollment screen is where the app ends up otherwise. */
    @Synchronized
    fun takeAnyNotice(): String? = _notice.value?.also { _notice.value = null }?.text
}
