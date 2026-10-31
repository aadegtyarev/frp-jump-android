package ru.adsrv.frpjump.lock

/**
 * When Settings › Unlock with fingerprint asks for authentication: on the
 * first start of the screen in a process (a cold start), and when it comes
 * back after more than [timeoutMs] out of sight. Times are a monotonic
 * clock in milliseconds (`SystemClock.elapsedRealtime()`). Only the screen
 * is locked: tunnels, the service and the notification's actions go on.
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
    private var authenticating = false

    /** The screen is in sight (between [onStart] and [onStop]). */
    private var started = false

    /** The screen comes into sight; returns [locked]. [enabled]: the setting. */
    fun onStart(now: Long, enabled: Boolean): Boolean {
        started = true
        if (!enabled) {
            locked = false
            leftAt = null
            return false
        }
        if (authenticating) return locked
        val left = leftAt
        leftAt = null
        if (!unlockedOnce || (left != null && now - left > timeoutMs)) locked = true
        return locked
    }

    /** The screen goes out of sight. */
    fun onStop(now: Long) {
        started = false
        if (authenticating || locked) return
        leftAt = now
    }

    fun authStarted() {
        authenticating = true
    }

    /**
     * The prompt is gone; [success] unlocks. Ended with the screen out of
     * sight (the owner left while it was up), the absence counts from [now]:
     * its stop was the prompt's and was not counted.
     */
    fun authFinished(success: Boolean, now: Long) {
        authenticating = false
        if (success) unlocked()
        if (!started && !locked) leftAt = now
    }

    /** Authenticated, also to turn the setting on: no prompt again until the next cold start or a long absence. */
    fun unlocked() {
        locked = false
        unlockedOnce = true
        leftAt = null
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
