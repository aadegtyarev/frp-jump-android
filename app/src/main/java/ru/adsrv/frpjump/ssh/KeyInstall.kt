package ru.adsrv.frpjump.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import ru.adsrv.frpjump.R
import java.io.Closeable
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * What `ssh-copy-id` does, as one fixed POSIX sh script, on one line (a
 * csh or tcsh login shell takes no newlines in a quoted word): reads the
 * key line from stdin; when a line with the same type and key is there
 * already (whatever its comment) it ends at once and touches nothing.
 * Otherwise it makes `~/.ssh` and `authorized_keys` if they are missing,
 * makes them private (not through a symlink, and a chmod that fails does
 * not stop it) and appends the key. Nothing of the key is in the command
 * line. No single quotes in it: [keyInstallCommand] wraps it in them.
 */
val KEY_INSTALL_SCRIPT: String = listOf(
    "umask 077",
    "d=\"\$HOME/.ssh\"",
    "f=\"\$d/authorized_keys\"",
    "read -r type blob comment",
    "[ -n \"\$blob\" ] || { echo \"no key on stdin\" >&2; exit 2; }",
    "if [ -f \"\$f\" ]; then while read -r t b rest || [ -n \"\$t\" ]; do [ \"\$t\" = \"\$type\" ] && [ \"\$b\" = \"\$blob\" ] && exit 0; done < \"\$f\"; fi",
    "[ -d \"\$d\" ] || mkdir -p \"\$d\" || exit 1",
    "[ -h \"\$d\" ] || chmod 700 \"\$d\" || true",
    "[ -e \"\$f\" ] || touch \"\$f\" || exit 1",
    "[ -h \"\$f\" ] || chmod 600 \"\$f\" || true",
    "if [ -s \"\$f\" ] && [ -n \"\$(tail -c 1 \"\$f\")\" ]; then echo >> \"\$f\" || exit 1; fi",
    "printf \"%s\\n\" \"\$type \$blob\${comment:+ \$comment}\" >> \"\$f\" || exit 1",
).joinToString("; ")

/** The exec request: the script under `sh` whatever the login shell is, as `ssh-copy-id` runs it. */
fun keyInstallCommand(): String = "exec sh -c '$KEY_INSTALL_SCRIPT'"

/** Key types an `authorized_keys` line of this app may have: the app's ed25519 and what an import can be. */
private fun knownKeyType(type: String): Boolean =
    type == "ssh-ed25519" || type == "ssh-rsa" || type.startsWith("ecdsa-sha2-") || type.startsWith("sk-")

private val BASE64 = Regex("[A-Za-z0-9+/]+={0,2}")

/**
 * What the script reads on stdin: [publicLine] as one line. Throws
 * [IllegalArgumentException] unless it is `type key [comment]` with a
 * known type, a base64 key and no control characters.
 */
fun keyInstallInput(publicLine: String): String {
    val line = publicLine.trim()
    require(line.none { it.isISOControl() }) { "the key line has control characters" }
    val parts = line.split(' ').filter { it.isNotEmpty() }
    require(parts.size >= 2) { "not an authorized_keys line" }
    require(knownKeyType(parts[0])) { "unknown key type ${parts[0].take(MAX_DETAIL)}" }
    require(BASE64.matches(parts[1]) && runCatching { Base64.getDecoder().decode(parts[1]) }.isSuccess) { "the key is not base64" }
    return line + "\n"
}

/** How "Use key login" ended. */
sealed interface KeyInstallResult {
    /** The key is on the device and a key login worked; the app now logs in with it. */
    data object Installed : KeyInstallResult

    /** As [Installed], but the remembered password could not be forgotten ([detail]). */
    data class InstalledPasswordKept(val detail: String) : KeyInstallResult

    /** No open session and no remembered password ([refusal] null), or why the password was not taken. */
    data class PasswordNeeded(val refusal: LoginRefusal? = null) : KeyInstallResult

    /** The device's host key must be trusted (or the changed one dealt with) first. */
    data class HostKey(val check: HostKeyCheck, val presented: PinnedHostKey) : KeyInstallResult

    /** The key was written, yet a key login was refused (sshd forbids keys, or reads them elsewhere). */
    data object KeyRefused : KeyInstallResult

