package ru.adsrv.frpjump.ssh

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

/** OpenSSH's `SHA256:<base64 without padding>` of a public key blob (as `ssh-keygen -l` prints it). */
fun sshFingerprint(publicKeyBlob: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKeyBlob))

/** The SSH wire blob of an ed25519 public key: string "ssh-ed25519", string <32 raw bytes>. */
fun ed25519PublicBlob(raw: ByteArray): ByteArray {
    require(raw.size == 32) { "an ed25519 public key is 32 bytes" }
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        val type = ED25519.toByteArray(Charsets.US_ASCII)
        out.writeInt(type.size)
        out.write(type)
        out.writeInt(raw.size)
        out.write(raw)
    }
    return bytes.toByteArray()
}

/** An `authorized_keys` line: `<type> <base64 blob> <comment>`. */
fun publicKeyLine(type: String, blob: ByteArray, comment: String): String =
    "$type ${Base64.getEncoder().encodeToString(blob)} $comment".trim()

/** The blob of an `authorized_keys` / `.pub` line, or null when it is not one. */
fun blobOfPublicKeyLine(line: String): ByteArray? {
    val parts = line.trim().split(Regex("\\s+"))
    if (parts.size < 2) return null
    return runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull()
}

const val ED25519 = "ssh-ed25519"
