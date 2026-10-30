package ru.adsrv.frpjump.session

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.BuildConfig

private const val TAG = "Poller"

/** While the app is visible: heartbeat + desired state (and the device list when shown) this often. */
const val FOREGROUND_POLL_MS = 2_000L

/**
 * How often to poll, or null for not at all. Visible: every 2 s. In the
 * background only while tunnels are up (the foreground service keeps the
 * process alive) and only if the owner chose an interval ([backgroundMs]);
 * otherwise nothing runs. Never once the server has removed this phone.
 */
fun pollIntervalMs(visible: Boolean, tunnelsUp: Boolean, backgroundMs: Long?, removed: Boolean): Long? = when {
    removed -> null
    visible -> FOREGROUND_POLL_MS
    tunnelsUp && backgroundMs != null -> backgroundMs
    else -> null
}

/**
 * The app's one poll loop. Each cycle runs [cycle] (heartbeat + desired
 * state, plus the device list while it is shown); after failures it waits
 * 10 s, then 30 s ([PollBackoff]). A change of interval restarts the loop
 * with an immediate cycle.
 */
class Poller(
    scope: CoroutineScope,
    tunnelsUp: Flow<Boolean>,
    backgroundMs: Flow<Long?>,
    removed: StateFlow<Boolean>,
    private val cycle: suspend (visible: Boolean) -> Boolean,
) {
    /** Set by the activity while it is started. */
    val visible = MutableStateFlow(false)

    init {
        scope.launch {
            combine(visible, tunnelsUp, backgroundMs, removed, ::pollIntervalMs)
                .distinctUntilChanged()
                .collectLatest { interval -> if (interval != null) loop(interval) }
        }
    }

    private suspend fun loop(intervalMs: Long) {
        val backoff = PollBackoff()
        while (true) {
            val ok = cycle(visible.value)
            if (BuildConfig.DEBUG) Log.d(TAG, "poll every ${intervalMs / 1000} s, visible=${visible.value}, ok=$ok")
            delay(backoff.next(ok, intervalMs))
        }
    }
}
