package ru.adsrv.frpjump.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.ssh.WriteFailureKind
import ru.adsrv.frpjump.ssh.hostKeyWriteFailure
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

/** One request waiting on a certificate question (an SslErrorHandler in the app). */
interface CertAnswer {
    fun proceed()
    fun cancel()
}

/**
 * The owner pressed Trust: [save] the pin (off the main thread), then let every waiting request go on
 * only when it was saved. A failed save cancels them (fail closed) and returns the failure, so the
 * caller can tell the owner. Cancellation cancels them too and is rethrown, never swallowed.
 */
suspend fun answerCertTrust(handlers: List<CertAnswer>, save: () -> Unit): Exception? {
    var failure: Exception? = null
    var saved = false
    try {
        failure = withContext(Dispatchers.IO) { hostKeyWriteFailure(save) }
        saved = failure == null
    } finally {
        // Anything but a saved pin (a failure, cancellation, even an Error) refuses the waiting requests.
        if (!saved) handlers.forEach { it.cancel() }
    }
    if (saved) handlers.forEach { it.proceed() }
    return failure
}

/**
 * The banner above the web page after a certificate answer, as a pure state: [refused] closes the page
 * for "Try again"; [saveFailure] says why (the pin could not be saved) instead of "not trusted".
 */
data class CertBanner(val refused: Boolean = false, val saveFailure: WriteFailureKind? = null) {
    /** The owner refused, or a certificate of this tunnel was refused without asking. */
    fun refusedNow() = CertBanner(refused = true)

    /** Trust was pressed but could not be saved: the page stays closed. */
    fun saveFailed(kind: WriteFailureKind) = CertBanner(refused = true, saveFailure = kind)

    /** Trust was saved and the page goes on: nothing stale stays. */
    fun saved() = CertBanner()

    /** "Try again" reloads the page. */
    fun tryAgain() = CertBanner()
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
