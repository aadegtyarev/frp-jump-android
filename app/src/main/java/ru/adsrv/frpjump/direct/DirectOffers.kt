package ru.adsrv.frpjump.direct

import ru.adsrv.frpjump.tunnel.DirectMode
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus

/** A "Direct didn't work: [name]" notification for one tunnel. */
data class DirectOffer(val profile: String, val device: String, val port: Int, val grantId: String, val name: String) {
    /** Stable per tunnel: the notification's tag. */
    val key: String get() = "$profile/$device/$port"
}

/** Whether [tunnel] fell back to the server after trying direct (and may offer the choice). */
fun Tunnel.directFailed(): Boolean =
    direct == DirectMode.ON && state.status == TunnelStatus.RELAYED && state.directFailed

/**
 * Which tunnels offer "Retry direct" / "Keep through the server" in a
 * notification: those whose direct attempt failed (or dropped and could
 * not be redone), with direct on for them. A tunnel kept through the
 * server, or with the setting off, never gets here ([Tunnel.direct]).
 *
 * An offer the owner swiped away stays away until its episode ends: the
 * tunnel goes direct, tries again or goes away; the next failure offers
 * again.
 */
class DirectOffers {
    private val dismissed = mutableSetOf<String>()

    /**
     * The offers to show for [tunnels] (by profile id); [name] names a
     * tunnel as the owner knows it (its connection's name).
     */
    @Synchronized
    fun offers(tunnels: Map<String, List<Tunnel>>, name: (profile: String, tunnel: Tunnel) -> String): List<DirectOffer> {
        val failed = tunnels.flatMap { (profile, list) -> list.filter { it.directFailed() }.map { profile to it } }
        dismissed.retainAll(failed.map { it.second.grantId }.toSet())
        return failed.filter { (_, t) -> t.grantId !in dismissed }.map { (profile, t) ->
            DirectOffer(profile, t.device, t.port, t.grantId, name(profile, t))
        }
    }

    /** Swiped away, or acted on: not offered again in this episode. */
    @Synchronized
    fun dismiss(grantId: String) {
        dismissed += grantId
    }
}
