package ru.adsrv.frpjump.direct

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.adsrv.frpjump.FrpJumpApp

/**
 * The "Direct didn't work" notification's actions: "Retry direct", "Keep
 * through the server", and its swipe. Not exported; reached only through
 * the notification's own PendingIntents.
 */
class DirectActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as FrpJumpApp
        val profile = intent.getStringExtra(EXTRA_PROFILE) ?: return
        val device = intent.getStringExtra(EXTRA_DEVICE) ?: return
        val grant = intent.getStringExtra(EXTRA_GRANT) ?: return
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val key = DirectOffer(profile, device, port, grant, "").key
        // The notification goes at once: the tap visibly did something.
        app.directNotifier.dismiss(grant, key.takeIf { intent.action != ACTION_DISMISS })
        if (intent.action == ACTION_DISMISS) return
        val pending = goAsync()
        app.appScope.launch {
            try {
                // Opened by the tap itself (the process had gone): profiles load first.
                withTimeoutOrNull(LOAD_WAIT_MS) { app.profiles.state.first { it.loaded } } ?: return@launch
                when (intent.action) {
                    ACTION_RETRY -> app.directChoices.retryDirect(profile, device, port)
                    ACTION_KEEP -> app.directChoices.keepThroughServer(profile, device)
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
        private const val EXTRA_PORT = "port"
        private const val EXTRA_GRANT = "grant"
        private const val LOAD_WAIT_MS = 8_000L

        fun intent(context: Context, action: String, offer: DirectOffer): PendingIntent = PendingIntent.getBroadcast(
            context,
            (action + offer.key).hashCode(),
            Intent(context, DirectActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_PROFILE, offer.profile)
                .putExtra(EXTRA_DEVICE, offer.device)
                .putExtra(EXTRA_PORT, offer.port)
                .putExtra(EXTRA_GRANT, offer.grantId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
