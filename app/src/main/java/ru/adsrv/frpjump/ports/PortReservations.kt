package ru.adsrv.frpjump.ports

import java.io.IOException

/** Immutable allocation-time view of all published profiles, not a second persistent pin database. */
data class PortReservations(
    val profiles: Set<String>,
    val pinned: Map<String, Int>,
    /** Existing frpc allocations still held, by profile/device/port key. */
    val held: Map<String, Int> = emptyMap(),
)

class PortReservationConflict(val port: Int) : IOException("Conflicting local-port reservation: $port")

/** Validate first, then restore derived reservations before ANY profile allocates an Auto port. */
fun restorePinReservations(profile: String, ports: MutableMap<String, Int>, snapshot: PortReservations) {
    require(profile in snapshot.profiles) { "Profile is no longer available for port allocation" }
    val claimed = HashMap<Int, String>()
    for ((key, port) in snapshot.held) {
        val owner = claimed.put(port, key)
        if (owner != null && owner != key) throw PortReservationConflict(port)
    }
    for ((key, port) in snapshot.pinned) {
        val heldHere = snapshot.held[key]
        if (heldHere != null && heldHere != port) throw PortReservationConflict(port)
        val owner = claimed.put(port, key)
        if (owner != null && owner != key) throw PortReservationConflict(port)
    }
    // Deleted profiles cannot leave a recovered pin in the map forever (including a concurrent Forget).
    ports.keys.removeAll { it.substringBefore('/') !in snapshot.profiles }
    ports.putAll(snapshot.held)
    reservePins(ports, snapshot.pinned)
}
