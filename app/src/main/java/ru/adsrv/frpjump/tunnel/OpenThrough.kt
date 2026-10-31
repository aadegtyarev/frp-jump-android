package ru.adsrv.frpjump.tunnel

/** A tunnel as terminal sessions and file screens use it: a device and SSH port of a profile. */
data class TunnelRef(val profile: String, val device: String, val port: Int)

/** The kinds of session the Sessions tab lists. */
enum class SessionKind { TERMINAL, MQTT }

/** A session of [kind] through [tunnel]; [live] until it has ended. */
data class SessionOn(val tunnel: TunnelRef, val live: Boolean, val kind: SessionKind = SessionKind.TERMINAL)

/** What goes with tunnels when they disconnect: live terminal and MQTT sessions, and open file screens. */
data class OpenThrough(val terminals: Int, val mqtt: Int, val files: Int) {
    /** The live sessions of both kinds. */
    val sessions: Int get() = terminals + mqtt

    /** Whether disconnecting asks first. */
    val any: Boolean get() = sessions > 0 || files > 0
}

/**
 * The sentence that says which sessions [open] closes: [terminals] or
 * [mqtt] alone, [both] for both kinds ("2 terminals and 1 MQTT session
 * will close."); null when none closes.
 */
inline fun <T> closingSentence(open: OpenThrough, terminals: (Int) -> T, mqtt: (Int) -> T, both: (Int, Int) -> T): T? = when {
    open.terminals > 0 && open.mqtt > 0 -> both(open.terminals, open.mqtt)
    open.terminals > 0 -> terminals(open.terminals)
    open.mqtt > 0 -> mqtt(open.mqtt)
    else -> null
}

private fun counted(sessions: List<SessionOn>, on: (TunnelRef) -> Boolean, files: List<TunnelRef>): OpenThrough {
    val live = sessions.filter { it.live && on(it.tunnel) }
    return OpenThrough(live.count { it.kind == SessionKind.TERMINAL }, live.count { it.kind == SessionKind.MQTT }, files.count(on))
}

/**
 * What closes when [tunnel] disconnects, or every tunnel when it is null
 * ("Disconnect all"): the live [sessions] of both kinds through it (an ended one has
 * nothing left to lose) and the file screens open on it ([files]).
 */
fun openThrough(sessions: List<SessionOn>, files: List<TunnelRef>, tunnel: TunnelRef?): OpenThrough {
    return counted(sessions, { tunnel == null || it == tunnel }, files)
}

/** What closes when every tunnel of [profile] disconnects (the power button on the Devices tab's bar). */
fun openOnProfile(sessions: List<SessionOn>, files: List<TunnelRef>, profile: String): OpenThrough =
    counted(sessions, { it.profile == profile }, files)

/** The terminal sessions through one tunnel: how many, and how many of them have ended. */
data class SessionCount(val total: Int, val ended: Int) {
    /**
     * Whether the row shows them as a pill: one open (the button says
     * "Terminal" alone then), or any ended; from two the button says
     * "Terminal · N" already.
     */
    val showsPill: Boolean get() = total == 1 || ended > 0

    companion object {
        val NONE = SessionCount(0, 0)
    }
}

/** The terminal [sessions] through [tunnel] (a connection's device and port), as its row shows them. */
fun sessionsThrough(sessions: List<SessionOn>, tunnel: TunnelRef): SessionCount {
    val mine = sessions.filter { it.kind == SessionKind.TERMINAL && it.tunnel == tunnel }
    return SessionCount(mine.size, mine.count { !it.live })
}
