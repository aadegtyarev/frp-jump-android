package ru.adsrv.frpjump.devices

import ru.adsrv.frpjump.tunnel.Tunnel

/** The ports this phone has tunnels open to, by device name: ascending, each once. */
fun tunnelPorts(tunnels: List<Tunnel>): Map<String, List<Int>> =
    tunnels.groupBy({ it.device }, { it.port }).mapValues { (_, ports) -> ports.distinct().sorted() }

/** Devices with tunnels open ([ports]) first; otherwise the list's own order. */
fun tunnelsFirst(devices: List<DeviceRow>, ports: Map<String, List<Int>>): List<DeviceRow> =
    devices.sortedBy { if (ports[it.name].isNullOrEmpty()) 1 else 0 }

/** The ports a row has room for: the first [max], and how many more are left out. */
fun portsShown(ports: List<Int>, max: Int = 4): Pair<List<Int>, Int> =
    ports.take(max) to (ports.size - max).coerceAtLeast(0)
