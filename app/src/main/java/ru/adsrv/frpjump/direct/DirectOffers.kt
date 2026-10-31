package ru.adsrv.frpjump.direct

import ru.adsrv.frpjump.tunnel.DirectMode
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus

/** One "Direct didn't work: [names]" notification: a device whose tunnels fell back to the server. */
data class DirectOffer(val profile: String, val device: String, val names: String) {
    /** Stable per device: the notification's tag. */
    val key: String get() = offerKey(profile, device)
}

fun offerKey(profile: String, device: String) = "$profile/$device"

/** Whether [tunnel] fell back to the server after trying direct (and may offer the choice). */
fun Tunnel.directFailed(): Boolean =
    direct == DirectMode.ON && state.status == TunnelStatus.RELAYED && state.directFailed

/** Through the server because the owner kept its device so on this network. */
fun Tunnel.kept(): Boolean = direct == DirectMode.KEPT

/** What [DirectOffers.offers] decided: what to show now, and when to ask again. */
data class OfferList(val offers: List<DirectOffer>, val recheckInMs: Long?)

/**
 * Which devices offer "Retry direct" / "Keep through the server" in a
 * notification: those with a tunnel whose direct attempt failed (or
 * dropped and could not be redone), with direct on for it; one offer per
 * device, naming its failed tunnels. A tunnel kept through the server, or
 * with the setting off, never gets here ([Tunnel.direct]).
 *
 * A device is offered only once it has had a failed tunnel for
 * [delayMs]: a punch that is confirmed just after the 10 s window would
 * otherwise flash a notification and take it back.
 *
 * An offer the owner swiped away (or answered) stays away until its
 * episode ends: no tunnel of the device is failed any more; the next
 * failure offers again.
 */
class DirectOffers(private val delayMs: Long = OFFER_DELAY_MS) {
    private val dismissed = mutableSetOf<String>()
    private val failingSince = mutableMapOf<String, Long>()

    /**
     * The offers to show at [now] for [tunnels] (by profile id); [name]
     * names a tunnel as the owner knows it (its connections' names).
     */
    @Synchronized
    fun offers(tunnels: Map<String, List<Tunnel>>, now: Long, name: (profile: String, tunnel: Tunnel) -> String): OfferList {
        val failed = tunnels.flatMap { (profile, list) -> list.filter { it.directFailed() }.map { profile to it } }
            .groupBy({ (profile, t) -> offerKey(profile, t.device) }, { it })
        dismissed.retainAll(failed.keys)
        failingSince.keys.retainAll(failed.keys)
        failed.keys.forEach { failingSince.getOrPut(it) { now } }
        val due = failed.filterKeys { it !in dismissed && now - failingSince.getValue(it) >= delayMs }
        val waiting = failed.keys.filter { it !in dismissed && it !in due }.map { delayMs - (now - failingSince.getValue(it)) }
        val offers = due.map { (_, list) ->
            val (profile, first) = list.first()
            DirectOffer(profile, first.device, list.map { (p, t) -> name(p, t) }.distinct().joinToString(", "))
        }
        return OfferList(offers, waiting.minOrNull())
    }

    /** Swiped away, or answered: not offered again in this episode. */
    @Synchronized
    fun dismiss(key: String) {
        dismissed += key
    }

    companion object {
        const val OFFER_DELAY_MS = 3_000L
    }
}
