package ru.adsrv.frpjump.connections

import kotlinx.serialization.Serializable
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ssh.SshLogin

/**
 * A named connection of one profile: a port on one of its devices and what
 * to do with it. Several connections may name the same device and port
 * (SSH as root and as another user); they share one tunnel and one local
 * port, which stay keyed by (profile, device, port).
 */
@Serializable
data class Connection(
    /** A random UUID; never shown. */
    val id: String,
    val device: String,
    val port: Int,
    val kind: PortKind,
    val name: String,
    /** Web: open `https://` instead of `http://`. */
    val https: Boolean = false,
    /** SSH: the user the login dialog starts with. */
    val user: String? = null,
) {
    /** The SSH user, `root` when none is set. */
    val sshUser: String get() = user?.trim()?.takeIf { it.isNotEmpty() } ?: SshLogin.DEFAULT_USER
}

/** A port the "New connection" sheet offers to tap; [name] is the name it suggests, if any. */
data class PortPreset(val port: Int, val name: String? = null)

/** Presets per kind, as the contract lists them; the first is what picking the kind fills in. */
val PORT_PRESETS: Map<PortKind, List<PortPreset>> = mapOf(
    PortKind.WEB to listOf(
        PortPreset(80, "homeui"),
        PortPreset(443),
        PortPreset(8080),
        PortPreset(1880, "Node-RED"),
        PortPreset(3000, "Grafana"),
    ),
    PortKind.SSH to listOf(PortPreset(22), PortPreset(2222)),
    PortKind.TCP to listOf(
        PortPreset(1883, "MQTT"),
        PortPreset(502, "Modbus TCP"),
        PortPreset(5432, "PostgreSQL"),
    ),
)

/** The name a preset of [kind] suggests for [port]; null when there is none. */
fun presetName(kind: PortKind, port: Int): String? = PORT_PRESETS[kind]?.firstOrNull { it.port == port }?.name

/** Why a typed port is not usable. */
enum class PortProblem { MISSING, OUT_OF_RANGE }

/** A port as typed: digits only, 1–65535; null otherwise. */
fun parsePort(text: String): Int? {
    val digits = text.trim()
    if (digits.isEmpty() || digits.length > 5 || !digits.all { it in '0'..'9' }) return null
    return digits.toInt().takeIf { it in 1..65535 }
}

fun portProblem(text: String): PortProblem? = when {
    text.isBlank() -> PortProblem.MISSING
    parsePort(text) == null -> PortProblem.OUT_OF_RANGE
    else -> null
}

/** What the sheet holds while the owner types. */
data class ConnectionDraft(
    val kind: PortKind,
    val portText: String,
    val name: String,
    val https: Boolean = false,
    val user: String = SshLogin.DEFAULT_USER,
)

data class DraftProblems(
    val port: PortProblem? = null,
    /** Another connection to the same device and port already has this name. */
    val nameTaken: Boolean = false,
    /** SSH without a user. */
    val userMissing: Boolean = false,
) {
    val ok: Boolean get() = port == null && !nameTaken && !userMissing
}

/**
 * The name [draft] is saved under: what was typed, else the preset's
 * suggestion, the SSH user, or [fallback] (e.g. "Web 8080").
 */
fun resolvedName(draft: ConnectionDraft, fallback: (PortKind, Int) -> String): String {
    draft.name.trim().takeIf { it.isNotEmpty() }?.let { return it }
    val port = parsePort(draft.portText) ?: return ""
    presetName(draft.kind, port)?.let { return it }
    if (draft.kind == PortKind.SSH) draft.user.trim().takeIf { it.isNotEmpty() }?.let { return it }
    return fallback(draft.kind, port)
}

/**
 * What stops [draft] from being saved to [device]: a bad port, a name
 * already used on that device and port (by any connection but [editingId]),
 * or SSH without a user. [name] is the name it would be saved under.
 */
fun draftProblems(
    draft: ConnectionDraft,
    name: String,
    device: String,
    existing: List<Connection>,
    editingId: String?,
): DraftProblems {
    val port = parsePort(draft.portText)
    return DraftProblems(
        port = portProblem(draft.portText),
        nameTaken = port != null && existing.any {
            it.id != editingId && it.device == device && it.port == port && it.name.trim().equals(name.trim(), ignoreCase = true)
        },
        userMissing = draft.kind == PortKind.SSH && draft.user.isBlank(),
    )
}

/** [draft] as a connection to [device]; call only when [draftProblems] is ok. */
fun connectionOf(draft: ConnectionDraft, id: String, device: String, name: String): Connection = Connection(
    id = id,
    device = device,
    port = checkNotNull(parsePort(draft.portText)),
    kind = draft.kind,
    name = name.trim(),
    https = draft.kind == PortKind.WEB && draft.https,
    user = draft.user.trim().takeIf { draft.kind == PortKind.SSH },
)

/** The sheet filled in from [connection], for "Change". */
fun draftOf(connection: Connection) = ConnectionDraft(
    kind = connection.kind,
    portText = connection.port.toString(),
    name = connection.name,
    https = connection.https,
    user = connection.sshUser,
)
