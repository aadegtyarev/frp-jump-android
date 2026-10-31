package ru.adsrv.frpjump.ssh

import android.util.Log
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import java.io.IOException
import java.security.PublicKey

/** The device's host key was not accepted: [check] says why ([HostKeyCheck.Unknown] or [HostKeyCheck.Changed]). */
class HostKeyRejected(val check: HostKeyCheck, val presented: PinnedHostKey) : IOException("host key not trusted")

/**
 * One login to the SSH server behind the tunnel to [device]:[port] of
 * [profile], at `127.0.0.1:<localPort>`: the host key pinned in [hostKeys],
 * then the app's keys or a password. The terminal ([SshSession]) and the
 * file screen each make their own connection with it.
 */
class SshConnector(
    val profile: String,
    val hostKeys: HostKeyStore,
    val device: String,
    val port: Int,
    val localPort: Int,
    val user: String,
    val auth: SshAuth,
    private val env: SshEnvironment,
) {
    /** A client with keep-alives and the app's connect timeout, not connected yet. */
    fun newClient(): SSHClient = SSHClient(DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
    }

    /**
     * Connects [ssh] to the tunnel, accepting only the pinned host key.
     * Throws [HostKeyRejected] when the key is unknown or changed, any other
     * exception when the connection fails; [ssh] is then disconnected.
     */
    fun connect(ssh: SSHClient) {
        val verifier = PinningVerifier(hostKeys, device, port)
        ssh.addHostKeyVerifier(verifier)
        try {
            ssh.connect(LOOPBACK, localPort)
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            val rejected = verifier.rejected
            if (rejected != null) throw HostKeyRejected(rejected.first, rejected.second)
            throw e
        }
        ssh.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
    }

    /**
     * Logs in on a connected [ssh] with [auth]: the app's key identities, or
     * the typed (else the remembered) password, which is then remembered or
     * forgotten as the owner ticked. Throws [UserAuthException] when refused.
     */
    fun authenticate(ssh: SSHClient) {
        try {
            logIn(ssh)
        } catch (e: UserAuthException) {
            // Kept beyond this tab: Terminal and Files then do not offer a saved password just refused.
            env.refusals.record(profile, device, port, user, refusal())
            throw e
        }
        env.refusals.clear(profile, device, port, user)
    }

    private fun logIn(ssh: SSHClient) {
        when (auth) {
            SshAuth.Key -> {
                val providers = env.keys.identities(env.keyComment()).mapNotNull { identity ->
                    try {
                        ssh.loadKeys(identity.privateKey, null, identity.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) })
                    } catch (e: IOException) {
                        Log.w(TAG, "cannot load an SSH key: ${e.message}")
                        null
                    }
                }
                ssh.authPublickey(user, providers)
            }
            is SshAuth.Password -> {
                val password = auth.typed ?: env.passwords.get(profile, device, port, user)
                    ?: run {
                        // Kept but not readable (the Keystore key went), or forgotten meanwhile: not "wrong password".
                        unreadable = env.passwords.has(profile, device, port, user)
                        missing = !unreadable
                        throw UserAuthException("No readable remembered password for $user")
                    }
                ssh.authPassword(user, password)
                if (!auth.remember) {
                    env.passwords.forget(profile, device, port, user)
                } else if (auth.typed != null) {
                    env.passwords.remember(profile, device, port, user, password)
                }
            }
        }
    }

    /** The remembered password was there but could not be unsealed, or was not there at all, at the last [authenticate]. */
    @Volatile
    private var unreadable = false

    @Volatile
    private var missing = false

    /** Why the last [authenticate] was refused. A remembered password is kept: "Change login" replaces or forgets it. */
    fun refusal(): LoginRefusal = loginRefusal(
        key = auth == SshAuth.Key,
        typed = (auth as? SshAuth.Password)?.typed != null,
        remembered = !missing,
        readable = !unreadable,
    )

    /** What to tell the owner after [UserAuthException]. */
    fun authFailure(): String = when (val r = refusal()) {
        LoginRefusal.KEY -> Texts.get(loginRefusalText(r), user, Texts.get(R.string.ssh_public_key))
        else -> Texts.get(loginRefusalText(r), user)
    }

    private class PinningVerifier(
        private val store: HostKeyStore,
        private val device: String,
        private val port: Int,
    ) : HostKeyVerifier {
        /** What was refused and why, for the prompt. */
        @Volatile
        var rejected: Pair<HostKeyCheck, PinnedHostKey>? = null

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val presented = PinnedHostKey(KeyType.fromKey(key).toString(), sshFingerprint(Buffer.PlainBuffer().putPublicKey(key).compactData))
            val check = store.check(device, this.port, presented)
            if (check == HostKeyCheck.Trusted) return true
            rejected = check to presented
            return false
        }

        /** Prefer the pinned key's algorithm, so a device with several keys shows the same one. */
        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
            store.pinned(device, this.port)?.let { listOf(it.type) }.orEmpty()
    }

    private companion object {
        const val TAG = "SshConnector"
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val KEEPALIVE_SECONDS = 20
    }
}
