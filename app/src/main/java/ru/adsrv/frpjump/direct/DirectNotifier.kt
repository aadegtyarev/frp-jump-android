package ru.adsrv.frpjump.direct

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.MainActivity
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.profiles.Profiles
import ru.adsrv.frpjump.tunnel.Tunnel

/**
 * Posts "Direct didn't work: <names>" with "Retry direct" and "Keep through
 * the server" for every device [DirectOffers] lists, and takes it back
 * when its tunnels go direct, retry, are kept or go away. Its own
 * channel, apart from the foreground service's. Lives in the app process,
 * which the foreground service keeps alive while tunnels are up, so the
 * actions ([DirectActionReceiver]) work with the app closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectNotifier(private val context: Context, private val profiles: Profiles) {
    private val offers = DirectOffers()
    /** Bumped to look again: an answer, or an offer's delay ran out. */
    private val ticks = MutableStateFlow(0)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var shown: Map<String, DirectOffer> = emptyMap()
    private var recheck: Job? = null

    fun start(scope: CoroutineScope) {
        // Left over from a process that died with them shown: nothing backs them any more.
        runCatching {
            manager.activeNotifications.filter { it.id == NOTIFICATION_ID }.forEach { manager.cancel(it.tag, it.id) }
        }
        scope.launch {
            combine(tunnelsAndNames(), ticks) { (tunnels, connections), _ ->
                offers.offers(tunnels, SystemClock.elapsedRealtime()) { profile, t -> nameOf(connections[profile].orEmpty(), t) }
            }.distinctUntilChanged().collect { list ->
                show(list.offers)
                recheck?.cancel()
                recheck = list.recheckInMs?.let { wait ->
                    scope.launch {
                        delay(wait)
                        ticks.update { it + 1 }
                    }
                }
            }
        }
    }

    /** The owner swiped it away or answered it: take it back now, and not again in this episode. */
    fun dismiss(profile: String, device: String, cancel: Boolean) {
        val key = offerKey(profile, device)
        offers.dismiss(key)
        if (cancel) manager.cancel(key, NOTIFICATION_ID)
        ticks.update { it + 1 }
    }

    private fun show(list: List<DirectOffer>) {
        val next = list.associateBy { it.key }
        (shown.keys - next.keys).forEach { manager.cancel(it, NOTIFICATION_ID) }
        if (next.isNotEmpty()) ensureChannel()
        next.values.filter { shown[it.key] != it }.forEach { manager.notify(it.key, NOTIFICATION_ID, notification(it)) }
        shown = next
    }

    private fun tunnelsAndNames(): Flow<Pair<Map<String, List<Tunnel>>, Map<String, List<Connection>>>> =
        profiles.state.map { it.runtimes }.distinctUntilChanged().flatMapLatest { runtimes ->
            if (runtimes.isEmpty()) {
                flowOf(emptyMap<String, List<Tunnel>>() to emptyMap())
            } else {
                val ids = runtimes.keys.toList()
                combine(ids.map { id -> runtimes.getValue(id).let { r -> combine(r.tunnels.state, r.connections.connections) { t, c -> t.tunnels to c } } }) { all ->
                    ids.zip(all).associate { (id, p) -> id to p.first } to ids.zip(all).associate { (id, p) -> id to p.second }
                }
            }
        }

    /** The names of the connections on the tunnel ("Web, homeui"), or "device:port" when none names it. */
    private fun nameOf(connections: List<Connection>, t: Tunnel): String =
        connections.filter { it.device == t.device && it.port == t.port }.map { it.name }.distinct().joinToString(", ")
            .ifEmpty { "${t.device}:${t.port}" }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.direct_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.direct_channel_description)
            },
        )
    }

    private fun notification(offer: DirectOffer): Notification {
        val code = offer.key.hashCode()
        val open = PendingIntent.getActivity(
            context,
            code,
            MainActivity.openDevice(context, offer.profile, offer.device),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.direct_failed_title, offer.names))
            .setContentText(context.getString(R.string.direct_failed_text, offer.device))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.direct_failed_text, offer.device)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setDeleteIntent(DirectActionReceiver.intent(context, DirectActionReceiver.ACTION_DISMISS, offer))
            .addAction(0, context.getString(R.string.retry_direct), DirectActionReceiver.intent(context, DirectActionReceiver.ACTION_RETRY, offer))
            .addAction(0, context.getString(R.string.keep_through_server), DirectActionReceiver.intent(context, DirectActionReceiver.ACTION_KEEP, offer))
            .build()
    }

    companion object {
        const val CHANNEL = "direct"
        const val NOTIFICATION_ID = 2
    }
}
