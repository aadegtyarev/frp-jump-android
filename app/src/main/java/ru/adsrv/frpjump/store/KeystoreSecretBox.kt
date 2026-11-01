package ru.adsrv.frpjump.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The Keystore no longer has the key: whatever was sealed with it is lost for good. */
class KeyMissingException : Exception("the Keystore key is gone")

/** Seals small secrets with one key; [destroy] makes everything sealed with it unreadable. */
interface SecretBox {
    fun seal(plain: String): String

    fun open(sealed: String): String

    /** Forget the key: anything sealed with it can no longer be opened. */
    fun destroy()
}

/**
 * Seals small secrets with an AES-256-GCM key that lives in the Android
 * Keystore under [alias] and never leaves it. Sealed form:
 * `v1:<iv b64>:<ciphertext+tag b64>`.
 */
class KeystoreSecretBox(private val alias: String) : SecretBox {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    /**
     * A key the Keystore can no longer use (invalidated, unrecoverable) is
     * replaced: sealing starts over, and anything sealed with the old key was
     * unreadable anyway.
     */
    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key())
        } catch (e: KeyPermanentlyInvalidatedException) {
            destroy()
            cipher.init(Cipher.ENCRYPT_MODE, key())
        }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "v1:${b64(cipher.iv)}:${b64(sealed)}"
    }

    override fun open(sealed: String): String {
        val parts = sealed.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "unknown sealed format" }
        val key = keyStore.getKey(alias, null) as? SecretKey ?: throw KeyMissingException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, unb64(parts[1])))
        return String(cipher.doFinal(unb64(parts[2])), Charsets.UTF_8)
    }

    override fun destroy() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun key(): SecretKey {
        try {
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        } catch (e: UnrecoverableKeyException) {
            destroy()
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String) = Base64.getDecoder().decode(text)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
