package ru.adsrv.frpjump.ssh

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.KeystoreSecretBox
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
class SshKeyStore(private val storage: TextStorage, private val box: KeystoreSecretBox) {
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

/** Passwords the owner ticked "remember" for, sealed, per (device, port, user). */
class SshPasswords(private val storage: TextStorage, private val box: KeystoreSecretBox) {
    private var sealed: Map<String, String> = read()

    @Synchronized
    fun get(device: String, port: Int, user: String): String? {
        val value = sealed[key(device, port, user)] ?: return null
        return try {
            box.open(value)
        } catch (e: Exception) {
            Log.w(TAG, "cannot unseal a password: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Whether a password is kept for this login; cheap, nothing is unsealed. */
    @Synchronized
    fun has(device: String, port: Int, user: String): Boolean = key(device, port, user) in sealed

    @Synchronized
    fun remember(device: String, port: Int, user: String, password: String) =
        save(sealed + (key(device, port, user) to box.seal(password)))

    @Synchronized
    fun forget(device: String, port: Int, user: String) {
        val k = key(device, port, user)
        if (k in sealed) save(sealed - k)
    }

    @Synchronized
    fun wipe() {
        storage.delete()
        sealed = emptyMap()
    }

    private fun key(device: String, port: Int, user: String) = WireJson.encodeToString(listOf(device, port.toString(), user))

    private fun read(): Map<String, String> =
        runCatching { storage.read()?.let { WireJson.decodeFromString<Map<String, String>>(it) } }.getOrNull().orEmpty()

    private fun save(value: Map<String, String>) {
        storage.write(WireJson.encodeToString(value))
        sealed = value
    }

    private companion object {
        const val TAG = "SshPasswords"
    }
}
