package ru.adsrv.frpjump.store

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/** What [CredentialStore.state] finds on disk. */
sealed interface StoredEnrollment {
    data class Enrolled(val credentials: Credentials) : StoredEnrollment
    data object NotEnrolled : StoredEnrollment

    /** Could not be read just now (Keystore busy, I/O): retry, do not re-enroll. */
    data class Unavailable(val reason: String) : StoredEnrollment
}

/**
 * True for failures that will not go away by retrying: the key is gone or
 * invalidated, the ciphertext does not match it, or the file is malformed.
 */
internal fun isPermanentLoss(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
    it is KeyMissingException || it is KeyPermanentlyInvalidatedException || it is UnrecoverableKeyException ||
        it is AEADBadTagException || it is SerializationException || it is IllegalArgumentException
}

/** What enrollment gave this phone. */
data class Credentials(
    val controlUrl: String,
    val deviceId: String,
    val deviceName: String,
    val apiToken: String,
    val certPem: String,
    val keyPem: String,
    val caCertPem: String,
    val serverAddr: String,
    val serverPort: Int,
) {
    override fun toString() = "Credentials(device=$deviceName, server=$controlUrl)"
}

/** On disk: public parts as they are, the API token and private key sealed. */
@Serializable
private data class StoredCredentials(
    @SerialName("control_url") val controlUrl: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("api_token_sealed") val apiTokenSealed: String,
    @SerialName("cert_pem") val certPem: String,
    @SerialName("key_pem_sealed") val keyPemSealed: String,
    @SerialName("ca_cert_pem") val caCertPem: String,
    @SerialName("server_addr") val serverAddr: String,
    @SerialName("server_port") val serverPort: Int,
)

/**
 * One profile's enrollment: one JSON file ([storage], `enrollment.json` in the
 * profile's directory), the secrets in it sealed with the profile's own [box].
 */
class CredentialStore(private val storage: TextStorage, private val box: SecretBox) {

    @Volatile
    private var cached: Credentials? = null

    /** The credentials, or null when not enrolled or unreadable right now; see [state]. */
    fun load(): Credentials? = (state() as? StoredEnrollment.Enrolled)?.credentials

    /**
     * A permanent loss (see [isPermanentLoss]) wipes the file and the key: the
     * phone has to enroll again. Anything else is reported as [StoredEnrollment.Unavailable]
     * and tried again on the next call.
     */
    @Synchronized
    fun state(): StoredEnrollment {
        cached?.let { return StoredEnrollment.Enrolled(it) }
        return try {
            val text = storage.read() ?: return StoredEnrollment.NotEnrolled
            val s = WireJson.decodeFromString<StoredCredentials>(text)
            val c = Credentials(
                controlUrl = s.controlUrl,
                deviceId = s.deviceId,
                deviceName = s.deviceName,
                apiToken = box.open(s.apiTokenSealed),
                certPem = s.certPem,
                keyPem = box.open(s.keyPemSealed),
                caCertPem = s.caCertPem,
                serverAddr = s.serverAddr,
                serverPort = s.serverPort,
            )
            cached = c
            StoredEnrollment.Enrolled(c)
        } catch (e: Exception) {
            if (isPermanentLoss(e)) {
                Log.w(TAG, "stored enrollment lost for good: ${e.javaClass.simpleName}")
                wipe()
                StoredEnrollment.NotEnrolled
            } else {
                Log.w(TAG, "stored enrollment unreadable for now: ${e.javaClass.simpleName}")
                StoredEnrollment.Unavailable(e.javaClass.simpleName)
            }
        }
    }

    @Synchronized
    fun save(c: Credentials) {
        val stored = StoredCredentials(
            controlUrl = c.controlUrl,
            deviceId = c.deviceId,
            deviceName = c.deviceName,
            apiTokenSealed = box.seal(c.apiToken),
            certPem = c.certPem,
            keyPemSealed = box.seal(c.keyPem),
            caCertPem = c.caCertPem,
            serverAddr = c.serverAddr,
            serverPort = c.serverPort,
        )
        storage.write(WireJson.encodeToString(stored))
        cached = c
    }

    @Synchronized
    fun wipe() {
        cached = null
        storage.delete()
        box.destroy()
    }

    private companion object {
        const val TAG = "CredentialStore"
    }
}
