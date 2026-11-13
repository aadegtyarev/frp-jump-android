package ru.adsrv.frpjump.assistant

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.MainActivity
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts

/**
 * Posts the assistant's one notification per attempt (Р32) while the chat is not on screen, and removes it when the chat is shown or
 * nothing is left to say. What it shows is [assistantNotice]; the text is fixed (see [AssistantNotice]). Without the notification
 * permission nothing is posted. The channel has default importance (so the notification and its Stop show on the lock screen) with no
 * sound, no vibration and no badge.
 */
class AssistantNotifier(private val context: Context) : AssistantNoticeSink {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun start(scope: CoroutineScope, activity: Flow<ChatActivity>, end: Flow<ChatEnd>, shown: Flow<Boolean>, enabled: Flow<Boolean>, stopping: Flow<Boolean>) {
        val driver = AssistantNoticeDriver(this)
        driver.start()
        scope.launch {
            combine(activity, end, shown, enabled, stopping) { a, e, s, on, st -> arrayOf<Any>(a, e, s, on, st) }
                .collect { v ->
                    driver.update(v[0] as ChatActivity, v[2] as Boolean, v[1] as ChatEnd, permitted(), v[3] as Boolean, v[4] as Boolean)
                }
        }
    }

    override fun cancel() { manager?.cancel(NOTIFICATION_ID) }

    override fun post(notice: AssistantNotice) {
        val manager = manager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, Texts.get(R.string.assistant_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false); setSound(null, null); enableVibration(false)
            },
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jump)
            .setContentTitle(Texts.get(notice.titleRes))
            .setContentIntent(openIntent(context))
            .setOngoing(notice.ongoing)
            .setAutoCancel(!notice.ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (notice.ongoing) builder.addAction(0, Texts.get(R.string.assistant_notice_stop), stopIntent(context))
        // Permission lost since the check: the system drops it, nothing more to do.
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun permitted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL = "assistant"
        const val NOTIFICATION_ID = 4

        private fun stopIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, AssistantStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )

        private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, AssistantOpenActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** The notification's Stop: the chat's Stop, nothing else. Not exported; idempotent (no attempt, nothing happens). */
class AssistantStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as FrpJumpApp
        app.stopAssistantAttempt()
        // No attempt runs (the process died with the notification up, or it just ended): nothing else will remove it.
        if (app.assistantActivity.value == ChatActivity.IDLE) context.getSystemService(NotificationManager::class.java)?.cancel(AssistantNotifier.NOTIFICATION_ID)
    }
}

/**
 * The notification's tap: no UI. It sets the in-process request "open the chat" and brings up [MainActivity] with the launcher intent
 * (no extras); the app lock shows first, as for any launch.
 */
class AssistantOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as FrpJumpApp).openAssistantRequest.value = true
        // The launcher intent: the app's existing task comes to the front without a second MainActivity (a single-top flag never matches from here).
        startActivity(
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
        finish()
    }
}
