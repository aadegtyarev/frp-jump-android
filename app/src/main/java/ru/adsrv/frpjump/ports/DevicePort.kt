package ru.adsrv.frpjump.ports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the card offers for a port; the tunnel itself is plain TCP either way. */
@Serializable
enum class PortKind {
    @SerialName("web") WEB,
    @SerialName("ssh") SSH,
    @SerialName("tcp") TCP,
}

@Serializable
data class DevicePort(val port: Int, val label: String, val kind: PortKind)

/** Always on every card, before the owner's own ports. */
val PRESET_PORTS = listOf(
    DevicePort(80, "Web", PortKind.WEB),
    DevicePort(22, "SSH", PortKind.SSH),
)

/** The Linux client's `_SSH_PORTS`: these go to the server as `ssh` whatever the kind. */
private val SSH_PORTS = setOf(22, 2222)

/**
 * The `protocol` sent to `POST /api/agent/connect`, classified as the Linux
 * client does (`_classify_protocol`): the server rejects a connect whose
 * protocol differs from an existing service on that port, so both clients
 * must name the same port the same way.
 */
fun wireProtocolFor(port: DevicePort): String =
    if (port.kind == PortKind.SSH || port.port in SSH_PORTS) "ssh" else "tcp"

/** Presets first, then custom ports, without repeating a port number. */
fun portsForCard(custom: List<DevicePort>): List<DevicePort> {
    val presetNumbers = PRESET_PORTS.map { it.port }.toSet()
    return PRESET_PORTS + custom.filter { it.port !in presetNumbers }.distinctBy { it.port }.sortedBy { it.port }
}
