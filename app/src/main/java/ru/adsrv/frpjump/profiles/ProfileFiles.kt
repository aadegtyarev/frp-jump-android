package ru.adsrv.frpjump.profiles

/**
 * File names in the app's filesDir. Per-profile files live in
 * `profiles/<id>/`; v1 kept the same names in filesDir itself.
 */
internal object ProfileFiles {
    const val DIR = "profiles"
    const val INDEX = "profiles.json"

    /** Present while the v1 upgrade is unfinished; holds the id it gives the profile. */
    const val MIGRATION = "profiles_migration.json"

    const val ENROLLMENT = "enrollment.json"
    const val DEVICE_LIST = "device_list.json"

    /** The server's last `features` answer, kept beside [DEVICE_LIST]. */
    const val SERVER_FEATURES = "server_features.json"
    const val DEVICE_PORTS = "device_ports.json"
    const val HOST_KEYS = "ssh_host_keys.json"
    const val SSH_LOGINS = "ssh_logins.json"

    /** Named connections; replaced [DEVICE_PORTS] (see [ru.adsrv.frpjump.connections.migrateDevicePorts]). */
    const val CONNECTIONS = "connections.json"

    /** A [CONNECTIONS] that did not parse, kept before anything is saved over it. */
    const val CONNECTIONS_CORRUPT = "connections.corrupt.json"

    /** Confirmed certificates of HTTPS web connections. */
    const val WEB_CERTS = "web_certs.json"

    /** v1's files, moved into the profile's directory on upgrade. */
    val PER_PROFILE = listOf(ENROLLMENT, DEVICE_LIST, DEVICE_PORTS, HOST_KEYS, SSH_LOGINS)

    /** Shared by all profiles; their keys name the profile and are rewritten on upgrade. */
    const val LOCAL_PORTS = "local_ports.json"
    const val WEB_SITE = "web_site.json"
    const val SSH_PASSWORDS = "ssh_passwords.json"

    /** What android.util.AtomicFile may leave next to a file: the old copy, a write in progress. */
    val ATOMIC_SUFFIXES = listOf("", ".bak", ".new")
}
