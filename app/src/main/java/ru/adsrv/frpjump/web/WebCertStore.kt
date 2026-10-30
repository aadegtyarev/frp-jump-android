package ru.adsrv.frpjump.web

import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage
import java.security.MessageDigest

/** What to do with the certificate an HTTPS web connection presents. */
sealed interface CertCheck {
    /** The one the owner confirmed before: open. */
    data object Trusted : CertCheck

    /** Nothing confirmed for this device and port yet: show the fingerprint, ask. */
    data object Unknown : CertCheck

    /** Another certificate was confirmed before: ask again, showing both. */
    data class Changed(val pinned: String) : CertCheck

    /** Not this connection's page, or no certificate to look at: refuse without asking. */
    data object Refused : CertCheck
}

/**
 * The decision for [presented] (a [certFingerprint], null when the
 * certificate could not be read) given what is [pinned]. Only pages of the
 * connection's own tunnel ([forTunnel]) may ever be accepted.
 */
fun certCheck(pinned: String?, presented: String?, forTunnel: Boolean): CertCheck = when {
    !forTunnel || presented == null -> CertCheck.Refused
    pinned == null -> CertCheck.Unknown
    pinned == presented -> CertCheck.Trusted
    else -> CertCheck.Changed(pinned)
}

/** SHA-256 of a DER certificate as colon-separated upper-case hex, as browsers show it. */
fun certFingerprint(der: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

/**
 * Certificates of HTTPS web connections the owner confirmed, per (device,
 * target port) of one profile: every tunnel lands on 127.0.0.1, so neither
 * the address nor the host name in the certificate says which device it is.
 * Reads at construction, writes on [pin]: call that off the main thread.
 */
class WebCertStore(private val storage: TextStorage) {
    private var pins: Map<String, Map<String, String>> = read()

    @Synchronized
    fun pinned(device: String, port: Int): String? = pins[device]?.get(port.toString())

    @Synchronized
    fun check(device: String, port: Int, presented: String?, forTunnel: Boolean): CertCheck =
        certCheck(pinned(device, port), presented, forTunnel)

    @Synchronized
    fun pin(device: String, port: Int, fingerprint: String) {
        val value = pins + (device to (pins[device].orEmpty() + (port.toString() to fingerprint)))
        storage.write(WireJson.encodeToString(value))
        pins = value
    }

    private fun read(): Map<String, Map<String, String>> =
        runCatching { storage.read()?.let { WireJson.decodeFromString<Map<String, Map<String, String>>>(it) } }.getOrNull().orEmpty()
}
