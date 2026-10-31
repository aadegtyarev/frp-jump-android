package ru.adsrv.frpjump.mqtt

/** Why the tree snapshot stopped. */
enum class SnapshotEnd {
    /** [SnapshotRule.QUIET_MS] without a message: the broker sent all it had. */
    QUIET,

    /** [SnapshotRule.MAX_MS] after the SUBACK, the broker still sending: some topics may be missing. */
    TIMED_OUT,

    /** The session's topic limit: a new topic was dropped ([TopicTree.limitReached]). */
    LIMIT,
}

/**
 * When the snapshot (`#` subscribed, then unsubscribed) ends: at the first
 * of [QUIET_MS] without any message counted from the SUBACK, [MAX_MS] after
 * the SUBACK, or the topic limit. Times are milliseconds on one clock.
 */
object SnapshotRule {
    const val QUIET_MS = 1_000L
    const val MAX_MS = 10_000L

    /**
     * Why it has ended by [now], or null while it goes on. [lastMessageAt]
     * is null before any message; [limitReached] is [TopicTree.limitReached].
     */
    fun end(subackAt: Long, lastMessageAt: Long?, now: Long, limitReached: Boolean): SnapshotEnd? {
        if (limitReached) return SnapshotEnd.LIMIT
        val quietAt = quietAt(subackAt, lastMessageAt)
        val timeoutAt = subackAt + MAX_MS
        return when {
            now >= quietAt && quietAt <= timeoutAt -> SnapshotEnd.QUIET
            now >= timeoutAt -> SnapshotEnd.TIMED_OUT
            else -> null
        }
    }

    /** When to look again with no message arriving meanwhile. */
    fun nextCheckAt(subackAt: Long, lastMessageAt: Long?): Long = minOf(quietAt(subackAt, lastMessageAt), subackAt + MAX_MS)

    private fun quietAt(subackAt: Long, lastMessageAt: Long?): Long = maxOf(subackAt, lastMessageAt ?: subackAt) + QUIET_MS
}