    /** The script failed: [detail] is the first line it wrote to stderr, its exit status, or that it timed out. */
    data class CommandFailed(val detail: String) : KeyInstallResult

    data class ConnectionFailed(val detail: String) : KeyInstallResult

    /** Key login works, but the app could not save that it should use it: nothing changed. */
    data class SaveFailed(val detail: String) : KeyInstallResult
}

/**
 * The script's end as a result: null when it succeeded ([status] 0), else
 * [KeyInstallResult.CommandFailed] with the first non-blank line of
 * [stderr], or the exit status when it said nothing.
 */
fun commandFailure(status: Int?, stderr: String): KeyInstallResult.CommandFailed? {
    if (status == 0) return null
    val said = stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(MAX_DETAIL)
    return KeyInstallResult.CommandFailed(said ?: if (status != null) "exit status $status" else "no exit status")
}

/** What a script that has not ended within its time says. */
const val SCRIPT_TIMED_OUT = "timed out"

/**
 * Waits for the script ([finished]: true once it ended in time) and only
 * then reads its [stderr]; one still running is [close]d and reported
 * as timed out, its stderr never read (that read would block).
 */
fun scriptOutcome(finished: () -> Boolean, status: () -> Int?, stderr: () -> String, close: () -> Unit): KeyInstallResult.CommandFailed? {
    if (!finished()) {
        close()
        return KeyInstallResult.CommandFailed(SCRIPT_TIMED_OUT)
    }
    return commandFailure(status(), stderr())
}

/** What the owner is told of a final result: a text resource and its arguments. */
data class KeyInstallText(val res: Int, val args: List<String>, val ok: Boolean)

/** The text for a final result; null for those that ask something instead (password, host key). */
fun keyInstallText(result: KeyInstallResult, user: String): KeyInstallText? = when (result) {
    KeyInstallResult.Installed -> KeyInstallText(R.string.key_install_done, emptyList(), ok = true)
    is KeyInstallResult.InstalledPasswordKept -> KeyInstallText(R.string.key_install_done_password_kept, listOf(result.detail), ok = true)
    KeyInstallResult.KeyRefused -> KeyInstallText(R.string.key_install_refused, listOf(user), ok = false)
    is KeyInstallResult.CommandFailed -> KeyInstallText(R.string.key_install_command_failed, listOf(result.detail), ok = false)
    is KeyInstallResult.ConnectionFailed -> KeyInstallText(R.string.key_install_connection_failed, listOf(result.detail), ok = false)
    is KeyInstallResult.SaveFailed -> KeyInstallText(R.string.key_install_save_failed, listOf(result.detail), ok = false)
    is KeyInstallResult.PasswordNeeded, is KeyInstallResult.HostKey -> null
}

private const val MAX_DETAIL = 200

/** Where to install the key: a login of [profile] to [device]:[port] as [user]. */
class KeyInstallTarget(
    val profile: String,
    val hostKeys: HostKeyStore,
    val device: String,
    val port: Int,
    val user: String,
    val logins: SshLoginMemory,
    /** The tunnel's local port, brought up if needed; throws [IOException] saying why there is none. */
    val localPort: suspend () -> Int,
) {
    fun withUser(other: String) = KeyInstallTarget(profile, hostKeys, device, port, other, logins, localPort)
}

/**
 * "Use key login": puts the phone's key into the device's
 * `authorized_keys` over an open session's connection (else a password
 * login), checks that a login with that key works, and only then makes Key
 * the remembered method and forgets the password. Anything short of that
 * leaves the app's logins as they were.
 */
class KeyInstaller(private val env: SshEnvironment, private val sessions: SshSessions) {
    /** What the run going now has open: closed by [cancel]. */
    private val open = mutableListOf<Closeable>()

    /** "Cancel" while it works: closes its connections (never an open session's), which ends the blocked call. */
    fun cancel() {
        val now = synchronized(open) { open.toList().also { open.clear() } }
        now.forEach { runCatching { it.close() } }
    }

    private fun <T : Closeable> hold(c: T): T = c.also { synchronized(open) { open += it } }

    private fun drop(c: Closeable) {
        synchronized(open) { open -= c }
        runCatching { c.close() }
    }

