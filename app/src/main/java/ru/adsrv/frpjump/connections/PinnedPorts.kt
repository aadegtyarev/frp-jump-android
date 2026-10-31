package ru.adsrv.frpjump.connections

import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ports.localPortKey

/** A saved connection's pinned local port, as the checks see every profile's. */
data class PortPin(val profile: String, val connectionId: String, val device: String, val port: Int, val localPort: Int)

/** A local port a tunnel of [profile] to [device]:[port] listens on now. */
data class HeldPort(val profile: String, val device: String, val port: Int, val localPort: Int)

/** The lowest port a connection may pin: below it Android lets no app listen. */
const val MIN_PINNED_PORT = 1024

/** Why a typed local port cannot be pinned; [other] names who has it ("wb:22"), or the other pin. */
data class LocalPortProblem(val reason: Reason, val other: String? = null) {
    enum class Reason {
        /** Not a number in 1024–65535. */
        OUT_OF_RANGE,

        /** Another connection (any device, any profile) pins it. */
        PINNED_ELSEWHERE,

        /** Another connection to the same device and port pins another one: they share one tunnel. */
        OTHER_PIN_ON_TUNNEL,

        /** Another tunnel listens on it now. */
        IN_USE,

        /**
         * This device and port's own tunnel is up on another local port
         * ([other]): pinning would move it and cut what runs through it.
         */
        TUNNEL_UP_OTHER_PORT,
    }
}

/** A typed local port: null for "Auto" (blank), [Int.MIN_VALUE] when it is not a port at all. */
fun parseLocalPort(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return null
    if (t.length > 5 || !t.all { it in '0'..'9' }) return Int.MIN_VALUE
    return t.toInt()
}

/**
 * What stops [text] from being pinned by the connection [editingId] (null:
 * a new one) of [profile] to [device]:[port]: a number out of range, a pin
 * of another connection anywhere, another pin on the same tunnel, a
 * tunnel that listens on it now, or this device and port's own tunnel up
 * on another port (pinning would move it under its sessions). Connections to the same device and port
 * share one tunnel, so the same pin there is fine. Blank ("Auto") is fine.
 */
fun localPortProblem(
    text: String,
    profile: String,
    device: String,
    port: Int?,
    editingId: String?,
    pins: List<PortPin>,
    held: List<HeldPort>,
): LocalPortProblem? {
    val wanted = parseLocalPort(text) ?: return null
    if (wanted !in MIN_PINNED_PORT..65535) return LocalPortProblem(LocalPortProblem.Reason.OUT_OF_RANGE)
    val sameTunnel = { p: String, d: String, n: Int -> p == profile && d == device && n == port }
    val others = pins.filter { it.connectionId != editingId }
    others.firstOrNull { it.localPort == wanted && !sameTunnel(it.profile, it.device, it.port) }?.let {
        return LocalPortProblem(LocalPortProblem.Reason.PINNED_ELSEWHERE, "${it.device}:${it.port}")
    }
    others.firstOrNull { sameTunnel(it.profile, it.device, it.port) && it.localPort != wanted }?.let {
        return LocalPortProblem(LocalPortProblem.Reason.OTHER_PIN_ON_TUNNEL, it.localPort.toString())
    }
    held.firstOrNull { it.localPort == wanted && !sameTunnel(it.profile, it.device, it.port) }?.let {
        return LocalPortProblem(LocalPortProblem.Reason.IN_USE, "${it.device}:${it.port}")
    }
    held.firstOrNull { sameTunnel(it.profile, it.device, it.port) && it.localPort != wanted }?.let {
        return LocalPortProblem(LocalPortProblem.Reason.TUNNEL_UP_OTHER_PORT, it.localPort.toString())
    }
    return null
}

/**
 * The pinned local port of each tunnel of a profile, by [localPortKey]: the
 * pin of any of its connections ([localPortProblem] keeps them from
 * disagreeing; should they, the first saved one wins).
 */
fun tunnelPins(connections: List<Connection>): Map<String, Int> =
    connections.filter { it.localPort != null }
        .groupBy { localPortKey(it.device, it.port) }
        .mapValues { (_, list) -> checkNotNull(list.first().localPort) }

/** Every pin of [profile]'s [connections], for the checks across profiles. */
fun portPins(profile: String, connections: List<Connection>): List<PortPin> =
    connections.mapNotNull { c -> c.localPort?.let { PortPin(profile, c.id, c.device, c.port, it) } }

/** What the sheet checks a pinned port of [profile] against: every profile's [pins], the tunnels up now ([held]). */
data class LocalPortContext(val profile: String, val pins: List<PortPin> = emptyList(), val held: List<HeldPort> = emptyList()) {
    fun problem(text: String, device: String, port: Int?, editingId: String?): LocalPortProblem? =
        localPortProblem(text, profile, device, port, editingId, pins, held)
}

/**
 * The pin [before] held that nothing pins any more once the profile's
 * connections are [after] ([before] deleted, or changed to "Auto" or to
 * another port): its tunnel's [localPortKey] and the port, to release from
 * the shared map. Null when [before] pinned nothing or another connection
 * to the same device and port still pins that port.
 */
fun pinToRelease(before: Connection?, after: List<Connection>): Pair<String, Int>? {
    val pin = before?.localPort ?: return null
    val key = localPortKey(before.device, before.port)
    return if (tunnelPins(after)[key] == pin) null else key to pin
}

/**
 * [connection]'s pin checked once more as it is saved, against [pins] of
 * every profile and the tunnels up now ([held]): the sheet's view may be
 * old by then. Null when it pins nothing or may pin what it does.
 */
fun pinProblemOnSave(profile: String, connection: Connection, pins: List<PortPin>, held: List<HeldPort>): LocalPortProblem? {
    val pin = connection.localPort ?: return null
    return localPortProblem(pin.toString(), profile, connection.device, connection.port, connection.id, pins, held)
}

/** A pin the save refused ([pinProblemOnSave]); nothing was saved. */
class PinRefused(val problem: LocalPortProblem) : Exception("pin refused: ${problem.reason}")

/** The words for [problem]: a string resource and its arguments, for the sheet and for a refused save alike. */
fun localPortProblemWords(problem: LocalPortProblem): Pair<Int, List<Any>> = when (problem.reason) {
    LocalPortProblem.Reason.OUT_OF_RANGE -> R.string.local_port_out_of_range to listOf(MIN_PINNED_PORT)
    LocalPortProblem.Reason.PINNED_ELSEWHERE -> R.string.local_port_pinned_elsewhere to listOf(problem.other.orEmpty())
    LocalPortProblem.Reason.OTHER_PIN_ON_TUNNEL -> R.string.local_port_other_pin to listOf(problem.other.orEmpty())
    LocalPortProblem.Reason.IN_USE -> R.string.local_port_in_use to listOf(problem.other.orEmpty())
    LocalPortProblem.Reason.TUNNEL_UP_OTHER_PORT -> R.string.local_port_tunnel_up to listOf(problem.other.orEmpty())
}
