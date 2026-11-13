package ru.adsrv.frpjump.lock

/**
 * When Settings › Unlock with fingerprint asks for authentication: on the
 * first start of the screen in a process (a cold start), and when it comes
 * back after more than the chosen interval out of sight (Settings › Lock
 * after; [timeoutMs] until [onStart] is given one; 0 is any absence). Times
 * are a monotonic clock in milliseconds (`SystemClock.elapsedRealtime()`).
 * Only the screen is locked: tunnels, the service and the notification's
 * actions go on.
 *
 * A window the app opened itself (picker, share, installer, browser) takes
 * the screen out of sight: such an absence counts only after
 * [HANDOFF_FLOOR_MS] at least, see [handoff].
 *
 * While the prompt is up the activity may stop and start again (the screen
 * lock is a separate activity on some versions): that is not "leaving".
 */
class LockPolicy(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    /** Content is hidden until [unlocked]. */
    var locked = false
        private set

    /** Unlocked at least once in this process: otherwise any start locks. */
    private var unlockedOnce = false

    /** When the screen went out of sight, unlocked; null while shown or locked. */
    private var leftAt: Long? = null

    /** A prompt is up: stops and starts are its own. */
    var authenticating = false
        private set

    /** The screen is in sight (between [onStart] and [onStop]). */
    var started = false
        private set

    /** When the app launched a window of its own; taken by the next stop, stale after [HANDOFF_WINDOW_MS]. */
    private var handoffAt: Long? = null

    /** The absence in [leftAt] began as a handoff. */
    private var leftByHandoff = false

    /**
     * The screen comes into sight; returns [locked]. [enabled]: the setting.
     * [afterMs]: the interval now chosen (0: any absence locks).
     */
    fun onStart(now: Long, enabled: Boolean, afterMs: Long = timeoutMs): Boolean {
        started = true
        if (!enabled) {
            locked = false
            leftAt = null
            return false
        }
        if (authenticating) return locked
        val left = leftAt
        leftAt = null
        if (engages(left, now, afterMs)) locked = true
        return locked
    }

    /** The decision of [onStart]: never unlocked in this process, or away for more than the limit. */
    private fun engages(left: Long?, now: Long, afterMs: Long): Boolean {
        val limit = if (leftByHandoff) maxOf(afterMs, HANDOFF_FLOOR_MS) else afterMs
        val away = left != null && (limit <= 0 || now - left > limit)
        return !unlockedOnce || away
    }

    /**
     * Whether a start at [now] would lock (what [onStart] decides, without
     * changing anything): already locked, or the lock engages on return.
     */
    fun wouldLockNow(now: Long, enabled: Boolean, afterMs: Long = timeoutMs): Boolean {
        if (!enabled) return false
        if (authenticating) return locked
        return locked || engages(leftAt, now, afterMs)
    }

    /** The screen goes out of sight. */
    fun onStop(now: Long) {
        started = false
        val handoff = handoffAt
        handoffAt = null
        if (authenticating || locked) return
        leftAt = now
        leftByHandoff = handoff != null && now - handoff <= HANDOFF_WINDOW_MS
    }

    /**
     * The app is launching a window of its own (picker, share, installer,
     * settings, browser, SSH client): the screen goes out of sight within
     * a few seconds. If it never does, the mark goes stale.
     */
    fun handoff(now: Long) {
        handoffAt = now
    }

    fun authStarted() {
        authenticating = true
    }

    /**
     * The prompt is gone; [success] unlocks. Ended with the screen out of
     * sight (the owner left while it was up), the absence counts from [now]:
     * its stop was the prompt's and was not counted. The prompt's own screen
     * (e.g. the PIN activity before Android 11) is like a window the app
     * opened: the 30 s floor applies, so Immediately does not lock again at
     * once and loop.
     */
    fun authFinished(success: Boolean, now: Long) {
        authenticating = false
        if (success) unlocked()
        if (!started && !locked) {
            leftAt = now
            leftByHandoff = true
        }
    }

    /**
     * The prompt's owner was destroyed before it answered, and the answer is
     * lost: the prompt is gone and nothing is unlocked. Locked stays locked,
     * and the next screen asks again.
     */
    fun authAbandoned(now: Long) = authFinished(success = false, now = now)

    /** Authenticated, also to turn the setting on: no prompt again until the next cold start or a long absence. */
    fun unlocked() {
        locked = false
        unlockedOnce = true
        leftAt = null
        leftByHandoff = false
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /** An absence that began as a handoff counts only after this long at least. */
        const val HANDOFF_FLOOR_MS = 30_000L

        /** The screen must go out of sight within this long of [handoff], or the mark is stale. */
        const val HANDOFF_WINDOW_MS = 5_000L
    }
}
