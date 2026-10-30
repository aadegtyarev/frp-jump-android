package ru.adsrv.frpjump.ssh

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage

/** A key the app can log in with: OpenSSH private key text, its passphrase if any. */
class SshIdentity(val privateKey: String, val passphrase: String?, val publicLine: String) {
    override fun toString() = "SshIdentity($publicLine)"
}

@Serializable
private data class StoredKey(
    @SerialName("private_sealed") val privateSealed: String,
    @SerialName("passphrase_sealed") val passphraseSealed: String? = null,
    @SerialName("public_line") val publicLine: String,
)

@Serializable
private data class StoredKeys(val generated: StoredKey? = null, val imported: StoredKey? = null)

/**
 * The app's own ed25519 key (made on first use) and an optional imported
 * one. Private halves and passphrases are sealed with [box]; public lines
 * are stored as they are.
 */
class SshKeyStore(private val storage: TextStorage, private val box: SecretBox) {
    private var keys: StoredKeys = read()

    /** The app's key's `authorized_keys` line; generates the key on first use. */
    @Synchronized
    fun publicLine(comment: String): String {
        keys.generated?.let { return it.publicLine }
        val pair = generateEd25519(comment)
        save(keys.copy(generated = StoredKey(privateSealed = box.seal(pair.privateKey), publicLine = pair.publicLine)))
        return pair.publicLine
    }

    @Synchronized
    fun importedPublicLine(): String? = keys.imported?.publicLine

    /** Keys to offer, the imported one first; a key that cannot be unsealed is skipped. */
    @Synchronized
    fun identities(comment: String): List<SshIdentity> {
        publicLine(comment)
        return listOfNotNull(keys.imported, keys.generated).mapNotNull { stored ->
            try {
                SshIdentity(box.open(stored.privateSealed), stored.passphraseSealed?.let(box::open), stored.publicLine)
            } catch (e: Exception) {
                Log.w(TAG, "cannot unseal an SSH key: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    /** Stores an imported private key; [publicLine] comes from loading it (see [SshClients.publicLineOf]). */
    @Synchronized
    fun import(privateKey: String, passphrase: String?, publicLine: String) {
        val stored = StoredKey(box.seal(privateKey), passphrase?.takeIf { it.isNotEmpty() }?.let(box::seal), publicLine)
        save(keys.copy(imported = stored))
    }

    @Synchronized
    fun removeImported() = save(keys.copy(imported = null))

    @Synchronized
    fun wipe() {
        storage.delete()
        keys = StoredKeys()
    }

    private fun read(): StoredKeys =
        runCatching { storage.read()?.let { WireJson.decodeFromString<StoredKeys>(it) } }.getOrNull() ?: StoredKeys()

    private fun save(value: StoredKeys) {
        storage.write(WireJson.encodeToString(value))
        keys = value
    }

    private companion object {
        const val TAG = "SshKeyStore"
    }
}

/**
 * Passwords the owner ticked "remember" for, sealed with the shared SSH key
 * [box], per (profile, device, port, user): two profiles may both have a
 * device called "wb", and they need not share its password.
 */
class SshPasswords(private val storage: TextStorage, private val box: SecretBox) {
    private var sealed: Map<String, String> = read()

    @Synchronized
    fun get(profile: String, device: String, port: Int, user: String): String? {
        val value = sealed[key(profile, device, port, user)] ?: return null
        return try {
            box.open(value)
        } catch (e: Exception) {
            Log.w(TAG, "cannot unseal a password: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Whether a password is kept for this login; cheap, nothing is unsealed. */
    @Synchronized
    fun has(profile: String, device: String, port: Int, user: String): Boolean = key(profile, device, port, user) in sealed

    @Synchronized
    fun remember(profile: String, device: String, port: Int, user: String, password: String) =
        save(sealed + (key(profile, device, port, user) to box.seal(password)))

    @Synchronized
    fun forget(profile: String, device: String, port: Int, user: String) {
        val k = key(profile, device, port, user)
        if (k in sealed) save(sealed - k)
    }

    /** Drops every password of [profile] ("Forget profile"). */
    @Synchronized
    fun forgetProfile(profile: String) {
        val kept = sealed.filterKeys { profileOf(it) != profile }
        if (kept != sealed) save(kept)
    }

    @Synchronized
    fun wipe() {
        storage.delete()
        sealed = emptyMap()
    }

    private fun read(): Map<String, String> =
        runCatching { storage.read()?.let { WireJson.decodeFromString<Map<String, String>>(it) } }.getOrNull().orEmpty()

    private fun save(value: Map<String, String>) {
        storage.write(WireJson.encodeToString(value))
        sealed = value
    }

    companion object {
        private const val TAG = "SshPasswords"

        private fun key(profile: String, device: String, port: Int, user: String) =
            WireJson.encodeToString(listOf(profile, device, port.toString(), user))

        private fun profileOf(key: String): String? =
            runCatching { WireJson.decodeFromString<List<String>>(key) }.getOrNull()?.takeIf { it.size == 4 }?.first()

        /**
         * v1 keyed passwords by `[device, port, user]`; on upgrade they become
         * [profile]'s. The sealed values stay as they are (same key). Null
         * when [text] needs no change, so running it again changes nothing.
         */
        fun migrateLegacy(text: String, profile: String): String? {
            val stored = runCatching { WireJson.decodeFromString<Map<String, String>>(text) }.getOrNull() ?: return null
            val migrated = stored.mapKeys { (key, _) ->
                val parts = runCatching { WireJson.decodeFromString<List<String>>(key) }.getOrNull()
                if (parts?.size == 3) WireJson.encodeToString(listOf(profile) + parts) else key
            }
            return if (migrated == stored) null else WireJson.encodeToString(migrated)
        }
    }
}
