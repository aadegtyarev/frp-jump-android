package ru.adsrv.frpjump.connections

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ports.profilePortPrefix
import ru.adsrv.frpjump.store.TextStorage

/** A port as 0.1/0.2-beta kept it per device in `device_ports.json`. */
@Serializable
private data class LegacyPort(val port: Int, val label: String = "", val kind: PortKind = PortKind.TCP)

/** The two ports 0.1/0.2-beta showed on every card without storing them, with the names they showed. */
private val LEGACY_PRESETS = mapOf(80 to ("Web" to PortKind.WEB), 22 to ("SSH" to PortKind.SSH))

/**
 * Turns a profile's per-device ports of 0.1/0.2-beta into named
 * connections, once, the first time the profile opens with this version:
 *
 * - every port the owner added (`device_ports.json`) becomes a connection
 *   with its label (none: the default name), kind and port, also on 80 or 22;
 * - the two presets every card showed (Web 80, SSH 22) become connections
 *   only for devices where they were used: a local port was ever handed out
 *   for them ([localPorts], which keeps a port for good once a tunnel was
 *   up). A preset never connected was never the owner's choice, and
 *   listing it for every device would bury the real ones. They get the
 *   default name, which says the same in the app's language;
 * - an SSH connection starts with the user last logged in as ([sshUser]).
 *
 * Tunnels stay keyed by (profile, device, port), so local ports, remembered
 * SSH logins, passwords, host keys and the web view's site memory keep
 * working as they are.
 *
 * A `device_ports.json` that does not parse is left in place and nothing
 * is written: the next start tries again, and the file is never deleted.
 *
 * Crash-safe: [connections] is written in one atomic write before
 * [legacy] is deleted. Its presence means "done": a run after a crash
 * between the two only deletes [legacy]; a crash before the write leaves
 * everything as it was, to be done again. A profile without anything to
 * migrate (a new one) gets an empty list, so this never runs again for it.
 * Returns true when it wrote the list.
 */
fun migrateDevicePorts(
    profile: String,
    connections: TextStorage,
    legacy: TextStorage,
    localPorts: Map<String, Int>,
    sshUser: (device: String, port: Int) -> String,
    newId: () -> String,
): Boolean {
    val legacyText = legacy.read()
    val custom = legacyText?.let { runCatching { WireJson.decodeFromString<Map<String, List<LegacyPort>>>(it) }.getOrNull() }
    if (legacyText != null && custom == null) {
        // Never lose what cannot be read: it stays where it is, and nothing is migrated from half of it.
        Log.w(TAG, "device_ports.json of profile $profile does not parse; left in place")
        return false
    }
    if (connections.read() != null) {
        if (legacyText != null) legacy.delete()
        return false
    }
    val usedPresets = localPorts.keys
        .filter { it.startsWith(profilePortPrefix(profile)) }
        .mapNotNull { key ->
            val devicePort = key.removePrefix(profilePortPrefix(profile))
            val port = devicePort.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
            if (port in LEGACY_PRESETS) devicePort.substringBeforeLast(':') to port else null
        }
    val devices = (custom.orEmpty().keys + usedPresets.map { it.first }).toSortedSet()
    val migrated = devices.flatMap { device ->
        val own = custom.orEmpty()[device].orEmpty().distinctBy { it.port }.sortedBy { it.port }
        // A port the owner added on 80 or 22 keeps the owner's label and kind instead of the preset's.
        val presets = LEGACY_PRESETS.filterKeys { port -> device to port in usedPresets && own.none { it.port == port } }
            .map { (port, preset) -> LegacyPort(port, "", preset.second) }
        (presets + own).map { p ->
            Connection(
                id = newId(),
                device = device,
                port = p.port,
                kind = p.kind,
                customName = p.label.trim().takeIf { it.isNotEmpty() },
                user = sshUser(device, p.port).takeIf { p.kind == PortKind.SSH },
            )
        }
    }
    connections.write(ConnectionStore.encodeConnections(migrated))
    legacy.delete()
    if (migrated.isNotEmpty()) Log.i(TAG, "${migrated.size} ports of profile $profile are now connections")
    return true
}

/**
 * Reads `connections.json`. Up to 0.2.0-beta1 every connection had a `name`,
 * also when the app made one up from the kind and port ("Web 8080", "TCP
 * 1883", and from the upgrade of 0.1 "Web", "SSH", "Port 8080"), so made-up
 * names stayed English in any language. Such a record gets no name of its
 * own, and shows the default name ([defaultConnectionName]) in the app's
 * language; every other `name` is kept as is: typed by the owner, or a
 * preset's or the SSH user's, which say the same in any language. So is a
 * made-up name whose default another connection to the same device and port
 * already shows: two rows must not read the same. Records written since
 * carry `custom_name` only. Throws when the text is not a list of
 * connections.
 */
fun readConnections(text: String): List<Connection> {
    val records = WireJson.parseToJsonElement(text).jsonArray.map { element ->
        val record = element.jsonObject
        val connection = WireJson.decodeFromJsonElement(Connection.serializer(), record)
        val legacyName = (record[LEGACY_NAME] as? JsonPrimitive)?.takeIf { it.isString }?.content
        connection to legacyName?.takeUnless { CUSTOM_NAME in record }
    }
    // What each row reads as now, in English as the old names were made.
    val shown = records.map { (c, legacy) -> legacy?.trim() ?: c.label(OLD_KIND_WORDS::getValue) }
    return records.mapIndexed { i, (c, legacy) ->
        if (legacy == null) return@mapIndexed c
        val default = defaultConnectionName(c.kind, c.port, OLD_KIND_WORDS::getValue)
        val taken = records.indices.any { j ->
            j != i && records[j].first.device == c.device && records[j].first.port == c.port && shown[j].equals(default, ignoreCase = true)
        }
        c.copy(customName = legacy.trim().takeUnless { it.isEmpty() || (madeUpName(it, c) && !taken) })
    }
}

/** [name] is one the app made up for [c] from its kind and port up to 0.2.0-beta1, in English. */
private fun madeUpName(name: String, c: Connection): Boolean {
    val n = name.trim()
    return n.isEmpty() ||
        n == "${OLD_KIND_WORDS.getValue(c.kind)} ${c.port}" ||
        n == "Port ${c.port}" ||
        LEGACY_PRESETS[c.port]?.let { (presetName, kind) -> n == presetName && c.kind == kind } == true
}

/** The kinds as the English "Web 8080" said them. */
private val OLD_KIND_WORDS = mapOf(PortKind.WEB to "Web", PortKind.SSH to "SSH", PortKind.TCP to "TCP")
private const val LEGACY_NAME = "name"
private const val CUSTOM_NAME = "custom_name"

private const val TAG = "ConnectionMigration"
