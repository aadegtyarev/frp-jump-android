package ru.adsrv.frpjump.session

/**
 * The wait before the next poll: the normal interval after a success, then
 * 10 s and 30 s (and 30 s on) after consecutive failures, back to normal on
 * the first success.
 */
class PollBackoff(private val steps: List<Long> = listOf(10_000L, 30_000L)) {
    private var failures = 0

    fun next(succeeded: Boolean, intervalMs: Long): Long {
        if (succeeded) {
            failures = 0
            return intervalMs
        }
        return steps[minOf(failures++, steps.lastIndex)]
    }
}
