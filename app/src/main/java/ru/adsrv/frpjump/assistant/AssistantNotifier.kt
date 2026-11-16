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

    fun start(scope: CoroutineScope, activity: Flow<ChatActivity>, end: Flow<ChatEnd>, shown: Flow<Boolean>, enabled: Flow<Boolean>, stopping: Flow<Boolean>, waits: Flow<Int> = kotlinx.coroutines.flow.flowOf(0)) {
        val driver = AssistantNoticeDriver(this)
        driver.start()
        scope.launch {
            combine(listOf<Flow<Any>>(activity, end, shown, enabled, stopping, waits)) { it }
                .collect { v ->
                    driver.update(v[0] as ChatActivity, v[2] as Boolean, v[1] as ChatEnd, permitted(), v[3] as Boolean, v[4] as Boolean, v[5] as Int)
                }
        }
    }

    override fun cancel() { manager?.cancel(NOTIFICATION_ID) }

    override fun post(notice: AssistantNotice) = post(notice, 0)

    override fun postWaiting(count: Int) = post(AssistantNotice.LISTENING, count)

    private fun post(notice: AssistantNotice, count: Int) {
        val manager = manager ?: return
        val waiting = notice == AssistantNotice.LISTENING
        // Waiting for events is quiet: its own channel of low importance. Working and the ends keep the default one (the lock screen shows them).
        manager.createNotificationChannel(
            if (waiting) NotificationChannel(CHANNEL_WAITING, Texts.get(R.string.assistant_channel_waiting), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null); enableVibration(false)
            } else NotificationChannel(CHANNEL, Texts.get(R.string.assistant_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false); setSound(null, null); enableVibration(false)
            },
        )
        val builder = NotificationCompat.Builder(context, if (waiting) CHANNEL_WAITING else CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jump)
            .setContentTitle(Texts.get(notice.titleRes, count))
            .setContentIntent(openIntent(context))
            .setOngoing(notice.ongoing)
            .setAutoCancel(!notice.ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(if (waiting) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
        if (waiting) builder.addAction(0, Texts.get(R.string.assistant_notice_cancel_waits), cancelWaitsIntent(context))
        else if (notice.ongoing) builder.addAction(0, Texts.get(R.string.assistant_notice_stop), stopIntent(context))
        // Permission lost since the check: the system drops it, nothing more to do.
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    /** "The assistant needs your input": a low-importance notification (no sound) with Open, which opens the assistant's session as a tab. Nothing is shown over the app lock (the launch shows the lock first). */
    fun postOwnerAsk(ask: OwnerAsk) {
        if (!permitted()) return
        val manager = manager ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_INPUT, Texts.get(R.string.assistant_channel_input), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); setSound(null, null); enableVibration(false)
        })
        val open = PendingIntent.getActivity(context, 2 + ask.id, Intent(context, AssistantShowActivity::class.java).putExtra(EXTRA_SESSION, ask.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, CHANNEL_INPUT)
            .setSmallIcon(R.drawable.ic_stat_jump)
            .setContentTitle(Texts.get(R.string.assistant_ask_notice))
            .setContentIntent(open)
            .addAction(0, Texts.get(R.string.assistant_ask_open), open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        runCatching { manager.notify(NOTIFICATION_ASK_BASE + ask.id, builder.build()) }
    }

    /** The ask about session [id] is answered or its session is gone: the notification goes. */
    fun cancelOwnerAsk(id: Int) { manager?.cancel(NOTIFICATION_ASK_BASE + id) }

    private fun permitted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL = "assistant"
        const val CHANNEL_WAITING = "assistant_waiting"
        const val NOTIFICATION_ID = 4
        const val CHANNEL_INPUT = "assistant_input"
        /** One notification per session asked about: the id of the notification is this plus the session's tab id. */
        const val NOTIFICATION_ASK_BASE = 1000
        const val EXTRA_SESSION = "session"

        private fun stopIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, AssistantStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )

        private fun cancelWaitsIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1, Intent(context, AssistantCancelWaitsReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
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

/** The waiting notification's "Cancel all": every wait ends, no turn starts. Not exported; idempotent. */
class AssistantCancelWaitsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as FrpJumpApp
        app.cancelAssistantWaits()
        // No conversation lives (the process restarted with the notification up): nothing else will remove it.
        if (app.assistantWaitCount.value == 0) context.getSystemService(NotificationManager::class.java)?.cancel(AssistantNotifier.NOTIFICATION_ID)
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

/**
 * The "needs your input" notification's Open: no UI. It sets the in-process request "open the assistant's session as a tab" and brings up
 * [MainActivity] with the launcher intent (no extras); the app lock shows first, as for any launch.
 */
class AssistantShowActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AssistantNotifier.EXTRA_SESSION, -1)
        if (id >= 0) (application as FrpJumpApp).showSessionRequest.value = id
        startActivity(
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
        finish()
    }
}
