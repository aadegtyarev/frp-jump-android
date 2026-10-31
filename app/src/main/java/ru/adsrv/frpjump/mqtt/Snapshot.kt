package ru.adsrv.frpjump.mqtt

/** Why the tree snapshot stopped. */
enum class SnapshotEnd {
    /** [SnapshotRule.QUIET_MS] without a snapshot message: the broker sent all it had. */
    QUIET,

    /** [SnapshotRule.MAX_MS] after the SUBACK, new topics still coming: some may be missing. */
    TIMED_OUT,

    /** The session's topic limit: a new topic was dropped ([TopicTree.limitReached]). */
    LIMIT,
}

/**
 * When the snapshot (`#` subscribed, then unsubscribed) ends: at the first
 * of [QUIET_MS] without a snapshot message counted from the SUBACK, [MAX_MS]
 * after the SUBACK, or the topic limit. Times are milliseconds on one clock.
 *
 * A snapshot message is one that fills the tree: a topic seen for the first
 * time, or a known retained value re-sent unchanged (the re-delivery after a
 * reconnect). New values of known topics do not count: a live controller
 * sends them all the time, and counting them would never let the tree go
 * quiet.
 */
object SnapshotRule {
    const val QUIET_MS = 1_000L
    const val MAX_MS = 10_000L

    /**
     * Why it has ended by [now], or null while it goes on. [lastSnapshotMessageAt]
     * is null before any snapshot message; [limitReached] is [TopicTree.limitReached].
     */
    fun end(subackAt: Long, lastSnapshotMessageAt: Long?, now: Long, limitReached: Boolean): SnapshotEnd? {
        if (limitReached) return SnapshotEnd.LIMIT
        val quietAt = quietAt(subackAt, lastSnapshotMessageAt)
        val timeoutAt = subackAt + MAX_MS
        return when {
            now >= quietAt && quietAt <= timeoutAt -> SnapshotEnd.QUIET
            now >= timeoutAt -> SnapshotEnd.TIMED_OUT
            else -> null
        }
    }

    /** When to look again with no message arriving meanwhile. */
    fun nextCheckAt(subackAt: Long, lastSnapshotMessageAt: Long?): Long = minOf(quietAt(subackAt, lastSnapshotMessageAt), subackAt + MAX_MS)

    private fun quietAt(subackAt: Long, lastSnapshotMessageAt: Long?): Long = maxOf(subackAt, lastSnapshotMessageAt ?: subackAt) + QUIET_MS
}
