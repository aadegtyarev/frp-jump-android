package ru.adsrv.frpjump.ports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a connection offers for a port; the tunnel itself is plain TCP either way. */
@Serializable
enum class PortKind {
    @SerialName("web") WEB,
    @SerialName("ssh") SSH,
    @SerialName("tcp") TCP,

    /** An MQTT broker, opened in the explorer; on the wire a plain TCP port. */
    @SerialName("mqtt") MQTT,
}

/** The Linux client's `_SSH_PORTS`: these go to the server as `ssh` whatever the kind. */
private val SSH_PORTS = setOf(22, 2222)

/**
 * The `protocol` sent to `POST /api/agent/connect`, classified as the Linux
 * client does (`_classify_protocol`): the server rejects a connect whose
 * protocol differs from an existing service on that port, so both clients
 * must name the same port the same way. MQTT is the app's own idea: the
 * server sees the plain `tcp` port it always did.
 */
fun wireProtocolFor(kind: PortKind, port: Int): String =
    if (kind == PortKind.SSH || port in SSH_PORTS) "ssh" else "tcp"
