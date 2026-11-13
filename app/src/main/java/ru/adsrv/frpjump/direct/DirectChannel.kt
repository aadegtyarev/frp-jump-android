package ru.adsrv.frpjump.direct

/**
 * The notification channel "Direct didn't work" used to post to. The app
 * posts nothing there any more (operator decision Р34); the channel is deleted
 * at every start, which also takes back what an older version left shown, and
 * removes it from the system settings.
 */
object DirectChannel {
    const val ID = "direct"

    /** [delete] is `NotificationManager.deleteNotificationChannel`; a failure leaves it for the next start. */
    fun retire(delete: (String) -> Unit) {
        runCatching { delete(ID) }
    }
}
