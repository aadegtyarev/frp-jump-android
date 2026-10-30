package ru.adsrv.frpjump.session

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
 * How often one profile polls, or null for not at all. The [current] one
 * (the profile the app shows) while the app is [visible]: every 2 s.
 * Otherwise, the app closed or another profile shown, only while the
 * profile has tunnels up (the foreground service keeps the process alive)
 * and only if the owner chose an interval ([backgroundMs]); otherwise
 * nothing runs. Never once its server has removed the profile.
 */
fun pollIntervalMs(visible: Boolean, current: Boolean, tunnelsUp: Boolean, backgroundMs: Long?, removed: Boolean): Long? = when {
    removed -> null
    visible && current -> FOREGROUND_POLL_MS
    tunnelsUp && backgroundMs != null -> backgroundMs
    else -> null
}

/**
 * One profile's poll loop, at [pollIntervalMs]. Each cycle runs [cycle]
 * (heartbeat + desired state, plus the device list while it is shown);
 * after failures it waits 10 s, then 30 s ([PollBackoff]). A change of
 * interval restarts the loop with an immediate cycle. Runs until [scope]
 * is cancelled (the profile is forgotten).
 */
class Poller(
    scope: CoroutineScope,
    /** Set by the activity while it is started; shared by all profiles. */
    private val visible: StateFlow<Boolean>,
    /** Whether this is the profile the app shows. */
    private val current: StateFlow<Boolean>,
    tunnelsUp: Flow<Boolean>,
    backgroundMs: Flow<Long?>,
    removed: StateFlow<Boolean>,
    private val cycle: suspend (shown: Boolean) -> Boolean,
) {
    init {
        scope.launch {
            combine(combine(visible, current, ::Pair), tunnelsUp, backgroundMs, removed) { (v, c), up, bg, gone ->
                pollIntervalMs(v, c, up, bg, gone)
            }
                .distinctUntilChanged()
                .collectLatest { interval -> if (interval != null) loop(interval) }
        }
    }

    private suspend fun loop(intervalMs: Long) {
        val backoff = PollBackoff()
        while (true) {
            val shown = visible.value && current.value
            val ok = cycle(shown)
            if (BuildConfig.DEBUG) Log.d(TAG, "poll every ${intervalMs / 1000} s, shown=$shown, ok=$ok")
            delay(backoff.next(ok, intervalMs))
        }
    }
}
