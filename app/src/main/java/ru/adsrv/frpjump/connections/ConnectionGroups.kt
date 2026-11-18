package ru.adsrv.frpjump.connections

import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.devices.searchMatches

/**
 * One device's connections on the Connections tab, with the device as its
 * profile's list last had it ([device] null: not in that list, or the list
 * not read yet).
 */
data class DeviceGroup(
    val profile: String,
    val name: String,
    val device: DeviceRow?,
    val rows: List<ConnectionRow>,
) {
    /** Unique across profiles. */
    val key: String get() = "$profile/$name"

    /** Known to be offline now ([knownOffline]): its connections that are down cannot be connected. */
    val offline: Boolean get() = knownOffline(device)
}

/**
 * Whether [device] is offline as of a fresh answer of the server. An old
 * list (no answer since, or not checked yet since the start) may be wrong,
 * and not being listed or a list not read says nothing: connecting is left
 * to try then.
 */
fun knownOffline(device: DeviceRow?): Boolean = device != null && !device.online && !device.stale && !device.checking

/** Whether [row]'s Connect (and the tap on its card) is off: down, on a device [offline] now. Up, it stays usable. */
fun connectBlocked(row: ConnectionRow, offline: Boolean): Boolean = offline && !row.active

/**
 * The name a card shows over the port: the owner's own, unless it only says
 * the kind again ([kindWord], case ignored), or none.
 */
fun connectionTitle(connection: Connection, kindWord: String): String? =
    connection.customName?.trim()?.takeIf { it.isNotEmpty() && !it.equals(kindWord, ignoreCase = true) }

/**
 * [rows] grouped by (profile, device), a stable order: devices by name,
 * ignoring case, then profiles in [rows]' order for one name. Neither a
 * tunnel's state nor a device's online state moves a group. Within a group
 * the rows keep their order. [devices] is each profile's device list, null
 * while not read.
 */
fun groupByDevice(rows: List<ConnectionRow>, devices: Map<String, List<DeviceRow>?>): List<DeviceGroup> {
    val profileOrder = rows.map { it.profile }.distinct()
    return rows.groupBy { it.profile to it.connection.device }
        .map { (key, list) ->
            val (profile, name) = key
            DeviceGroup(profile, name, devices[profile]?.firstOrNull { it.name == name }, list)
        }
        // Favourites come first on the Tunnels tab, but as a group of their own over these ([tunnelsView]): a device's group never holds one.
        .sortedWith(
            compareBy<DeviceGroup, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { profileOrder.indexOf(it.profile) },
        )
}

/**
 * The groups matching [query], ignoring the spaces around it and case as
 * Devices search does ([searchMatches]): a device whose name or label
 * contains it with all its connections, otherwise only its connections
 * whose [label] contains it. All of them for a blank query.
 */
fun filterGroups(groups: List<DeviceGroup>, query: String, label: (ConnectionRow) -> String): List<DeviceGroup> {
    val wanted = query.trim()
    if (wanted.isEmpty()) return groups
    return groups.mapNotNull { group ->
        if (searchMatches(group.name, wanted) || searchMatches(group.device?.label, wanted)) return@mapNotNull group
        group.rows.filter { searchMatches(label(it), wanted) }
            .takeIf { it.isNotEmpty() }
            ?.let { group.copy(rows = it) }
    }
}

/** The tunnels that are on: those with a tunnel (up or coming up) and the ones still being restored (they have no row yet). */
fun onTunnelCount(groups: List<DeviceGroup>, restoring: Int): Int = groups.sumOf { g -> g.rows.count { it.active } } + restoring.coerceAtLeast(0)

/** A favourite row of the first group, with the device group it comes from (its name for "· device", its online state). */
data class FavoriteRow(val group: DeviceGroup, val row: ConnectionRow)

/**
 * What the Tunnels tab lists, for the filter "only on" ([onlyOn], the choice) and the search [query]: with the filter in effect only the
 * rows with a tunnel, searched within them as everywhere ([filterGroups]). The filter is not in effect while no row is on (at 0, or only
 * tunnels still being restored, which have no row to show): then all of them are listed. [inAll] is set when the scope found nothing for the
 * query but all tunnels have matches: how many rows, for the line that offers to turn the filter off.
 *
 * What is listed is split once: [favorites] are the saved connections with the flag, in the order of their device groups (device name, then
 * profile order) and the stored order within one; [groups] are the other rows by device as [groupByDevice] orders them, a device left out when
 * all its rows are favourites. A row is in one place only, and the filter and the search apply to both alike.
 */
data class TunnelsView(val favorites: List<FavoriteRow>, val groups: List<DeviceGroup>, val filtered: Boolean, val inAll: Int?) {
    /** Nothing is listed. */
    val isEmpty: Boolean get() = favorites.isEmpty() && groups.isEmpty()
}

fun tunnelsView(groups: List<DeviceGroup>, onlyOn: Boolean, query: String, label: (ConnectionRow) -> String): TunnelsView {
    val filtered = onlyOn && groups.any { g -> g.rows.any { it.active } }
    val scope = if (filtered) groups.mapNotNull { g -> g.rows.filter { it.active }.takeIf { it.isNotEmpty() }?.let { g.copy(rows = it) } } else groups
    val shown = filterGroups(scope, query, label)
    val inAll = if (filtered && shown.isEmpty() && query.isNotBlank()) {
        filterGroups(groups, query, label).sumOf { it.rows.size }.takeIf { it > 0 }
    } else null
    val favorites = shown.flatMap { g -> g.rows.filter { it.saved && it.connection.favorite }.map { FavoriteRow(g, it) } }
    val rest = shown.mapNotNull { g -> g.rows.filterNot { it.saved && it.connection.favorite }.takeIf { it.isNotEmpty() }?.let { g.copy(rows = it) } }
    return TunnelsView(favorites, rest, filtered, inAll)
}
