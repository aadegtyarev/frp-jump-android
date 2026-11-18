package ru.adsrv.frpjump.ssh

import net.schmizz.sshj.connection.channel.direct.Session
import java.io.IOException

/*
 * How the app introduces itself to the machine it logs in to: iTerm2's
 * convention, `LC_TERMINAL` and `LC_TERMINAL_VERSION`. The `LC_` prefix is the
 * one stock sshd's `AcceptEnv LC_*` lets through. The values are constants of
 * the build: no profile, device, user or other data of the owner.
 */

/** The terminal's name, as the remote machine sees it in `$LC_TERMINAL`. */
const val TERMINAL_NAME = "frp-jump"

const val ENV_TERMINAL = "LC_TERMINAL"
const val ENV_TERMINAL_VERSION = "LC_TERMINAL_VERSION"

/** The two variables, for the app version [version] (its `versionName`). */
fun terminalIdentity(version: String): List<Pair<String, String>> =
    listOf(ENV_TERMINAL to TERMINAL_NAME, ENV_TERMINAL_VERSION to version)

/**
 * Sends the identity as `env` requests on [session], before its shell or exec.
 * A server that refuses one (no `AcceptEnv`, or a limit of its own) is ignored
 * silently: the login goes on as without it. The first refusal ends the sending
 * so that a server which takes none costs one round trip, not two.
 */
fun introduceTerminal(session: Session, version: String) {
    for ((name, value) in terminalIdentity(version)) {
        try {
            session.setEnvVar(name, value)
        } catch (_: IOException) {
            return
        }
    }
}

/**
 * The same variables in tmux's own session environment, so that the shell in the
 * session and every window made later have them, also on a session that already runs.
 * `set-environment` works on every tmux the app supports and only touches panes
 * made later; the first shell of a session created now is covered by `new-session -e`
 * ([tmuxNewSessionEnvFlags]), where tmux has it.
 */
internal fun tmuxSetEnvironmentCommands(target: String, version: String): String =
    terminalIdentity(version).joinToString("") { (name, value) -> " ';' set-environment -t $target ${shellQuote(name)} ${shellQuote(value)}" }

/** `-e NAME=value` for `new-session` (tmux 3.2 and later; earlier ones refuse the flag). */
internal fun tmuxNewSessionEnvFlags(version: String): String =
    terminalIdentity(version).joinToString("") { (name, value) -> " -e ${shellQuote("$name=$value")}" }

private val TMUX_VERSION = Regex("""^tmux (?:next-)?(\d+)\.(\d+)""")

/** Whether the line of `tmux -V` found in the probe's output says tmux 3.2 or later ([TmuxProbe]); anything unreadable is no. */
fun tmuxHasNewSessionEnv(probeOutput: String): Boolean {
    val m = probeOutput.lineSequence().map { it.trim() }.firstNotNullOfOrNull { TMUX_VERSION.find(it) } ?: return false
    val major = m.groupValues[1].toIntOrNull() ?: return false
    val minor = m.groupValues[2].toIntOrNull() ?: return false
    return major > 3 || (major == 3 && minor >= 2)
}
