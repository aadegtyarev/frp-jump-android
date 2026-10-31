package ru.adsrv.frpjump.restore

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ru.adsrv.frpjump.MainActivity
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.tunnel.TunnelService

/**
 * Android did not let the tunnels service start from the background (12+,
 * the app not exempt from battery optimisation, or restricted in the
 * background), so nothing keeps the tunnels up. Tapping opens the app,
 * which starts the service. On the tunnels' channel, taken back once the
 * service runs.
 */
object RestoreNotice {
    private const val NOTIFICATION_ID = 3

    enum class Kind {
        /** After a restart (process killed, reboot): "Tap to restore N tunnels". */
        RESTORE,

        /** An ordinary start with the app closed: "Tap to keep N tunnels running". */
        KEEP,
    }

    /** [tunnels] 0: no count is known, the notice says "tunnels". */
    fun show(context: Context, tunnels: Int, kind: Kind) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(TunnelService.CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(TunnelService.CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (kind) {
            Kind.RESTORE -> if (tunnels > 0) {
                context.resources.getQuantityString(R.plurals.restore_notice_title, tunnels, tunnels)
            } else {
                context.getString(R.string.restore_notice_title_unknown)
            }
            Kind.KEEP -> if (tunnels > 0) {
                context.resources.getQuantityString(R.plurals.keep_notice_title, tunnels, tunnels)
            } else {
                context.getString(R.string.keep_notice_title_unknown)
            }
        }
        val text = context.getString(if (kind == Kind.RESTORE) R.string.restore_notice_text else R.string.keep_notice_text)
        val notification = NotificationCompat.Builder(context, TunnelService.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
