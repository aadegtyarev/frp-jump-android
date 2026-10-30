package ru.adsrv.frpjump.tunnel

import ru.adsrv.frpjump.api.ConsumedGrant
import ru.adsrv.frpjump.ports.LocalPortAllocator
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.ports.profilePortKey

/** A consumed grant and the local port it is served on. */
data class PlannedTunnel(val grant: ConsumedGrant, val localPort: Int) {
    val key get() = localPortKey(grant.exposerDeviceName, grant.targetPort)
    fun visitor() = VisitorSpec(grant.grantId, grant.secret, localPort)
}

/**
 * Desired state is the source of truth: one tunnel per consumed grant, on
 * the local port kept for its ([profile], device, port) in [ports], the map
 * shared by all profiles (updated in place).
 * Ports in [heldByFrpc] (the running frpc listens on them) are trusted as
 * they are; every other port is probed before use. Grants come back in the
 * server's order, so an unchanged desired state yields an unchanged config.
 */
fun planTunnels(
    profile: String,
    consumed: List<ConsumedGrant>,
    ports: MutableMap<String, Int>,
    allocator: LocalPortAllocator,
    heldByFrpc: Set<Int>,
): List<PlannedTunnel> = consumed.map { grant ->
    val key = profilePortKey(profile, grant.exposerDeviceName, grant.targetPort)
    val existing = ports[key]
    val port = allocator.assign(ports, key, probeExisting = existing == null || existing !in heldByFrpc)
    PlannedTunnel(grant, port)
}
