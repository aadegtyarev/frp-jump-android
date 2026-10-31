package ru.adsrv.frpjump.tunnel

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.content.res.Resources
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
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.MainActivity
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.localized
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
    /** What the notification shows now; said again in a new language. */
    private var shown: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Already in the foreground with its notification: only say it again, in the app's language now.
        if (intent?.action == ACTION_REFRESH && running) {
            shown?.let { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(it)) }
            return START_STICKY
        }
        val app = application as FrpJumpApp
        val profiles = app.profiles
        // Always first, even when the tunnels are already gone: a service started with
        // startForegroundService() that stops before this crashes the app. Just after a
        // restart the record may not be read yet: "Restoring tunnels…" without a count.
        val recorded = app.restoreRecord.profiles.value?.values?.sum()
        if (!startInForeground(View(TunnelSummary.of(profiles.tunnelsByProfileNow(), recorded)))) {
            // Restarted by Android after the process was killed, and not allowed to come to
            // the foreground from the background: let the owner bring the tunnels back.
            app.appScope.launch {
                val count = withContext(Dispatchers.IO) { app.restoreRecord.load() }.values.sum()
                RestoreNotice.show(app, count, RestoreNotice.Kind.RESTORE)
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        everInForeground = true
        RestoreNotice.cancel(this)
        lastStartId = startId
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            // Not before the profiles are read: until then there is nothing to disconnect.
            app.appScope.launch {
                profiles.awaitLoaded()
                profiles.disconnectAll()
            }
        }
        if (watcher == null) {
            // The service stops itself when the last tunnel goes and none is still to come
            // back; nobody calls stopService().
            watcher = scope.launch {
                combine(
                    profiles.tunnelsByProfile,
                    profiles.restorePending,
                    app.restoreRecord.profiles,
                    profiles.restoreWaiting,
                    profiles.disconnectAllFailed,
                ) { up, pending, record, waiting, failed ->
                    val summary = TunnelSummary.of(up, pending ?: record?.values?.sum())
                    if (serviceNeeded(summary.tunnels, pending)) View(summary, waiting, failed) else null
                }
                    // "Disconnect all" here cannot ask: the title says what it would close.
                    .combine(app.ssh.liveCount) { view, sessions -> view?.copy(sessions = sessions) }
                    .distinctUntilChanged()
                    .collect { view ->
                        if (view == null) {
                            running = false
                            ServiceCompat.stopForeground(this@TunnelService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            // Not if a newer start is on its way: that one is for new tunnels.
                            stopSelfResult(lastStartId)
                        } else {
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(view))
                        }
                        shown = view
                    }
            }
        }
        // Sticky: Android starts it again after killing the process, and the tunnels come back.
        return START_STICKY
    }

    /** What the notification shows. */
    private data class View(
        val summary: TunnelSummary,
        /** Why tunnels still to come back are not back yet. */
        val waiting: Waiting? = null,
        /** The last "Disconnect all" could not tell a server. */
        val disconnectFailed: Boolean = false,
        /** Terminal sessions not ended, which "Disconnect all" closes. */
        val sessions: Int = 0,
    )

    /** Android 13+ tells the whole app of a new language (Settings › Language): the notification says it too. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        shown?.let { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(it)) }
    }

    override fun onDestroy() {
        running = false
        // Stopped by Android while not in the foreground: its notification is no longer backed.
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * False when Android does not allow it now: 12+ throws when the service
     * was started again from the background; for an app the owner restricted
     * in the background it only logs and leaves the service in the background.
     */
    private fun startInForeground(view: View): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(view), type)
            isInForeground()
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (an IllegalStateException) on 12+.
            Log.w(TAG, "cannot come to the foreground now: ${e.message}")
            false
        }
    }

    /** Whether Android took this service into the foreground (it reports its own services). */
    @Suppress("DEPRECATION")
    private fun isInForeground(): Boolean =
        getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == TunnelService::class.java.name && it.foreground }

    /**
     * "N tunnels", or "N tunnels in M profiles" when more than one profile
     * has tunnels, then " · M terminal sessions" when any is open;
     * "Restoring (N) tunnels…" while none runs yet after a restart.
     */
    private fun title(res: Resources, view: View): String {
        val tunnels = tunnelsLine(res, view.summary)
        if (view.sessions == 0 || tunnelsTitle(view.summary) !is TunnelsTitle.Up) return tunnels
        return tunnels + " · " + res.getQuantityString(R.plurals.notification_terminal_sessions, view.sessions, view.sessions)
    }

    private fun tunnelsLine(res: Resources, summary: TunnelSummary): String = when (val title = tunnelsTitle(summary)) {
        TunnelsTitle.RestoringUnknown -> res.getString(R.string.tunnels_restoring_unknown)
        is TunnelsTitle.Restoring -> res.getQuantityString(R.plurals.tunnels_restoring, title.count, title.count)
        is TunnelsTitle.Up -> {
            val tunnels = res.getQuantityString(R.plurals.tunnels, summary.tunnels, summary.tunnels)
            if (summary.namesProfiles) {
                res.getQuantityString(R.plurals.tunnels_in_profiles, summary.profiles, summary.profiles, tunnels)
            } else {
                tunnels
            }
        }
    }

    /** While tunnels are still to come back: a failed "Disconnect all" first, then what they wait for. */
    private fun text(res: Resources, view: View): String? = when {
        tunnelsTitle(view.summary) is TunnelsTitle.Up -> null
        view.disconnectFailed -> res.getString(R.string.disconnect_all_failed)
        view.waiting == Waiting.UNLOCK -> res.getString(R.string.restore_waiting_unlock)
        view.waiting == Waiting.NETWORK -> res.getString(R.string.restore_waiting_network)
        else -> null
    }

    private fun notification(view: View): Notification {
        val res = localized().resources
        // Created, or renamed into the app's current language.
        tunnelsChannel(this, res)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectAll = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_DISCONNECT_ALL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jump)
            .setContentTitle(title(res, view))
            .setContentText(text(res, view))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, res.getString(R.string.disconnect_all), disconnectAll)
            .build()
    }

    companion object {
        private const val TAG = "TunnelService"
        const val CHANNEL = "tunnels"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT_ALL = "ru.adsrv.frpjump.DISCONNECT_ALL"
        private const val ACTION_REFRESH = "ru.adsrv.frpjump.REFRESH_NOTIFICATION"

        /** Whether the service runs in the foreground now. */
        @Volatile
        var running = false
            private set

        /**
         * Whether this process has had the service in the foreground. A
         * process that never had it and starts tunnels from the background is
         * bringing them back after a restart (sticky restart, boot).
         */
        @Volatile
        private var everInForeground = false

        /**
         * False when Android refuses a start from the background (12+): tunnels
         * run without the guard, and [RestoreNotice] asks the owner to open the
         * app, which starts it ([ru.adsrv.frpjump.FrpJumpApp]): to restore them
         * when this [restarting] process brings them back, else to keep them.
         */
        /**
         * Says the notification again in the app's language, after Settings ›
         * Language on Android 10–12 (13+ tells the service itself). Called
         * from the app in the foreground; nothing when the service is not up.
         */
        fun refresh(context: Context) {
            if (!running) return
            runCatching { context.startService(Intent(context, TunnelService::class.java).setAction(ACTION_REFRESH)) }
                .onFailure { Log.w(TAG, "cannot refresh the notification: ${it.message}") }
        }

        fun start(context: Context, restarting: Boolean = !everInForeground): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, TunnelService::class.java))
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "cannot start the foreground service now: ${e.message}")
            if (!running) {
                val app = context.applicationContext as FrpJumpApp
                val count = app.restoreRecord.profiles.value?.values?.sum() ?: 0
                RestoreNotice.show(context, count, if (restarting) RestoreNotice.Kind.RESTORE else RestoreNotice.Kind.KEEP)
            }
            false
        }
    }
}

/** The tunnels channel ([TunnelService.CHANNEL]), created or renamed in [res]'s language. */
fun tunnelsChannel(context: Context, res: Resources) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(TunnelService.CHANNEL, res.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
    )
}
