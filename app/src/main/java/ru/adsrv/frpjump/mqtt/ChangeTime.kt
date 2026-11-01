package ru.adsrv.frpjump.mqtt

/** When something last changed, as far as the session knows. */
sealed interface LastChange {
    /** Seen changing in this session, at [ms] (milliseconds since the epoch). */
    data class At(val ms: Long) : LastChange

    /** Only values delivered as retained on subscribe: set before the session, time unknown ("before connecting"). */
    data object BeforeSession : LastChange
}

/**
 * A row's change: the newest change of [node] or below it seen in this
 * session; [LastChange.BeforeSession] when it holds values but none changed
 * since they arrived; null when it holds nothing (the root's `$SYS` before
 * it is opened).
 */
fun lastChange(node: TopicNode): LastChange? {
    val seen = maxOf(node.value?.changedAt ?: 0L, node.newestChangeAt)
    return when {
        seen > 0 -> LastChange.At(seen)
        node.value != null || node.topicsBelow > 0 -> LastChange.BeforeSession
        else -> null
    }
}

/**
 * A branch card's "Last change" line, or null when it would only repeat
 * the time shown with the node's own value (the newest change is that
 * value's, or neither is known) or the node holds nothing.
 */
fun branchChangeLine(node: TopicNode): LastChange? {
    val value = node.value
    val newest = node.newestChangeAt.takeIf { it > 0 }
    if (value != null && newest == value.changedAt) return null
    if (value != null && newest == null) return null
    return lastChange(node)
}
