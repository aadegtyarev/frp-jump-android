package ru.adsrv.frpjump.store

import android.util.AtomicFile
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import java.io.File

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

/** One JSON file in the app's private filesDir. */
class CredentialStore(filesDir: File, private val box: KeystoreSecretBox = KeystoreSecretBox()) {
    private val file = AtomicFile(File(filesDir, "enrollment.json"))

    @Volatile
    private var cached: Credentials? = null

    @Synchronized
    fun load(): Credentials? {
        cached?.let { return it }
        if (!file.baseFile.exists()) return null
        return try {
            val s = WireJson.decodeFromString<StoredCredentials>(String(file.readFully(), Charsets.UTF_8))
            Credentials(
                controlUrl = s.controlUrl,
                deviceId = s.deviceId,
                deviceName = s.deviceName,
                apiToken = box.open(s.apiTokenSealed),
                certPem = s.certPem,
                keyPem = box.open(s.keyPemSealed),
                caCertPem = s.caCertPem,
                serverAddr = s.serverAddr,
                serverPort = s.serverPort,
            ).also { cached = it }
        } catch (e: Exception) {
            // Unreadable (key lost, file damaged): the phone has to enroll again.
            Log.w(TAG, "stored enrollment unreadable: ${e.javaClass.simpleName}")
            null
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
        val out = file.startWrite()
        try {
            out.write(WireJson.encodeToString(stored).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        cached = c
    }

    @Synchronized
    fun wipe() {
        cached = null
        file.delete()
        box.destroy()
    }

    private companion object {
        const val TAG = "CredentialStore"
    }
}
