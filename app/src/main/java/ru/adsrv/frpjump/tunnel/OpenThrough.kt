package ru.adsrv.frpjump.tunnel

/** A tunnel as terminal sessions and file screens use it: a device and SSH port of a profile. */
data class TunnelRef(val profile: String, val device: String, val port: Int)

/** A terminal session through [tunnel]; [live] until it has ended. */
data class SessionOn(val tunnel: TunnelRef, val live: Boolean)

/** What goes with tunnels when they disconnect: live terminal sessions and open file screens. */
data class OpenThrough(val sessions: Int, val files: Int) {
    /** Whether disconnecting asks first. */
    val any: Boolean get() = sessions > 0 || files > 0
}

/**
 * What closes when [tunnel] disconnects, or every tunnel when it is null
 * ("Disconnect all"): the live [sessions] through it (an ended one has
 * nothing left to lose) and the file screens open on it ([files]).
 */
fun openThrough(sessions: List<SessionOn>, files: List<TunnelRef>, tunnel: TunnelRef?): OpenThrough {
    val on = { ref: TunnelRef -> tunnel == null || ref == tunnel }
    return OpenThrough(sessions.count { it.live && on(it.tunnel) }, files.count(on))
}

/** What closes when every tunnel of [profile] disconnects (the power button on the Devices tab's bar). */
fun openOnProfile(sessions: List<SessionOn>, files: List<TunnelRef>, profile: String): OpenThrough =
    OpenThrough(sessions.count { it.live && it.tunnel.profile == profile }, files.count { it.profile == profile })

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

/** The [sessions] through [tunnel] (a connection's device and port), as its row shows them. */
fun sessionsThrough(sessions: List<SessionOn>, tunnel: TunnelRef): SessionCount {
    val mine = sessions.filter { it.tunnel == tunnel }
    return SessionCount(mine.size, mine.count { !it.live })
}
