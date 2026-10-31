package ru.adsrv.frpjump.restore

/**
 * Tunnels still to bring back after a cold start: those [record]ed for a
 * [listed] profile that has none [up] yet. Null while that is not known
 * yet: [record] not read, or [listed] null (the profiles not read yet, or
 * not readable just now and tried again). Profiles no longer listed, or
 * that could not be read at all (listed empty), bring nothing back.
 */
fun pendingRestore(record: Map<String, Int>?, listed: Set<String>?, up: Map<String, Int>): Int? = when {
    record == null -> null
    record.isEmpty() -> 0
    listed == null -> null
    else -> record.filterKeys { it in listed && (up[it] ?: 0) == 0 }.values.sum()
}

/** After a reboot: the tunnels the record lists, worth starting the service for when above 0. */
fun tunnelsToRestoreOnBoot(record: Map<String, Int>): Int = record.values.filter { it > 0 }.sum()

/**
 * Whether the tunnels service stays: while tunnels are up, and while some
 * are still to come back (or that is not known yet, just after a restart).
 */
fun serviceNeeded(tunnels: Int, pending: Int?): Boolean = tunnels > 0 || pending == null || pending > 0
