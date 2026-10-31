package ru.adsrv.frpjump.notices

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * The app's messages, shown at the top of the screen over everything
 * ([ru.adsrv.frpjump.ui.NoticeHost]) rather than as system toasts at the
 * bottom, which cover cards and buttons. While no screen of the app is
 * started ([foreground] false: a notification's action, the service) a
 * notice is a toast, as nothing would draw it. Any thread.
 */
class Notices(private val context: Context, private val foreground: () -> Boolean) {
    private val ids = AtomicLong(1)
    private val _queue = MutableStateFlow(NoticeQueue())
    val queue: StateFlow<NoticeQueue> = _queue

    fun show(text: String, kind: NoticeKind = NoticeKind.INFO, long: Boolean = false, key: String? = null, action: NoticeAction? = null) {
        if (!foreground()) {
            main.post { Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
            return
        }
        val notice = TopNotice(ids.getAndIncrement(), text, kind, long, key, action)
        synchronized(_queue) { _queue.value = _queue.value.post(notice) }
    }

    fun dismiss(id: Long) {
        synchronized(_queue) { _queue.value = _queue.value.dismiss(id) }
    }

    private val main = Handler(Looper.getMainLooper())
}
