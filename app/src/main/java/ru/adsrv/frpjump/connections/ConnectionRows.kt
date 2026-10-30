package ru.adsrv.frpjump.connections

import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelsState

/**
 * A connection with the state of its tunnel. Tunnels are per (profile,
 * device, port): every connection to the same port shows the same tunnel,
 * local port, progress and error.
 */
data class ConnectionRow(
    val profile: String,
    val connection: Connection,
    /** False for a tunnel up without any saved connection (a port deleted meanwhile): listed so it can be disconnected. */
    val saved: Boolean,
    val tunnel: Tunnel?,
    val pending: PendingOp?,
    val error: String?,
) {
    /** The tunnel's key within the profile ([localPortKey]). */
    val tunnelKey: String get() = localPortKey(connection.device, connection.port)

    /** Unique across profiles and connections. */
    val key: String get() = "${profile}/${connection.id}"

    val active: Boolean get() = tunnel != null
}

/** Id of the stand-in for a tunnel no saved connection names. */
private fun unsavedId(device: String, port: Int) = "unsaved:" + localPortKey(device, port)

/**
 * [connections] of [profile] (only [device]'s when given) joined with its
 * [tunnels], in the stored order, followed by tunnels up that no connection
 * names, so nothing that runs is hidden.
 */
fun connectionRows(profile: String, connections: List<Connection>, tunnels: TunnelsState, device: String? = null): List<ConnectionRow> {
    val byKey = tunnels.tunnels.associateBy { it.key }
    val mine = connections.filter { device == null || it.device == device }
    val named = mine.map { localPortKey(it.device, it.port) }.toSet()
    val rows = mine.map { c ->
        val key = localPortKey(c.device, c.port)
        ConnectionRow(profile, c, saved = true, tunnel = byKey[key], pending = tunnels.pending[key], error = tunnels.errors[key])
    }
    val unsaved = tunnels.tunnels
        .filter { (device == null || it.device == device) && it.key !in named }
        .map { t ->
            val c = Connection(unsavedId(t.device, t.port), t.device, t.port, PortKind.TCP, name = "")
            ConnectionRow(profile, c, saved = false, tunnel = t, pending = tunnels.pending[t.key], error = tunnels.errors[t.key])
        }
    return rows + unsaved
}

/** The Connections tab's order: active ones first, each group in profile order as [rows] come. */
fun activeFirst(rows: List<ConnectionRow>): Pair<List<ConnectionRow>, List<ConnectionRow>> = rows.partition { it.active }

/**
 * Whether removing [removed] (or moving it to another port) leaves its
 * tunnel without any connection of the same profile: then the tunnel is
 * disconnected too, rather than left running unseen.
 */
fun lastOnItsTunnel(removed: Connection, all: List<Connection>): Boolean =
    all.none { it.id != removed.id && it.device == removed.device && it.port == removed.port }