    /** [typed] null: an open session's connection, else the remembered password. Runs on the IO dispatcher. */
    suspend fun install(target: KeyInstallTarget, typed: String?): KeyInstallResult = withContext(Dispatchers.IO) {
        try {
            run(target, typed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HostKeyRejected) {
            KeyInstallResult.HostKey(e.check, e.presented)
        } catch (e: Exception) {
            KeyInstallResult.ConnectionFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun run(t: KeyInstallTarget, typed: String?): KeyInstallResult {
        val comment = env.keyComment()
        val publicLine = env.keys.publicLine(comment)
        val input = try {
            keyInstallInput(publicLine)
        } catch (e: IllegalArgumentException) {
            return KeyInstallResult.CommandFailed(e.message ?: "bad key line")
        }
        val live = if (typed == null) sessions.liveClient(t.profile, t.device, t.port, t.user) else null
        val remembered = if (live == null && typed == null) env.passwords.get(t.profile, t.device, t.port, t.user) else null
        val password = typed ?: remembered
        if (live == null && password == null) {
            // Kept but not readable on this phone: say so, rather than just ask.
            val unreadable = typed == null && env.passwords.has(t.profile, t.device, t.port, t.user)
            return KeyInstallResult.PasswordNeeded(LoginRefusal.PASSWORD_UNREADABLE.takeIf { unreadable })
        }
        val localPort = t.localPort()
        val connector = SshConnector(t.profile, t.hostKeys, t.device, t.port, localPort, t.user, SshAuth.Key, env)
        if (live != null) {
            runScript(live, input)?.let { return it }
        } else {
            val ssh = hold(connector.newClient())
            try {
                connector.connect(ssh)
                try {
                    // Not through the connector: it would remember the password.
                    ssh.authPassword(t.user, password)
                } catch (e: UserAuthException) {
                    // A remembered one is kept, as the terminal keeps it: the dialog asks, and success forgets it anyway.
                    return KeyInstallResult.PasswordNeeded(if (remembered != null) LoginRefusal.PASSWORD_SAVED else LoginRefusal.PASSWORD_TYPED)
                }
                runScript(ssh, input)?.let { return it }
            } finally {
                drop(ssh)
            }
        }
        currentCoroutineContext().ensureActive()
        if (!keyLogs(connector, t.user, publicLine, comment)) return KeyInstallResult.KeyRefused
        // Cancelled meanwhile: nothing is saved.
        currentCoroutineContext().ensureActive()
        try {
            t.logins.remember(t.device, t.port, SshLogin(t.user, SshAuthMethod.KEY))
        } catch (e: Exception) {
            return KeyInstallResult.SaveFailed(e.message ?: e.javaClass.simpleName)
        }
        return try {
            env.passwords.forget(t.profile, t.device, t.port, t.user)
            KeyInstallResult.Installed
        } catch (e: Exception) {
            KeyInstallResult.InstalledPasswordKept(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Whether a fresh login with the installed key ([publicLine]) only, not an imported one, is accepted. */
    private fun keyLogs(connector: SshConnector, user: String, publicLine: String, comment: String): Boolean {
        val identity = env.keys.identities(comment).firstOrNull { it.publicLine == publicLine }
            ?: throw IOException("the phone's key cannot be read")
        val check = hold(connector.newClient())
        try {
            connector.connect(check)
            val key = check.loadKeys(identity.privateKey, null, identity.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) })
            check.authPublickey(user, key)
            return true
        } catch (e: UserAuthException) {
            return false
        } finally {
            drop(check)
        }
    }

    /** Runs the script on [ssh] with the key on stdin; null when it succeeded. */
    private fun runScript(ssh: SSHClient, input: String): KeyInstallResult? {
        val session = hold(ssh.startSession())
        try {
            val command = session.exec(keyInstallCommand())
            command.outputStream.apply {
                write(input.toByteArray())
                flush()
                close()
            }
            return scriptOutcome(
                finished = {
                    try {
                        command.join(SCRIPT_TIMEOUT_S, TimeUnit.SECONDS)
                        !command.isOpen
                    } catch (e: ConnectionException) {
                        false
                    }
                },
                status = { command.exitStatus },
                stderr = { command.errorStream.bufferedReader().use { it.readText() } },
                close = { runCatching { session.close() } },
            )
        } finally {
            drop(session)
        }
    }

    private companion object {
        const val SCRIPT_TIMEOUT_S = 30L
    }
}
