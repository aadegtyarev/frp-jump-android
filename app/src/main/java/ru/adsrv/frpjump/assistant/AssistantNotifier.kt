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
 * permission nothing is posted.
 */
class AssistantNotifier(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun start(scope: CoroutineScope, activity: Flow<ChatActivity>, end: Flow<ChatEnd>, shown: Flow<Boolean>) {
        val tracker = AssistantNoticeTracker()
        scope.launch {
            combine(activity, end, shown) { a, e, s -> Triple(a, e, s) }
                .collect { (a, e, s) -> show(tracker.update(a, s, e, permitted())) }
        }
    }

    private var posted: AssistantNotice? = null

    private fun show(notice: AssistantNotice?) {
        if (notice == posted) return
        posted = notice
        if (notice == null) { manager?.cancel(NOTIFICATION_ID); return }
        post(notice)
    }

    private fun post(notice: AssistantNotice) {
        val manager = manager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, Texts.get(R.string.assistant_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
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
        (context.applicationContext as FrpJumpApp).stopAssistantAttempt()
    }
}

/**
 * The notification's tap: no UI. It sets the in-process request "open the chat" and brings up [MainActivity] with a plain intent
 * (no extras); the app lock shows first, as for any launch.
 */
class AssistantOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as FrpJumpApp).openAssistantRequest.value = true
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
