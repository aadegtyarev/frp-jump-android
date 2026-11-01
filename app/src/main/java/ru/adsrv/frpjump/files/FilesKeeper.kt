package ru.adsrv.frpjump.files

import ru.adsrv.frpjump.tunnel.SessionOn
import ru.adsrv.frpjump.tunnel.TunnelRef

/**
 * Whether a file session on [tunnel] is kept after its screen is left:
 * while a terminal session through the same tunnel is live. None: it
 * closes as the screen goes.
 */
fun keepsFiles(tunnel: TunnelRef?, terminals: List<SessionOn>): Boolean =
    tunnel != null && terminals.any { it.live && it.tunnel == tunnel }

/**
 * The app's file sessions, by the id of their screen's route. A screen
 * attaches to its session and releases it when it goes; a session left
 * while a terminal is open on its tunnel is kept ([keepsFiles]), and
 * "Files" on that tunnel opens it again ([kept]) in its folder. It closes
 * when the last terminal session there ends or goes (the tunnel with it).
 * Main thread.
 */
class FilesKeeper<S : FilesKeeper.Session>(private val open: (Int) -> S) {
    /** What the keeper needs of a file session. */
    interface Session {
        /** Its tunnel; null when its login is gone (never kept then). */
        val tunnel: TunnelRef?
        val user: String

        /** Its screen left, the session kept: transfers stop, the connection and folder stay. */
        fun leave()

        /** Its screen is back after [leave]. */
        fun resume()

        fun close()
    }

    private val sessions = mutableMapOf<Int, S>()

    /** Screens attached to each session (a screen shows one; two only in passing). */
    private val screens = mutableMapOf<Int, Int>()
    private var terminals: List<SessionOn> = emptyList()

    /** The session of screen [id]: the kept one, or a new one. */
    fun attach(id: Int): S {
        val kept = sessions[id]
        val session = kept ?: open(id).also { sessions[id] = it }
        val before = screens[id] ?: 0
        screens[id] = before + 1
        if (kept != null && before == 0) session.resume()
        return session
    }

    /** Screen [id] went: its session is kept while a terminal is open on its tunnel, else closed. */
    fun release(id: Int) {
        val session = sessions[id] ?: return
        val left = (screens[id] ?: 1) - 1
        if (left > 0) {
            screens[id] = left
            return
        }
        screens -= id
        if (!keepsFiles(session.tunnel, terminals)) return close(id)
        // One kept per tunnel: the one left last.
        sessions.filter { (other, s) -> other != id && other !in screens && s.tunnel == session.tunnel }.keys.forEach(::close)
        session.leave()
    }

    /** The kept session on [tunnel] (as [user] when given), not on a screen now; null when there is none. */
    fun kept(tunnel: TunnelRef, user: String? = null): Int? =
        sessions.entries.firstOrNull { (id, s) -> id !in screens && s.tunnel == tunnel && (user == null || s.user == user) }?.key

    /** The terminal sessions as they change: kept sessions with no live terminal left close. */
    fun onTerminals(now: List<SessionOn>) {
        terminals = now
        sessions.filter { (id, s) -> id !in screens && !keepsFiles(s.tunnel, now) }.keys.forEach(::close)
    }

    private fun close(id: Int) {
        sessions.remove(id)?.close()
    }
}
