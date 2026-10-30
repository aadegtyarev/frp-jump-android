package ru.adsrv.frpjump.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.MainActivity

/**
 * Keeps the app process (and so every profile's frpc, its children) alive
 * while at least one tunnel of any profile is up, with a "N tunnels"
 * notification (all profiles together) and "Disconnect all" (all of them).
 * It does not poll itself: the profiles' pollers ([ru.adsrv.frpjump.session.Poller])
 * run at the background interval while this keeps the process alive.
 * Type `specialUse`: `dataSync` is capped at 6 h a day for apps
 * targeting Android 15, and none of the other types fit a user-started
 * tunnel.
 */
class TunnelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val profiles = (application as FrpJumpApp).profiles
        // Always first, even when the tunnels are already gone: a service started with
        // startForegroundService() that stops before this crashes the app.
        startInForeground(profiles.tunnelCountNow())
        lastStartId = startId
        if (intent?.action == ACTION_DISCONNECT_ALL) profiles.disconnectAllAsync()
        if (watcher == null) {
            // The service stops itself when the last tunnel goes; nobody calls stopService().
            watcher = scope.launch {
                profiles.tunnelCount.distinctUntilChanged().collect { count ->
                    if (count == 0) {
                        ServiceCompat.stopForeground(this@TunnelService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        // Not if a newer start is on its way: that one is for new tunnels.
                        stopSelfResult(lastStartId)
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(count))
                    }
                }
            }
        }
        // Not sticky: if Android kills the process, frpc dies with it and the
        // tunnels come back from desired state when the app is opened again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(count: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(count), type)
    }

    private fun notification(count: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Tunnels", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectAll = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_DISCONNECT_ALL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(if (count == 1) "1 tunnel" else "$count tunnels")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Disconnect all", disconnectAll)
            .build()
    }

    companion object {
        private const val CHANNEL = "tunnels"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT_ALL = "ru.adsrv.frpjump.DISCONNECT_ALL"

        /** False when Android refuses a start from the background (12+): tunnels run without the guard. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, TunnelService::class.java))
            true
        } catch (e: IllegalStateException) {
            Log.w("TunnelService", "cannot start the foreground service now: ${e.message}")
            false
        }
    }
}
