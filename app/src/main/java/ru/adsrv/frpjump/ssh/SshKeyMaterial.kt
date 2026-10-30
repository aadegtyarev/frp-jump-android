package ru.adsrv.frpjump.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import java.security.SecureRandom
import java.util.Base64

/** A private key in OpenSSH format and its `authorized_keys` line. */
class SshKeyPair(val privateKey: String, val publicLine: String) {
    override fun toString() = "SshKeyPair($publicLine)"
}

/** A fresh ed25519 key pair, the private half as `ssh-keygen` writes it (unencrypted). */
fun generateEd25519(comment: String, random: SecureRandom = SecureRandom()): SshKeyPair {
    val generator = Ed25519KeyPairGenerator()
    generator.init(Ed25519KeyGenerationParameters(random))
    val pair = generator.generateKeyPair()
    val private = pair.private as Ed25519PrivateKeyParameters
    val public = pair.public as Ed25519PublicKeyParameters
    val body = Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(OpenSSHPrivateKeyUtil.encodePrivateKey(private))
    return SshKeyPair(
        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n",
        publicLine = publicKeyLine(ED25519, ed25519PublicBlob(public.encoded), comment),
    )
}

private const val PEM_LINE = 70

/**
 * Loads an OpenSSH / PEM private key the way a login will and returns its
 * `authorized_keys` line; throws when the text is not a key or the
 * passphrase is wrong.
 */
fun publicLineOfPrivateKey(privateKey: String, passphrase: String?, comment: String): String {
    val finder = passphrase?.takeIf { it.isNotEmpty() }?.let { PasswordUtils.createOneOff(it.toCharArray()) }
    val public = SSHClient().loadKeys(privateKey, null, finder).public
    return publicKeyLine(KeyType.fromKey(public).toString(), Buffer.PlainBuffer().putPublicKey(public).compactData, comment)
}
