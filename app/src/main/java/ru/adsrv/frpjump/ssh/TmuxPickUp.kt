package ru.adsrv.frpjump.ssh

import net.schmizz.sshj.SSHClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * Picking up the app's tmux sessions after the process was killed, and
 * ending all of them on a device ("Close all sessions of this phone").
 * The device's `tmux list-sessions` is the list: tabs are not stored.
 * Only names of this install (`frpjump-<install>-<n>`) are ever taken;
 * another phone's and the owner's own sessions are left alone.
 */

/**
 * The SSH user's tmux sessions, one name per line. No tmux or no server
 * prints nothing on stdout (the error goes to stderr): nothing to pick up.
 */
const val TMUX_LIST_COMMAND = "tmux list-sessions -F '#{session_name}'"

/**
 * This install's session names in the output of [TMUX_LIST_COMMAND],
 * each once, by their number (`-2` before `-10`); every other line
 * (another install's, the owner's own, a login script's chatter) is left out.
 */
fun ownTmuxSessions(output: String, install: String): List<String> {
    val prefix = "frpjump-$install-"
    return output.lineSequence()
        .map { it.trim() }
        .filter { isTmuxName(it) && it.startsWith(prefix) }
        .distinct()
        .sortedBy { tmuxNumber(it) }
        .toList()
}

/** The `<n>` of an app's session name; [isTmuxName] holds. */
fun tmuxNumber(name: String): Long = name.substringAfterLast('-').toLongOrNull() ?: 0L

/**
 * Ends sessions [names], each by its exact target: one tmux call per name,
 * joined by `;` (the same in sh, bash, zsh and fish), so a session already
 * gone does not keep the others alive.
 */
fun tmuxKillAllCommand(names: List<String>): String {
    require(names.isNotEmpty()) { "no sessions to end" }
    return names.joinToString("; ") { tmuxKillCommand(it) }
}

/**
 * What "Close all sessions of this phone" does on one device and port:
 * close the [tabs] shown there; end the sessions [found] on the device
 * ([kill], all of them); [orphans] are those of them no tab of the app
 * holds ([tabNames], hidden tabs included), each counted once on the
 * Undo notice next to the tabs.
 */
data class CloseAllPlan(val tabs: List<Int>, val orphans: List<String>, val kill: List<String>) {
    /** What the Undo notice counts: the tabs and the sessions without one. */
    val count: Int get() = tabs.size + orphans.size
}

/** See [CloseAllPlan]: [shownTabs] are the tab ids shown to that device and port, [tabNames] the tmux names of every tab of the app. */
fun closeAllPlan(shownTabs: List<Int>, tabNames: Set<String>, found: List<String>): CloseAllPlan =
    CloseAllPlan(shownTabs, found.filterNot { it in tabNames }, found)

/** The most a listing or a kill-all exec may take. */
private const val EXEC_TIMEOUT_S = 10L

/** A list of a few hundred names fits; anything past this is not read. */
private const val LIST_MAX_BYTES = 64 * 1024

/**
 * Runs [command] on an exec channel of [ssh] and returns its standard
 * output (bounded); throws [IOException] when it did not finish in time.
 * Blocking: an IO thread.
 */
fun execOutput(ssh: SSHClient, command: String): String =
    ssh.startSession().use { s ->
        val exec = s.exec(command)
        // As the probe: the output waits in the channel's window until read; no answer is no answer.
        exec.join(EXEC_TIMEOUT_S, TimeUnit.SECONDS)
        if (exec.isOpen) throw IOException("exec did not finish")
        readUpTo(exec.inputStream, LIST_MAX_BYTES)
    }
