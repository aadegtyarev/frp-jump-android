package ru.adsrv.frpjump.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.UserAuthException
import ru.adsrv.frpjump.R
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What `ssh-copy-id` does, as one fixed POSIX sh script: makes `~/.ssh` and
 * `authorized_keys` private, then appends the key line read from stdin
 * unless a line with the same type and key is already there (whatever its
 * comment). Nothing of the key is put into the command line. No single
 * quotes in it: [keyInstallCommand] wraps it in them.
 */
val KEY_INSTALL_SCRIPT: String = listOf(
    "umask 077",
    "d=\"\$HOME/.ssh\"; f=\"\$d/authorized_keys\"",
    "mkdir -p \"\$d\" && touch \"\$f\" && chmod 700 \"\$d\" && chmod 600 \"\$f\" || exit 1",
    "read -r type blob comment",
    "[ -n \"\$blob\" ] || { echo \"no key on stdin\" >&2; exit 2; }",
    "while read -r t b rest || [ -n \"\$t\" ]; do",
    "  [ \"\$t\" = \"\$type\" ] && [ \"\$b\" = \"\$blob\" ] && exit 0",
    "done < \"\$f\"",
    "if [ -s \"\$f\" ] && [ -n \"\$(tail -c 1 \"\$f\")\" ]; then echo >> \"\$f\"; fi",
    "printf \"%s\\n\" \"\$type \$blob\${comment:+ \$comment}\" >> \"\$f\"",
).joinToString("\n")

/** The exec request: the script under `sh` whatever the login shell is, as `ssh-copy-id` runs it. */
fun keyInstallCommand(): String = "exec sh -c '$KEY_INSTALL_SCRIPT'"

/**
 * What the script reads on stdin: [publicLine] as one line. Throws
 * [IllegalArgumentException] when it is not a single `type key [comment]` line.
 */
fun keyInstallInput(publicLine: String): String {
    val line = publicLine.trim()
    require('\n' !in line && '\r' !in line) { "the key is not one line" }
    require(line.split(' ').filter { it.isNotEmpty() }.size >= 2) { "not an authorized_keys line" }
    return line + "\n"
}

/** How "Use key login" ended. */
sealed interface KeyInstallResult {
    /** The key is on the device and a key login worked; the app now logs in with it. */
    data object Installed : KeyInstallResult

    /** No open session and no remembered password ([refused] false), or the password was refused. */
    data class PasswordNeeded(val refused: Boolean) : KeyInstallResult

    /** The device's host key must be trusted (or the changed one dealt with) first. */
    data class HostKey(val check: HostKeyCheck, val presented: PinnedHostKey) : KeyInstallResult

    /** The key was written, yet a key login was refused (sshd forbids keys, or reads them elsewhere). */
    data object KeyRefused : KeyInstallResult

    /** The script failed: [detail] is the first line it wrote to stderr, else its exit status. */
    data class CommandFailed(val detail: String) : KeyInstallResult

    data class ConnectionFailed(val detail: String) : KeyInstallResult

    /** Key login works, but the app could not save that it should use it. */
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

/** What the owner is told of a final result: a text resource and its arguments. */
data class KeyInstallText(val res: Int, val args: List<String>, val ok: Boolean)

/** The text for a final result; null for those that ask something instead (password, host key). */
fun keyInstallText(result: KeyInstallResult, user: String): KeyInstallText? = when (result) {
    KeyInstallResult.Installed -> KeyInstallText(R.string.key_install_done, emptyList(), ok = true)
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
 * login), checks that a key login works, and only then makes Key the
 * remembered method and forgets the password. Anything short of that
 * leaves the app's logins as they were.
 */
class KeyInstaller(private val env: SshEnvironment, private val sessions: SshSessions) {
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
        val input = keyInstallInput(env.keys.publicLine(env.keyComment()))
        val live = if (typed == null) sessions.liveClient(t.profile, t.device, t.port, t.user) else null
        val password = if (live == null) typed ?: env.passwords.get(t.profile, t.device, t.port, t.user) else null
        if (live == null && password == null) return KeyInstallResult.PasswordNeeded(refused = false)
        val localPort = t.localPort()
        val connector = SshConnector(t.profile, t.hostKeys, t.device, t.port, localPort, t.user, SshAuth.Key, env)
        if (live != null) {
            runScript(live, input)?.let { return it }
        } else {
            val ssh = connector.newClient()
            try {
                connector.connect(ssh)
                try {
                    // Not through the connector: it would remember or forget the password.
                    ssh.authPassword(t.user, password)
                } catch (e: UserAuthException) {
                    return KeyInstallResult.PasswordNeeded(refused = true)
                }
                runScript(ssh, input)?.let { return it }
            } finally {
                runCatching { ssh.disconnect() }
            }
        }
        val check = connector.newClient()
        try {
            connector.connect(check)
            connector.authenticate(check)
        } catch (e: UserAuthException) {
            return KeyInstallResult.KeyRefused
        } finally {
            runCatching { check.disconnect() }
        }
        return try {
            t.logins.remember(t.device, t.port, SshLogin(t.user, SshAuthMethod.KEY))
            env.passwords.forget(t.profile, t.device, t.port, t.user)
            KeyInstallResult.Installed
        } catch (e: Exception) {
            KeyInstallResult.SaveFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Runs the script on [ssh] with the key on stdin; null when it succeeded. */
    private fun runScript(ssh: SSHClient, input: String): KeyInstallResult? =
        ssh.startSession().use { session ->
            val command = session.exec(keyInstallCommand())
            command.outputStream.apply {
                write(input.toByteArray())
                flush()
                close()
            }
            command.join(SCRIPT_TIMEOUT_S, TimeUnit.SECONDS)
            val stderr = command.errorStream.bufferedReader().use { it.readText() }
            commandFailure(command.exitStatus, stderr)
        }

    private companion object {
        const val SCRIPT_TIMEOUT_S = 30L
    }
}
