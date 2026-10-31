package ru.adsrv.frpjump.direct

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.notices.NoticeKind

/**
 * The "Direct didn't work" notification's actions: "Retry direct", "Keep
 * through the server", and its swipe. Not exported; reached only through
 * the notification's own PendingIntents. The work is bounded by
 * [WORK_LIMIT_MS], well inside a receiver's 10 s.
 */
class DirectActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as FrpJumpApp
        val profile = intent.getStringExtra(EXTRA_PROFILE) ?: return
        val device = intent.getStringExtra(EXTRA_DEVICE) ?: return
        when (intent.action) {
            ACTION_DISMISS -> {
                app.directNotifier.dismiss(profile, device, cancel = false)
                return
            }
            // The notification goes at once: the tap visibly did something.
            ACTION_RETRY -> app.directNotifier.dismiss(profile, device, cancel = true)
        }
        val pending = goAsync()
        app.appScope.launch {
            try {
                withTimeoutOrNull(WORK_LIMIT_MS) {
                    // Opened by the tap itself (the process had gone): profiles load first.
                    app.profiles.state.first { it.loaded }
                    when (intent.action) {
                        ACTION_RETRY -> app.directChoices.retryDirect(profile, device)
                        ACTION_KEEP -> if (app.directChoices.keepThroughServer(profile, device)) {
                            app.directNotifier.dismiss(profile, device, cancel = true)
                        } else {
                            app.notices.show(Texts.get(R.string.keep_no_network), NoticeKind.WARN, long = true)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RETRY = "ru.adsrv.frpjump.RETRY_DIRECT"
        const val ACTION_KEEP = "ru.adsrv.frpjump.KEEP_THROUGH_SERVER"
        const val ACTION_DISMISS = "ru.adsrv.frpjump.DIRECT_OFFER_DISMISSED"
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_DEVICE = "device"
        private const val WORK_LIMIT_MS = 8_000L

        fun intent(context: Context, action: String, offer: DirectOffer): PendingIntent = PendingIntent.getBroadcast(
            context,
            (action + offer.key).hashCode(),
            Intent(context, DirectActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_PROFILE, offer.profile)
                .putExtra(EXTRA_DEVICE, offer.device),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
