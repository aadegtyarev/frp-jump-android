package ru.adsrv.frpjump.connections

import android.util.Log
import kotlinx.serialization.Serializable
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ports.profilePortPrefix
import ru.adsrv.frpjump.store.TextStorage

/** A port as 0.1/0.2-beta kept it per device in `device_ports.json`. */
@Serializable
private data class LegacyPort(val port: Int, val label: String = "", val kind: PortKind = PortKind.TCP)

/** The two ports 0.1/0.2-beta showed on every card without storing them. */
private val LEGACY_PRESETS = mapOf(80 to ("Web" to PortKind.WEB), 22 to ("SSH" to PortKind.SSH))

/**
 * Turns a profile's per-device ports of 0.1/0.2-beta into named
 * connections, once, the first time the profile opens with this version:
 *
 * - every port the owner added (`device_ports.json`) becomes a connection
 *   with its label, kind and port, also on 80 or 22;
 * - the two presets every card showed (Web 80, SSH 22) become connections
 *   only for devices where they were used: a local port was ever handed out
 *   for them ([localPorts], which keeps a port for good once a tunnel was
 *   up). A preset never connected was never the owner's choice, and
 *   listing it for every device would bury the real ones;
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
            .map { (port, preset) -> LegacyPort(port, preset.first, preset.second) }
        (presets + own).map { p ->
            Connection(
                id = newId(),
                device = device,
                port = p.port,
                kind = p.kind,
                name = p.label.trim().ifEmpty { "Port ${p.port}" },
                user = sshUser(device, p.port).takeIf { p.kind == PortKind.SSH },
            )
        }
    }
    connections.write(ConnectionStore.encodeConnections(migrated))
    legacy.delete()
    if (migrated.isNotEmpty()) Log.i(TAG, "${migrated.size} ports of profile $profile are now connections")
    return true
}

private const val TAG = "ConnectionMigration"
