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
 * The SSH user's tmux sessions, one line each: the name, then who opened it ([OPENED_BY_OPTION], empty for the owner's own). No tmux or no
 * server prints nothing on stdout (the error goes to stderr): nothing to pick up.
 */
const val TMUX_LIST_COMMAND = "tmux list-sessions -F '#{session_name} #{$OPENED_BY_OPTION}'"

/** This install's sessions in a listing: the owner's tabs' ([owner]) and every one of them ([all], also the assistant's), by their number. */
class OwnSessions(val owner: List<String>, val all: List<String>) {
    /** The assistant's sessions among [all]. */
    val assistant: List<String> get() = all - owner.toSet()
}

/**
 * This install's session names in the output of [TMUX_LIST_COMMAND], each once, by their number (`-2` before `-10`), split by who opened
 * them; every other line (another install's, the owner's own, a login script's chatter) is left out. A line that is only a name (an
 * older listing) is the owner's.
 */
fun ownTmuxListing(output: String, install: String): OwnSessions {
    val prefix = "frpjump-$install-"
    val lines = output.lineSequence().map { it.trim().split(' ', limit = 2) }
        .filter { isTmuxName(it[0]) && it[0].startsWith(prefix) }
        .distinctBy { it[0] }.sortedBy { tmuxNumber(it[0]) }.toList()
    return OwnSessions(
        owner = lines.filter { SessionOpener.fromWire(it.getOrNull(1)?.trim()) == SessionOpener.OWNER }.map { it[0] },
        all = lines.map { it[0] },
    )
}

/** The tmux names a restore keeps new tabs from taking: every session of this install in [listing], the assistant's too (a tab that `-A`s into one would take it over). */
fun namesToReserve(listing: OwnSessions): List<String> = listing.all

/** The owner's tabs' sessions of this install in [output]; see [ownTmuxListing]. */
fun ownTmuxSessions(output: String, install: String): List<String> = ownTmuxListing(output, install).owner

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
 * [tmuxKillAllCommand], then the sessions left ([TMUX_LIST_COMMAND]), in
 * one exec. The exit status of a `;` chain is only its last command's, and
 * a kill of a session already gone fails too; what is still listed is
 * what did not end ([stillRunning]).
 */
fun tmuxKillAllAndListCommand(names: List<String>): String = tmuxKillAllCommand(names) + "; " + TMUX_LIST_COMMAND

/** Of [names] to end, those the output of [tmuxKillAllAndListCommand] still lists. */
fun stillRunning(output: String, names: List<String>): List<String> {
    val listed = output.lineSequence().map { it.trim().substringBefore(' ') }.toSet()
    return names.filter { it in listed }
}

/** A tab of the app as "Close all sessions of this phone" sees it. */
data class CloseAllTab(
    val id: Int,
    /** Its tmux session, when it has one. */
    val name: String?,
    /** To the device and port being closed (same profile). */
    val here: Boolean,
    /** Already leaving with an Undo of its own on screen. */
    val hidden: Boolean,
)

/**
 * What "Close all sessions of this phone" does on one device and port:
 * close [tabs] (those shown there); [orphans] are the sessions found that
 * no tab of the app holds, each counted once on the Undo notice next to
 * the tabs; [kill] the sessions to end on the device: all found except
 * those a tab that stays holds (another port or profile reaching the
 * same device and user), which would otherwise die under it.
 */
data class CloseAllPlan(val tabs: List<Int>, val orphans: List<String>, val kill: List<String>) {
    /** What the Undo notice counts: the tabs and the sessions without one. */
    val count: Int get() = tabs.size + orphans.size
}

/** See [CloseAllPlan]: [tabs] every tab of the app, [found] this install's sessions on the device for the user. */
fun closeAllPlan(tabs: List<CloseAllTab>, found: List<String>): CloseAllPlan {
    val held = tabs.mapNotNull { it.name }.toSet()
    val staying = tabs.filter { !it.here && !it.hidden }.mapNotNull { it.name }.toSet()
    return CloseAllPlan(
        tabs = tabs.filter { it.here && !it.hidden }.map { it.id },
        orphans = found.filterNot { it in held },
        kill = found.filterNot { it in staying },
    )
}

/**
 * The names a pick-up hands out, until their tabs exist: two pick-ups
 * (two first tabs at once) never take the same session. Thread-safe.
 */
class PickUpClaims(private val names: TmuxNames) {
    private val waiting = mutableSetOf<String>()

    /**
     * Of [found], those neither [held] by a tab nor handed out already;
     * the first is [adopt]ed by the tab that listed them. Empty when none
     * is free or the tab already has a name. The counter moves past them
     * in the same step.
     */
    @Synchronized
    fun claim(found: List<String>, held: () -> Set<String>, adopt: (String) -> Boolean): List<String> {
        val taken = held()
        val free = found.filterNot { it in taken || it in waiting }
        if (free.isEmpty() || !adopt(free.first())) return emptyList()
        waiting += free.drop(1)
        names.reserve(free)
        return free
    }

    /** [done] are tabs now, or not wanted: free for a later pick-up. */
    @Synchronized
    fun release(done: List<String>) {
        waiting -= done.toSet()
    }
}

/** The most a listing or a kill-all exec may take. */
private const val EXEC_TIMEOUT_S = 10L

/** A list of a few hundred names fits; anything past this is not read. */
private const val LIST_MAX_BYTES = 64 * 1024

/** What an exec gave: standard output and error (both bounded) and the exit status, null when the remote side sent none. */
class ExecResult(val stdout: String, val stderr: String, val exitStatus: Int?)

/**
 * Runs [command] on an exec channel of [ssh] and returns its standard
 * output (bounded); throws [IOException] when it did not finish in time.
 * Blocking: an IO thread.
 */
fun execOutput(ssh: SSHClient, command: String): String = execResult(ssh, command).stdout

/** [execOutput] with the error output and the exit status as well. */
fun execResult(ssh: SSHClient, command: String, beforeExec: () -> Unit = {}): ExecResult =
    ssh.startSession().use { s ->
        beforeExec()
        val exec = s.exec(command)
        // As the probe: the output waits in the channel's window until read; no answer is no answer.
        exec.join(EXEC_TIMEOUT_S, TimeUnit.SECONDS)
        if (exec.isOpen) throw IOException("exec did not finish")
        val out = readUpTo(exec.inputStream, LIST_MAX_BYTES)
        val err = runCatching { readUpTo(exec.errorStream, ERR_MAX_BYTES) }.getOrDefault("")
        ExecResult(out, err, exec.exitStatus)
    }

/**
 * The sessions a `tmux list-sessions` ([TMUX_LIST_COMMAND], or a chain ending with it) really listed: its output when it exited 0, none
 * when tmux said there is no server to ask ("no server running", "error connecting to": nothing runs), and null for any other outcome (no
 * exit status, tmux missing, a failure of the command): the listing says nothing then, and nothing may be concluded or forgotten from it.
 */
fun tmuxListing(result: ExecResult): String? = when {
    result.exitStatus == 0 -> result.stdout
    result.exitStatus != null && ("no server running" in result.stderr || "error connecting to" in result.stderr) -> ""
    else -> null
}

private const val ERR_MAX_BYTES = 4 * 1024
