package ru.adsrv.frpjump.ui.connections

import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.connections.DeviceGroup
import ru.adsrv.frpjump.tunnel.TunnelRef

/** The saved UI filter stores alternating profile IDs and device names, never device names alone. */
internal fun inDeviceScope(scope: List<String>, group: DeviceGroup): Boolean =
    scope.isEmpty() || scope.chunked(2).any { it.size == 2 && it[0] == group.profile && it[1] == group.name }

/** A bulk action addresses distinct active tunnels, not inactive rows or other profiles. */
internal fun selectedTunnelRefs(rows: List<ConnectionRow>, selected: Set<String>): Set<TunnelRef> =
    rows.asSequence().filter { it.key in selected && it.active }
        .map { TunnelRef(it.profile, it.connection.device, it.connection.port) }.toSet()
