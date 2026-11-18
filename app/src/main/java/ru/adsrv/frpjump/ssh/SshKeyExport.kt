package ru.adsrv.frpjump.ssh

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The phone key's export file and its reading (К4): an OpenSSH private key, `openssh-key-v1`, encrypted the way
 * `ssh-keygen -p` writes it: bcrypt KDF (16-byte salt, 16 rounds) and aes256-ctr. Only ed25519.
 *
 * Nothing here logs, copies to a clipboard or shares anything; every byte array that holds a secret (the passphrase
 * as bytes, the derived key, the plaintext section, the seed) comes from a [Scratch] and is zeroed when the call
 * ends, success or not. The passphrase array the caller passes is zeroed too. What cannot be zeroed: the immutable
 * `String` of the unencrypted key text the store hands over and takes (the same as for a generated key), and the
 * copies a cipher keeps inside itself.
 */
object SshKeyExport {
    const val CIPHER = "aes256-ctr"
    const val KDF = "bcrypt"
    const val SALT_BYTES = 16
    const val ROUNDS = 16

    /** A file asking for more is refused rather than computed: it would hang the phone. */
    private const val MAX_ROUNDS = 1024
    private const val MAX_SALT = 64
    private const val MAGIC = "openssh-key-v1"
    private const val BLOCK = 16
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 16
    private const val PEM_LINE = 70
    private const val LABEL = "OPENSSH PRIVATE KEY"
    private const val BEGIN = "-----BEGIN $LABEL-----"
    private const val END = "-----END $LABEL-----"

