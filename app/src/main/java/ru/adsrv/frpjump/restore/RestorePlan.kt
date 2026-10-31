package ru.adsrv.frpjump.restore

/**
 * Tunnels still to bring back after a cold start: those [record]ed for a
 * [listed] profile that has none [up] yet. Null while that is not known
 * yet ([record] or the profiles not read). Profiles that could not be read
 * bring nothing back; profiles no longer listed are ignored.
 */
fun pendingRestore(
    record: Map<String, Int>?,
    profilesLoaded: Boolean,
    profilesFailed: Boolean,
    listed: Set<String>,
    up: Map<String, Int>,
): Int? = when {
    record == null -> null
    profilesFailed -> 0
    !profilesLoaded -> if (record.isEmpty()) 0 else null
    else -> record.filterKeys { it in listed && (up[it] ?: 0) == 0 }.values.sum()
}

/** After a reboot: the tunnels the record lists, worth starting the service for when above 0. */
fun tunnelsToRestoreOnBoot(record: Map<String, Int>): Int = record.values.filter { it > 0 }.sum()

/**
 * Whether the tunnels service stays: while tunnels are up, and while some
 * are still to come back (or that is not known yet, just after a restart).
 */
fun serviceNeeded(tunnels: Int, pending: Int?): Boolean = tunnels > 0 || pending == null || pending > 0
