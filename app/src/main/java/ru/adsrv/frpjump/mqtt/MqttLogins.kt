package ru.adsrv.frpjump.mqtt

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage

@Serializable
private data class StoredLogin(val user: String, @SerialName("password_sealed") val passwordSealed: String)

/**
 * Broker logins the owner ticked Remember for, one per connection of a
 * profile; the password sealed with [box] (the one SSH passwords use), the
 * user as it is. Read on first use, off the main thread.
 */
class MqttLogins(private val storage: TextStorage, private val box: SecretBox) {
    private var loaded: Map<String, StoredLogin>? = null

    private val logins: Map<String, StoredLogin>
        get() = loaded ?: read().also { loaded = it }

    /** The remembered login; null when none, or when its password cannot be unsealed. */
    @Synchronized
    fun get(profile: String, connectionId: String): MqttLogin? {
        return try {
            val stored = logins[connectionKey(profile, connectionId)] ?: return null
            MqttLogin(stored.user, box.open(stored.passwordSealed))
        } catch (e: Exception) {
            Log.w(TAG, "cannot read a broker login: ${e.javaClass.simpleName}")
            null
        }
    }

    @Synchronized
    fun remember(profile: String, connectionId: String, login: MqttLogin) =
        save(logins + (connectionKey(profile, connectionId) to StoredLogin(login.user, box.seal(login.password))))

    /** Drops the login of one connection (it was deleted, or the owner logged in without Remember). */
    @Synchronized
    fun forget(profile: String, connectionId: String) {
        val key = connectionKey(profile, connectionId)
        if (key in logins) save(logins - key)
    }

    /** Drops every login of [profile] ("Forget profile"). */
    @Synchronized
    fun forgetProfile(profile: String) {
        val kept = logins.filterKeys { profileOfKey(it) != profile }
        if (kept != logins) save(kept)
    }

    @Synchronized
    fun wipe() {
        storage.delete()
        loaded = emptyMap()
    }

    private fun read(): Map<String, StoredLogin> {
        // IO failure is not an empty file: never cache it or overwrite other saved entries.
        val text = storage.read() ?: return emptyMap()
        // Preserve the existing malformed-JSON fallback; this slice changes IO failure handling only.
        return runCatching { WireJson.decodeFromString<Map<String, StoredLogin>>(text) }.getOrNull().orEmpty()
    }

    private fun save(value: Map<String, StoredLogin>) {
        storage.write(WireJson.encodeToString(value))
        loaded = value
    }

    private companion object {
        const val TAG = "MqttLogins"
    }
}

/** The key of one connection of a profile in the MQTT stores. */
internal fun connectionKey(profile: String, connectionId: String): String = WireJson.encodeToString(listOf(profile, connectionId))

internal fun profileOfKey(key: String): String? =
    runCatching { WireJson.decodeFromString<List<String>>(key) }.getOrNull()?.takeIf { it.size == 2 }?.first()
