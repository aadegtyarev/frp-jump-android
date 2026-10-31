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
