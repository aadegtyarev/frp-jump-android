package ru.adsrv.frpjump.connections

import ru.adsrv.frpjump.devices.DeviceRow

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

    val active: Boolean get() = rows.any { it.active }

    /** Known to be offline: its connections that are down cannot be connected now. */
    val offline: Boolean get() = device != null && !device.online
}

/**
 * [rows] grouped by (profile, device): devices with an active connection
 * first, then online ones, then the rest (offline or not known), each part
 * by device name, ignoring case (profiles in [rows]' order for one name).
 * Within a group the rows keep their order. [devices] is each profile's
 * device list, null while not read.
 */
fun groupByDevice(rows: List<ConnectionRow>, devices: Map<String, List<DeviceRow>?>): List<DeviceGroup> {
    val profileOrder = rows.map { it.profile }.distinct()
    return rows.groupBy { it.profile to it.connection.device }
        .map { (key, list) ->
            val (profile, name) = key
            DeviceGroup(profile, name, devices[profile]?.firstOrNull { it.name == name }, list)
        }
        .sortedWith(
            compareBy<DeviceGroup> { rank(it) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { profileOrder.indexOf(it.profile) },
        )
}

private fun rank(group: DeviceGroup): Int = when {
    group.active -> 0
    group.device?.online == true -> 1
    else -> 2
}

/**
 * The groups matching [query], ignoring case and the spaces around it: a
 * device whose name contains it with all its connections, otherwise only
 * its connections whose [label] contains it. All of them for a blank query.
 */
fun filterGroups(groups: List<DeviceGroup>, query: String, label: (ConnectionRow) -> String): List<DeviceGroup> {
    val wanted = query.trim()
    if (wanted.isEmpty()) return groups
    return groups.mapNotNull { group ->
        if (group.name.contains(wanted, ignoreCase = true)) return@mapNotNull group
        group.rows.filter { label(it).contains(wanted, ignoreCase = true) }
            .takeIf { it.isNotEmpty() }
            ?.let { group.copy(rows = it) }
    }
}

/** From how many connections the tab offers search: fewer fit on a screen. */
const val CONNECTIONS_SEARCH_FROM = 6
