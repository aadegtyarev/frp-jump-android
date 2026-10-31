package ru.adsrv.frpjump.tunnel

import ru.adsrv.frpjump.api.ConsumedGrant
import ru.adsrv.frpjump.ports.LocalPortAllocator
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.ports.profilePortKey
import ru.adsrv.frpjump.ports.profilePortPrefix
import ru.adsrv.frpjump.ports.reservePins

/** A consumed grant and the local port it is served on; [pinned]: the owner fixed it, it never moves. */
data class PlannedTunnel(val grant: ConsumedGrant, val localPort: Int, val pinned: Boolean = false) {
    val key get() = localPortKey(grant.exposerDeviceName, grant.targetPort)
    fun visitor(direct: Boolean = true, attempt: Int = 0) = VisitorSpec(grant.grantId, grant.secret, localPort, direct, attempt)
}

/**
 * Desired state is the source of truth: one tunnel per consumed grant, on
 * the local port kept for its ([profile], device, port) in [ports], the map
 * shared by all profiles (updated in place).
 * Ports in [heldByFrpc] (the running frpc listens on them) are trusted as
 * they are; every other port is probed before use. Grants come back in the
 * server's order, so an unchanged desired state yields an unchanged config.
 * [pinned] (by [ru.adsrv.frpjump.ports.localPortKey]) are this profile's
 * pinned ports: reserved first, used as they are, never probed or moved.
 */
fun planTunnels(
    profile: String,
    consumed: List<ConsumedGrant>,
    ports: MutableMap<String, Int>,
    allocator: LocalPortAllocator,
    heldByFrpc: Set<Int>,
    pinned: Map<String, Int> = emptyMap(),
): List<PlannedTunnel> {
    reservePins(ports, pinned.mapKeys { (key, _) -> profilePortPrefix(profile) + key })
    return consumed.map { grant ->
        val local = localPortKey(grant.exposerDeviceName, grant.targetPort)
        val key = profilePortKey(profile, grant.exposerDeviceName, grant.targetPort)
        val pin = pinned[local]
        if (pin != null) {
            PlannedTunnel(grant, pin, pinned = true)
        } else {
            val existing = ports[key]
            PlannedTunnel(grant, allocator.assign(ports, key, probeExisting = existing == null || existing !in heldByFrpc))
        }
    }
}
