package ru.adsrv.frpjump.devices

/**
 * The devices whose name contains [query], ignoring case and the spaces
 * around it, in the list's order; all of them for a blank query. Names
 * only: connection names ("Web", "SSH") repeat across devices and would
 * match most of the list.
 */
fun filterDevices(devices: List<DeviceRow>, query: String): List<DeviceRow> {
    val wanted = query.trim()
    if (wanted.isEmpty()) return devices
    return devices.filter { it.name.contains(wanted, ignoreCase = true) }
}
