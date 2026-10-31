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
 * "Tap to restore N tunnels": Android did not let the tunnels service start
 * from the background (12+, the app not exempt from battery optimisation),
 * so it cannot keep the tunnels up. Tapping opens the app, which starts the
 * service and brings them back. On the tunnels' channel, taken back once
 * the service runs.
 */
object RestoreNotice {
    private const val NOTIFICATION_ID = 3

    fun show(context: Context, tunnels: Int) {
        if (tunnels <= 0) return
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
        val text = context.getString(R.string.restore_notice_text)
        val notification = NotificationCompat.Builder(context, TunnelService.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.resources.getQuantityString(R.plurals.restore_notice_title, tunnels, tunnels))
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
