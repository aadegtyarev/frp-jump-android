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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.MainActivity
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.restore.RestoreNotice
import ru.adsrv.frpjump.restore.serviceNeeded

/**
 * Keeps the app process (and so every profile's frpc, its children) alive
 * while at least one tunnel of any profile is up, with a "N tunnels
 * (in M profiles)" notification and "Disconnect all" (all of them).
 * It does not poll itself: the profiles' pollers ([ru.adsrv.frpjump.session.Poller])
 * run at the background interval while this keeps the process alive.
 * Type `specialUse`: `dataSync` is capped at 6 h a day for apps
 * targeting Android 15, and none of the other types fit a user-started
 * tunnel. It is also not among the types a `BOOT_COMPLETED` receiver may
 * not start on Android 15.
 *
 * Sticky: when Android kills the process with tunnels up, it starts the
 * service again; the app then reads the [ru.adsrv.frpjump.restore.RestoreRecord]
 * and its profiles bring their tunnels back from desired state, on the
 * same local ports. Until they run the notification says "Restoring N
 * tunnels…". Android 12+ may refuse the foreground start then (the app is
 * in the background, not exempt from battery optimisation): the service
 * gives up and [RestoreNotice] asks the owner to tap to bring them back.
 */
class TunnelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as FrpJumpApp
        val profiles = app.profiles
        // Always first, even when the tunnels are already gone: a service started with
        // startForegroundService() that stops before this crashes the app.
        val restoring = app.restoreRecord.profiles.value?.values?.sum() ?: 0
        if (!startInForeground(TunnelSummary.of(profiles.tunnelsByProfileNow(), restoring))) {
            // Restarted by Android after the process was killed, and not allowed to come to
            // the foreground from the background: let the owner bring the tunnels back.
            RestoreNotice.show(this, restoring)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        RestoreNotice.cancel(this)
        lastStartId = startId
        if (intent?.action == ACTION_DISCONNECT_ALL) profiles.disconnectAllAsync()
        if (watcher == null) {
            // The service stops itself when the last tunnel goes and none is still to come
            // back; nobody calls stopService().
            watcher = scope.launch {
                combine(profiles.tunnelsByProfile, profiles.restorePending) { up, pending -> up to pending }
                    .distinctUntilChanged()
                    .collect { (up, pending) ->
                        val summary = tunnelSummary(up)
                        if (!serviceNeeded(summary.tunnels, pending)) {
                            running = false
                            ServiceCompat.stopForeground(this@TunnelService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            // Not if a newer start is on its way: that one is for new tunnels.
                            stopSelfResult(lastStartId)
                        } else {
                            val shown = TunnelSummary.of(up, pending ?: restoring)
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(shown))
                        }
                    }
            }
        }
        // Sticky: Android starts it again after killing the process, and the tunnels come back.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    /** False when Android does not allow it now (12+, started again from the background). */
    private fun startInForeground(summary: TunnelSummary): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(summary), type)
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (an IllegalStateException) on 12+.
            Log.w(TAG, "cannot come to the foreground now: ${e.message}")
            false
        }
    }

    /**
     * "N tunnels", or "N tunnels in M profiles" when more than one profile
     * has tunnels; "Restoring N tunnels…" while none runs yet after a restart.
     */
    private fun title(summary: TunnelSummary): String {
        if (summary.tunnels == 0 && summary.restoring > 0) {
            return resources.getQuantityString(R.plurals.tunnels_restoring, summary.restoring, summary.restoring)
        }
        val tunnels = resources.getQuantityString(R.plurals.tunnels, summary.tunnels, summary.tunnels)
        return if (summary.namesProfiles) {
            resources.getQuantityString(R.plurals.tunnels_in_profiles, summary.profiles, summary.profiles, tunnels)
        } else {
            tunnels
        }
    }

    private fun notification(summary: TunnelSummary): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectAll = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_DISCONNECT_ALL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title(summary))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.disconnect_all), disconnectAll)
            .build()
    }

    companion object {
        private const val TAG = "TunnelService"
        const val CHANNEL = "tunnels"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT_ALL = "ru.adsrv.frpjump.DISCONNECT_ALL"

        /** Whether the service runs in the foreground now. */
        @Volatile
        var running = false
            private set

        /**
         * False when Android refuses a start from the background (12+): tunnels
         * run without the guard, and [RestoreNotice] asks the owner to open the
         * app, which starts it ([ru.adsrv.frpjump.FrpJumpApp]).
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, TunnelService::class.java))
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "cannot start the foreground service now: ${e.message}")
            if (!running) {
                val app = context.applicationContext as FrpJumpApp
                RestoreNotice.show(context, app.restoreRecord.profiles.value?.values?.sum() ?: 0)
            }
            false
        }
    }
}
