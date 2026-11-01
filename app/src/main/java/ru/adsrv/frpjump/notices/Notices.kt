package ru.adsrv.frpjump.notices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * The app's messages, shown at the top of the screen over everything
 * ([ru.adsrv.frpjump.ui.NoticeHost]) rather than as system toasts at the
 * bottom, which cover cards and buttons. While no screen of the app is
 * started ([foreground] false: a notification's action, the service) a
 * message goes to [fallback] (a toast), as nothing would draw it; while
 * the app is [locked] it is dropped, as the lock screen shows nothing of
 * the app. Any thread.
 */
class Notices(
    private val foreground: () -> Boolean,
    private val locked: () -> Boolean,
    private val fallback: (text: String, long: Boolean) -> Unit,
) {
    private val ids = AtomicLong(1)
    private val _queue = MutableStateFlow(NoticeQueue())
    val queue: StateFlow<NoticeQueue> = _queue

    /**
     * The hosts drawn now, the innermost last: the screen's, then one per
     * bottom sheet open over it. Only the last one shows (and times) the
     * notice, so it is never left to run out under a sheet's scrim.
     */
    private val _hosts = MutableStateFlow<List<Any>>(emptyList())
    val hosts: StateFlow<List<Any>> = _hosts

    /** [first]: shown before anything else and never dropped (Undo, whose time counts). */
    fun show(text: String, kind: NoticeKind = NoticeKind.INFO, long: Boolean = false, key: String? = null, action: NoticeAction? = null, first: Boolean = false) {
        if (locked()) return
        if (!foreground()) return fallback(text, long)
        val notice = TopNotice(ids.getAndIncrement(), text, kind, long, key, action, first)
        synchronized(_queue) { _queue.value = _queue.value.post(notice) }
    }

    /** Like [show], but only while a screen of the app is started: news not worth a toast in the background. */
    fun showInApp(text: String, kind: NoticeKind = NoticeKind.INFO, key: String? = null) {
        if (foreground()) show(text, kind, key = key)
    }

    fun dismiss(id: Long) {
        synchronized(_queue) { _queue.value = _queue.value.dismiss(id) }
    }

    fun dismissKey(key: String) {
        synchronized(_queue) { _queue.value = _queue.value.dismissKey(key) }
    }

    /** The app went to the background: what was not seen yet becomes toasts rather than be lost. */
    fun toBackground() {
        val left = synchronized(_queue) { _queue.value.items.also { _queue.value = NoticeQueue() } }
        if (!locked()) left.forEach { fallback(it.text, it.long) }
    }

    /** Locked: nothing waits to be shown over the lock screen or after it. */
    fun clear() {
        synchronized(_queue) { _queue.value = NoticeQueue() }
    }

    fun attach(host: Any) {
        synchronized(_hosts) { _hosts.value = _hosts.value + host }
    }

    fun detach(host: Any) {
        synchronized(_hosts) { _hosts.value = _hosts.value - host }
    }
}
