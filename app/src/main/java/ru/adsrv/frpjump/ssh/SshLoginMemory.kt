package ru.adsrv.frpjump.ssh

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

@Serializable
enum class SshAuthMethod {
    @SerialName("key") KEY,
    @SerialName("password") PASSWORD,
}

/** What the login dialog offers for a device and port. */
@Serializable
data class SshLogin(val user: String = DEFAULT_USER, val method: SshAuthMethod = SshAuthMethod.KEY) {
    companion object {
        /** Wiren Board controllers log in as root. */
        const val DEFAULT_USER = "root"
    }
}

/** The last user name and auth method per (device, port). Passwords are not kept here. */
class SshLoginMemory(private val storage: TextStorage) {
    private var logins: Map<String, Map<String, SshLogin>> = read()

    @Synchronized
    fun get(device: String, port: Int): SshLogin = logins[device]?.get(port.toString()) ?: SshLogin()

    @Synchronized
    fun remember(device: String, port: Int, login: SshLogin) {
        val clean = login.copy(user = login.user.trim().ifEmpty { SshLogin.DEFAULT_USER })
        val value = logins + (device to (logins[device].orEmpty() + (port.toString() to clean)))
        storage.write(WireJson.encodeToString(value))
        logins = value
    }

    @Synchronized
    fun wipe() {
        storage.delete()
        logins = emptyMap()
    }

    private fun read(): Map<String, Map<String, SshLogin>> =
        runCatching { storage.read()?.let { WireJson.decodeFromString<Map<String, Map<String, SshLogin>>>(it) } }
            .getOrNull().orEmpty()
}
