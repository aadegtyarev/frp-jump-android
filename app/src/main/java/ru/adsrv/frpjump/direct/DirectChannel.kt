package ru.adsrv.frpjump.direct

import ru.adsrv.frpjump.store.TextStorage

/**
 * What older versions left for "Direct didn't work": a notification channel
 * ("Direct connection", whose notifications were titled "Direct didn't work")
 * and the offers file. The app posts nothing there any more; both are removed
 * at every start, which also takes back notifications still shown and removes
 * the channel from the system settings.
 */
object DirectChannel {
    /** The channel's id: the only place that names it. */
    const val ID = "direct"

    /**
     * [deleteChannel] is `NotificationManager.deleteNotificationChannel`;
     * [offered] is the old `direct_offered.json` (its `.new` and `.bak`
     * leftovers go with it). Each step on its own: a failure of one does not stop
     * the other or the start.
     */
    fun retireLegacy(deleteChannel: (String) -> Unit, offered: TextStorage) {
        runCatching { deleteChannel(ID) }
        runCatching { offered.delete() }
    }
}
