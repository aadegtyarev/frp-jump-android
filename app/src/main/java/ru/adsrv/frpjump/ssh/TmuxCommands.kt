package ru.adsrv.frpjump.ssh

/*
 * The commands a terminal tab runs on the device for tmux, each one string
 * for an SSH exec channel: the device's sshd hands it to the login user's
 * shell as `<shell> -c '<command>'`, non-login and non-interactive.
 *
 * - Every argument the app chooses is single-quoted ([shellQuote]); that
 *   reads the same in sh, bash, zsh, fish and csh. The tmux command
 *   separator is a quoted `';'`, not `\;`.
 * - `tmux` is called by name. The probe and the launch run in the same exec
 *   environment (sshd's default PATH, e.g. /usr/local/bin:/usr/bin:/bin), so
 *   when the probe finds it the launch does too; a tmux only on a
 *   login-profile PATH is not found and the tab opens a plain shell.
 * - Targets are exact: `=<name>` where tmux wants a session (has-session,
 *   kill-session), `=<name>:` where it wants a pane (set-option, if-shell,
 *   send-keys; tmux 3.1 does not find a pane by `=<name>` alone). A bare
 *   `-t frpjump-x-1` would also match `frpjump-x-10` by prefix, and
 *   kill-session must never end another tab's session.
 * - Nothing global: only `set-option -t <session>`; ~/.tmux.conf is never
 *   read or written by the app.
 */

/** A name of the app's tmux sessions: `frpjump-<install>-<n>`, lower-case hex and digits only. */
private val TMUX_NAME = Regex("frpjump-[0-9a-f]{6}-[1-9][0-9]*")

/** Whether [name] is a session name the app makes; commands are built only for those. */
fun isTmuxName(name: String): Boolean = TMUX_NAME.matches(name)

/** [s] as one shell word, single-quoted; a `'` inside becomes `'\''`. */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** What the probe found on the device. */
enum class TmuxProbe {
    /** No tmux on the exec PATH (or a shell that cannot run the probe): a plain shell. */
    ABSENT,

    /** tmux is there; the tab's session is not: the launch creates it. */
    FRESH,

    /** tmux is there and the tab's session still runs: the launch attaches to it. */
    EXISTING,
}

private const val MARK_TMUX = "frpjump-tmux"
private const val MARK_ATTACH = "frpjump-attach"

/**
 * One short exec before the tab's channel opens: whether tmux is there and
 * whether session [name] still runs. It prints only its own markers (a
 * login script's chatter or tmux's "no server running" on stderr cannot
 * pass for them); see [parseTmuxProbe].
 */
fun tmuxProbeCommand(name: String): String {
    require(isTmuxName(name)) { "not a tmux session name of the app" }
    return "command -v tmux >/dev/null && echo $MARK_TMUX && tmux has-session -t ${shellQuote("=$name")} && echo $MARK_ATTACH"
}

/** Reads the standard output of [tmuxProbeCommand]. */
fun parseTmuxProbe(output: String): TmuxProbe {
    val lines = output.lineSequence().map { it.trim() }.toSet()
    return when {
        MARK_TMUX !in lines -> TmuxProbe.ABSENT
        MARK_ATTACH in lines -> TmuxProbe.EXISTING
        else -> TmuxProbe.FRESH
    }
}

/**
 * The tab's channel, run with the PTY instead of a login shell: attach to
 * session [name] or create it (`-A`), then its own options. `exec` replaces
 * the user's shell, so tmux's exit status is the channel's. `-u`: UTF-8
 * even when the non-login exec has no LANG. TERM is the PTY's
 * (`xterm-256color`), set by sshd from the pty request.
 */
fun tmuxAttachCommand(name: String): String {
    require(isTmuxName(name)) { "not a tmux session name of the app" }
    val target = shellQuote("=$name:")
    return "exec tmux -u new-session -A -s ${shellQuote(name)}" +
        " ';' set-option -t $target status off" +
        " ';' set-option -t $target mouse on"
}

/** Ends session [name] when its tab is closed for good. */
fun tmuxKillCommand(name: String): String {
    require(isTmuxName(name)) { "not a tmux session name of the app" }
    return "tmux kill-session -t ${shellQuote("=$name")}"
}

/**
 * Leaves copy mode (or any other pane mode) of session [name], and does
 * nothing when the pane is in none: checked by tmux itself, in one round
 * trip, so nothing races between the check and the cancel.
 */
fun tmuxLeaveModeCommand(name: String): String {
    require(isTmuxName(name)) { "not a tmux session name of the app" }
    return "tmux if-shell -F -t ${shellQuote("=$name:")} '#{pane_in_mode}' ${shellQuote("send-keys -t =$name: -X cancel")}"
}

/** How a tab's connection came up, for what the owner is told. */
enum class Resumed {
    /** Opened for the first time (or by Reconnect into a fresh shell): nothing to say. */
    FIRST,

    /** Back on the tmux session it had: tmux redraws the screen; a short notice says so. */
    REATTACHED,

    /** Back after a drop with a fresh shell: the "new shell" line and bar, as without tmux. */
    NEW_SHELL,
}

/**
 * What a connection that just opened is: [dropped] it came after the
 * previous one dropped; [probe] what the probe found. A session found
 * running is re-attached whatever led here (a drop, or Reconnect after the
 * tries ran out); after a drop without it, the shell is new and says so —
 * also when tmux is there but the session died meanwhile (the device
 * rebooted).
 */
fun resumed(dropped: Boolean, probe: TmuxProbe): Resumed = when {
    probe == TmuxProbe.EXISTING -> Resumed.REATTACHED
    dropped -> Resumed.NEW_SHELL
    else -> Resumed.FIRST
}

/**
 * The tmux session to end when a tab closes, or null: only on the owner's
 * Close ([endTmux]), only of a connection that was open ([wasOpen]) and ran
 * tmux ([inUse]). A tab closed while its connection is down keeps its
 * session on the device; so does one closed with its tunnel.
 */
fun tmuxToKill(endTmux: Boolean, wasOpen: Boolean, inUse: Boolean, name: String?): String? =
    name?.takeIf { endTmux && wasOpen && inUse }
