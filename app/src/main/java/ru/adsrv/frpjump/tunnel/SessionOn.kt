package ru.adsrv.frpjump.tunnel

/** A tunnel as terminal sessions and file screens use it: a device and SSH port of a profile. */
data class TunnelRef(val profile: String, val device: String, val port: Int)

/** The kinds of session the Sessions tab lists. */
enum class SessionKind { TERMINAL, MQTT }

/** A session of [kind] through [tunnel]; [live] until it has ended. [id]: its session's id, unique per kind. */
data class SessionOn(val tunnel: TunnelRef, val live: Boolean, val kind: SessionKind = SessionKind.TERMINAL, val id: Int = 0)

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
