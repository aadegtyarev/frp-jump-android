package ru.adsrv.frpjump.ports

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Thrown when every port of the range is taken. */
class NoFreePortException(val range: IntRange) :
    Exception("No free local port in ${range.first}-${range.last}.")

/** A remote (device, port) within one profile: what a tunnel is listed and looked up by. */
fun localPortKey(device: String, port: Int) = "$device:$port"

/**
 * Key of the persisted local port map, shared by all profiles: one entry per
 * profile and remote (device, port), so two profiles never get the same
 * local port even for devices of the same name. Profile ids are UUIDs, so
 * the prefix up to the first `/` is always the profile.
 */
fun profilePortKey(profile: String, device: String, port: Int) = "$profile/${localPortKey(device, port)}"

/** The prefix every key of [profile] starts with. */
fun profilePortPrefix(profile: String) = "$profile/"

/**
 * Picks local ports for consumed grants and keeps them stable per
 * (profile, device, port), as the Linux agent's `_allocate_local_port` does per grant:
 * a dedicated range, ports already promised to another key skipped, the rest
 * probed by binding. Binding first is still a race with whatever grabs the
 * port before frpc does, but a range away from the ephemeral ports makes
 * that rare.
 *
 * A port is kept for its key even after a disconnect, so a bookmark to
 * 127.0.0.1:<port> stays valid. It is only re-picked when it is busy at the
 * moment the tunnel is (re)created: [assign] with `probeExisting = true`.
 * Never probe a port the running frpc holds, it would look busy and move.
 */
class LocalPortAllocator(
    private val range: IntRange,
    private val isBindable: (Int) -> Boolean = ::isLoopbackPortBindable,
) {
    /** Returns the port for [key], adding or replacing the entry in [ports]. */
    fun assign(ports: MutableMap<String, Int>, key: String, probeExisting: Boolean): Int {
        val existing = ports[key]
        if (existing != null && existing in range && (!probeExisting || isBindable(existing))) return existing
        val promised = ports.filterKeys { it != key }.values.toSet()
        val port = range.firstOrNull { it !in promised && isBindable(it) } ?: throw NoFreePortException(range)
        ports[key] = port
        return port
    }
}

fun isLoopbackPortBindable(port: Int): Boolean = try {
    ServerSocket().use { socket ->
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", port))
    }
    true
} catch (_: IOException) {
    false
}
