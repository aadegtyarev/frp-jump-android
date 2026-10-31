package ru.adsrv.frpjump.connections

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ports.wireProtocolFor
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
    /**
     * The name the owner gave it; null: none, it shows [defaultConnectionName]
     * in the app's language. Up to 0.2.0-beta1 the name was stored as `name`
     * also when the app made it up ([readConnections]).
     */
    @SerialName("custom_name") val customName: String? = null,
    /** Web: open `https://` instead of `http://`. */
    val https: Boolean = false,
    /** SSH: the user the login dialog starts with. */
    val user: String? = null,
) {
    /** The SSH user, `root` when none is set. */
    val sshUser: String get() = user?.trim()?.takeIf { it.isNotEmpty() } ?: SshLogin.DEFAULT_USER

    /** What the connection is called: its own name, else [defaultConnectionName] with [kindWord] ("Web", "Веб"). */
    fun label(kindWord: (PortKind) -> String): String = customName ?: defaultConnectionName(kind, port, kindWord)
}

/**
 * The name of a connection the owner did not name: its kind, and the port
 * unless it is the kind's own: "Web" (80), "Web HTTPS" (443), "SSH" (22),
 * "Web 8080", "SSH 2222", "TCP 1883". [kindWord] says the kind in the app's
 * language ("Веб 8080").
 */
fun defaultConnectionName(kind: PortKind, port: Int, kindWord: (PortKind) -> String): String {
    val word = kindWord(kind)
    return when {
        kind == PortKind.WEB && port == 80 -> word
        kind == PortKind.WEB && port == 443 -> "$word HTTPS"
        kind == PortKind.SSH && port == 22 -> word
        else -> "$word $port"
    }
}

/** A port the "New connection" sheet offers to tap; [name], if any, is filled in as the connection's name. */
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

/** A name a preset fills in ("homeui"), not one the owner typed. */
fun isPresetName(name: String): Boolean = PORT_PRESETS.values.flatten().any { it.name != null && it.name == name.trim() }

/**
 * The name field once [preset] is tapped: blank or filled in by another
 * preset, it takes the preset's name (none for a preset without one); a
 * name the owner typed stays.
 */
fun nameAfterPreset(current: String, preset: PortPreset): String =
    if (current.isBlank() || isPresetName(current)) preset.name.orEmpty() else current

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
    /**
     * Another connection to the same device and port goes to the server
     * under another protocol ([wireProtocolFor]: `ssh` or `tcp`): they would
     * share one tunnel, which the server knows under one protocol only.
     * Holds the other connection's protocol.
     */
    val protocolClash: String? = null,
) {
    val ok: Boolean get() = port == null && !nameTaken && !userMissing && protocolClash == null
}

/** The name [draft] is saved under: what was typed; null (none) leaves the [defaultConnectionName]. */
fun customNameOf(draft: ConnectionDraft): String? = draft.name.trim().takeIf { it.isNotEmpty() }

/** What [draft] would be called once saved, with [kindWord] for the default name. */
fun draftLabel(draft: ConnectionDraft, kindWord: (PortKind) -> String): String {
    customNameOf(draft)?.let { return it }
    val port = parsePort(draft.portText) ?: return ""
    return defaultConnectionName(draft.kind, port, kindWord)
}

/**
 * What stops [draft] from being saved to [device]: a bad port, a name
 * already used on that device and port (by any connection but [editingId]),
 * SSH without a user, or a kind the port's other connections cannot share. Names
 * are compared as shown ([Connection.label], [draftLabel] with [kindWord]).
 */
fun draftProblems(
    draft: ConnectionDraft,
    device: String,
    existing: List<Connection>,
    editingId: String?,
    kindWord: (PortKind) -> String,
): DraftProblems {
    val name = draftLabel(draft, kindWord)
    val port = parsePort(draft.portText)
    return DraftProblems(
        port = portProblem(draft.portText),
        nameTaken = port != null && existing.any {
            it.id != editingId && it.device == device && it.port == port && it.label(kindWord).trim().equals(name.trim(), ignoreCase = true)
        },
        userMissing = draft.kind == PortKind.SSH && draft.user.isBlank(),
        protocolClash = port?.let { p ->
            val mine = wireProtocolFor(draft.kind, p)
            existing.firstOrNull { it.id != editingId && it.device == device && it.port == p && wireProtocolFor(it.kind, p) != mine }
                ?.let { wireProtocolFor(it.kind, p) }
        },
    )
}

/** [draft] as a connection to [device]; call only when [draftProblems] is ok. */
fun connectionOf(draft: ConnectionDraft, id: String, device: String): Connection = Connection(
    id = id,
    device = device,
    port = checkNotNull(parsePort(draft.portText)),
    kind = draft.kind,
    customName = customNameOf(draft),
    https = draft.kind == PortKind.WEB && draft.https,
    user = draft.user.trim().takeIf { draft.kind == PortKind.SSH },
)

/** The sheet filled in from [connection], for "Change". */
fun draftOf(connection: Connection) = ConnectionDraft(
    kind = connection.kind,
    portText = connection.port.toString(),
    name = connection.customName.orEmpty(),
    https = connection.https,
    user = connection.sshUser,
)