    /**
     * The export file for the phone key whose unencrypted OpenSSH text is [privateKeyText], encrypted with
     * [passphrase] (zeroed when this returns). Throws [KeyFileException] when the text is not an ed25519 key.
     */
    fun encryptPhoneKey(
        privateKeyText: String,
        comment: String,
        passphrase: CharArray,
        random: SecureRandom = SecureRandom(),
        rounds: Int = ROUNDS,
        scratch: Scratch = Scratch(),
    ): ByteArray = scratch.use {
        try {
            val blob = scratch.keep(armorBody(privateKeyText) ?: throw KeyFileException(KeyFileProblem.DAMAGED))
            val private = try {
                OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(blob)
            } catch (e: Exception) {
                throw KeyFileException(KeyFileProblem.DAMAGED)
            } as? Ed25519PrivateKeyParameters ?: throw KeyFileException(KeyFileProblem.NOT_ED25519)
            val seed = scratch.keep(private.encoded)
            val pub = private.generatePublicKey().encoded
            val publicBlob = ed25519PublicBlob(pub)
            val commentBytes = comment.toByteArray(StandardCharsets.UTF_8)

            // The private section, before encryption: two equal check ints, the key, its comment, padding 1, 2, 3...
            val size = 4 + 4 + (4 + ED25519.length) + (4 + 32) + (4 + 64) + (4 + commentBytes.size)
            val padded = (size + BLOCK - 1) / BLOCK * BLOCK
            val section = scratch.bytes(padded)
            val w = Writer(section)
            val check = random.nextInt()
            w.int(check)
            w.int(check)
            w.string(ED25519.toByteArray(StandardCharsets.US_ASCII))
            w.string(pub)
            w.int(64)
            w.raw(seed)
            w.raw(pub)
            w.string(commentBytes)
            var pad = 1
            while (w.pos < padded) w.raw(byteArrayOf((pad++).toByte()))

            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val (key, iv) = derive(passphrase, salt, rounds, scratch)
            ctr(key, iv, section, section.size, scratch)

            val kdfOptions = ByteArray(4 + salt.size + 4)
            Writer(kdfOptions).apply { string(salt); int(rounds) }
            val out = ByteArray(MAGIC.length + 1 + (4 + CIPHER.length) + (4 + KDF.length) + (4 + kdfOptions.size) + 4 + (4 + publicBlob.size) + 4 + section.size)
            Writer(out).apply {
                raw(MAGIC.toByteArray(StandardCharsets.US_ASCII)); raw(byteArrayOf(0))
                string(CIPHER.toByteArray(StandardCharsets.US_ASCII))
                string(KDF.toByteArray(StandardCharsets.US_ASCII))
                string(kdfOptions)
                int(1)
                string(publicBlob)
                string(section)
            }
            armor(out)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /** What a file is, read without its passphrase. */
    class Header(val encrypted: Boolean)

    /**
     * Whether [file] is an OpenSSH ed25519 key and whether it needs a passphrase. Throws [KeyFileException]:
     * NOT_ED25519 (with the type when the file says it), DAMAGED, UNSUPPORTED (a cipher or KDF other than ssh-keygen's default).
     */
    fun inspect(file: ByteArray): Header {
        Scratch().use { scratch ->
            val c = container(file, scratch)
            return Header(c.cipher != "none")
        }
    }

    /**
     * The key in [file], opened with [passphrase] (zeroed when this returns; ignored by a file with no cipher). The
     * public line carries [comment]. Throws [KeyFileException]: WRONG_PASSPHRASE, DAMAGED, NOT_ED25519, UNSUPPORTED.
     */
    fun decryptEd25519(file: ByteArray, passphrase: CharArray, comment: String, scratch: Scratch = Scratch()): SshKeyPair = scratch.use {
        try {
            val c = container(file, scratch)
            val section = scratch.keep(c.section.copyOf())
            if (c.cipher != "none") {
                val (key, iv) = derive(passphrase, c.salt, c.rounds, scratch)
                ctr(key, iv, section, section.size, scratch)
            }
            val r = Reader(section)
            val wrong = if (c.cipher == "none") KeyFileProblem.DAMAGED else KeyFileProblem.WRONG_PASSPHRASE
            if (section.size < 8 || section.size % 8 != 0) throw KeyFileException(KeyFileProblem.DAMAGED)
            if (r.int() != r.int()) throw KeyFileException(wrong)
            // Past the check ints the passphrase was right (or the file is damaged): the rest is structure.
            val type = String(r.string(scratch), StandardCharsets.US_ASCII)
            if (type != ED25519) throw KeyFileException(KeyFileProblem.DAMAGED)
            val pub = r.string(scratch)
            val both = r.string(scratch)
            r.string(scratch) // the file's own comment: the phone's is used
            var pad = 1
            while (r.left > 0) if (r.byte() != (pad++).toByte()) throw KeyFileException(KeyFileProblem.DAMAGED)
            if (pub.size != 32 || both.size != 64) throw KeyFileException(KeyFileProblem.DAMAGED)
            val seed = scratch.keep(both.copyOfRange(0, 32))
            val private = Ed25519PrivateKeyParameters(seed, 0)
            val derived = private.generatePublicKey()
            if (!MessageDigest.isEqual(derived.encoded, pub) || !MessageDigest.isEqual(both.copyOfRange(32, 64), pub) ||
                !MessageDigest.isEqual(ed25519PublicBlob(pub), c.publicBlob)
            ) {
                throw KeyFileException(KeyFileProblem.DAMAGED)
            }
            ed25519KeyPair(private, derived, comment)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private class Container(val cipher: String, val salt: ByteArray, val rounds: Int, val publicBlob: ByteArray, val section: ByteArray)

    private fun container(file: ByteArray, scratch: Scratch): Container {
        val text = String(file, StandardCharsets.UTF_8)
        val blob = armorBody(text) ?: throw notOpenSsh(text)
        scratch.keep(blob)
        val r = Reader(blob)
        val magic = MAGIC.toByteArray(StandardCharsets.US_ASCII)
        if (r.left < magic.size + 1 || !blob.copyOfRange(0, magic.size).contentEquals(magic) || blob[magic.size] != 0.toByte()) {
            throw KeyFileException(KeyFileProblem.DAMAGED)
        }
        r.skip(magic.size + 1)
        val cipher = String(r.string(scratch), StandardCharsets.US_ASCII)
        val kdf = String(r.string(scratch), StandardCharsets.US_ASCII)
        val options = Reader(r.string(scratch))
        val keys = r.int()
        if (keys != 1) throw KeyFileException(KeyFileProblem.UNSUPPORTED)
        val publicBlob = r.string(scratch)
        val section = r.string(scratch)
        // The public half names the type, so another type is refused before any passphrase is asked.
        val pr = Reader(publicBlob)
        val type = String(pr.string(scratch), StandardCharsets.US_ASCII)
        if (type != ED25519) throw KeyFileException(KeyFileProblem.NOT_ED25519, type)
        if (pr.string(scratch).size != 32 || pr.left != 0) throw KeyFileException(KeyFileProblem.DAMAGED)
        if (cipher == "none") {
            if (kdf != "none") throw KeyFileException(KeyFileProblem.UNSUPPORTED)
            return Container(cipher, ByteArray(0), 0, publicBlob, section)
        }
        if (cipher != CIPHER || kdf != KDF) throw KeyFileException(KeyFileProblem.UNSUPPORTED)
        val salt = options.string(scratch)
        val rounds = options.int()
        if (options.left != 0 || salt.size !in 1..MAX_SALT || rounds !in 1..MAX_ROUNDS) throw KeyFileException(KeyFileProblem.UNSUPPORTED)
        if (section.size % BLOCK != 0) throw KeyFileException(KeyFileProblem.DAMAGED)
        return Container(cipher, salt, rounds, publicBlob, section)
    }

    /** A file in another format: its PEM label says the type when it can; anything else is no key file. */
    private fun notOpenSsh(text: String): KeyFileException {
        val label = Regex("^\\s*-----BEGIN ([A-Z ]+)-----").find(text)?.groupValues?.get(1) ?: return KeyFileException(KeyFileProblem.DAMAGED)
        return when (label) {
            "OPENSSH PRIVATE KEY" -> KeyFileException(KeyFileProblem.DAMAGED) // armor without a whole body
            "RSA PRIVATE KEY" -> KeyFileException(KeyFileProblem.NOT_ED25519, "ssh-rsa")
            "DSA PRIVATE KEY" -> KeyFileException(KeyFileProblem.NOT_ED25519, "ssh-dss")
            "EC PRIVATE KEY" -> KeyFileException(KeyFileProblem.NOT_ED25519, "ecdsa")
            else -> KeyFileException(KeyFileProblem.NOT_ED25519)
        }
    }

    /** The passphrase through bcrypt_pbkdf into the cipher's key and counter start. */
    private fun derive(passphrase: CharArray, salt: ByteArray, rounds: Int, scratch: Scratch): Pair<ByteArray, ByteArray> {
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val password = scratch.bytes(encoded.remaining())
        encoded.get(password)
        if (encoded.hasArray()) scratch.keep(encoded.array())
        val out = scratch.bytes(KEY_BYTES + IV_BYTES)
        BCrypt().pbkdf(password, salt, rounds, out)
        return scratch.keep(out.copyOfRange(0, KEY_BYTES)) to scratch.keep(out.copyOfRange(KEY_BYTES, out.size))
    }

    /** AES-256 in counter mode over the first [length] bytes of [data], in place; the same call encrypts and decrypts. */
    private fun ctr(key: ByteArray, iv: ByteArray, data: ByteArray, length: Int, scratch: Scratch) {
        val cipher = SICBlockCipher(AESEngine.newInstance())
        cipher.init(true, ParametersWithIV(KeyParameter(key), iv))
        val out = scratch.bytes(length)
        cipher.processBytes(data, 0, length, out, 0)
        System.arraycopy(out, 0, data, 0, length)
    }

    /** The bytes between the armor lines of an OpenSSH private key text; null when there are no such lines. */
    private fun armorBody(text: String): ByteArray? {
        val start = text.indexOf(BEGIN)
        val end = text.indexOf(END)
        if (start < 0 || end < start) return null
        return try {
            Base64.getMimeDecoder().decode(text.substring(start + BEGIN.length, end).trim())
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun armor(blob: ByteArray): ByteArray {
        val body = Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(blob)
        return "$BEGIN\n$body\n$END\n".toByteArray(StandardCharsets.US_ASCII)
    }

    /** SSH wire writer into a buffer sized by the caller. */
    private class Writer(private val buf: ByteArray) {
        var pos = 0

        fun int(v: Int) {
            for (shift in intArrayOf(24, 16, 8, 0)) buf[pos++] = (v ushr shift).toByte()
        }

        fun raw(b: ByteArray) {
            System.arraycopy(b, 0, buf, pos, b.size)
            pos += b.size
        }

        fun string(b: ByteArray) {
            int(b.size)
            raw(b)
        }
    }

    /** SSH wire reader; a length that runs past the end is damage. */
    private class Reader(private val buf: ByteArray) {
        private var pos = 0
        val left get() = buf.size - pos

        fun skip(n: Int) {
            if (n < 0 || n > left) throw KeyFileException(KeyFileProblem.DAMAGED)
            pos += n
        }

        fun byte(): Byte {
            if (left < 1) throw KeyFileException(KeyFileProblem.DAMAGED)
            return buf[pos++]
        }

        fun int(): Int {
            if (left < 4) throw KeyFileException(KeyFileProblem.DAMAGED)
            var v = 0
            repeat(4) { v = (v shl 8) or (buf[pos++].toInt() and 0xff) }
            return v
        }

        fun string(scratch: Scratch? = null): ByteArray {
            val n = int()
            if (n < 0 || n > left) throw KeyFileException(KeyFileProblem.DAMAGED)
            val out = buf.copyOfRange(pos, pos + n)
            pos += n
            return scratch?.keep(out) ?: out
        }
    }
}

/** Why a key file was refused; each has its own short message for the owner. */
enum class KeyFileProblem { WRONG_PASSPHRASE, NOT_ED25519, DAMAGED, UNSUPPORTED }

/** A refused key file. No key material or passphrase is ever in it: [keyType] is the public type name the file shows (`ssh-rsa`). */
class KeyFileException(val problem: KeyFileProblem, val keyType: String? = null) : Exception(problem.name)

/**
 * Byte arrays that hold secrets, zeroed together when the call that made them ends. A seam: a test keeps the
 * instance and looks at the arrays afterwards.
 */
class Scratch : AutoCloseable {
    private val held = mutableListOf<ByteArray>()

    /** The arrays made or kept so far (a test reads them after [close]). */
    val tracked: List<ByteArray> get() = held

    fun bytes(size: Int): ByteArray = keep(ByteArray(size))

    fun keep(array: ByteArray): ByteArray {
        held += array
        return array
    }

    override fun close() {
        held.forEach { it.fill(0) }
    }
}
