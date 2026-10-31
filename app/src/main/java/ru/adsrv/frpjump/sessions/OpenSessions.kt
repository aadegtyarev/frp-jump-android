package ru.adsrv.frpjump.sessions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.SessionOn
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.undo.PendingUndo

/**
 * Whether the session [id] of [kind] through [tunnel] is gone from the
 * screens while an Undo is on them: it is being closed, or its tunnel
 * disconnected ([hidden] is [PendingUndo.hidden]). It still runs; Undo
 * shows it again as it is.
 */
fun sessionHidden(hidden: Set<String>, kind: SessionKind, id: Int, tunnel: TunnelRef): Boolean =
    PendingUndo.sessionKey(kind, id) in hidden || PendingUndo.tunnelKey(tunnel.profile, tunnel.device, tunnel.port) in hidden

/** [sessionHidden] of [session]. */
fun sessionHidden(hidden: Set<String>, session: SessionOn): Boolean = sessionHidden(hidden, session.kind, session.id, session.tunnel)

/**
 * The app's open sessions of both kinds, terminal and MQTT, as everything
 * that counts them sees them: the notification, the connection rows and
 * the "keeps running" notice. Each kind gives its sessions as they change
 * ([terminals], [mqtt]) and as they are now ([terminalsNow], [mqttNow]);
 * those [sessionHidden] by [hidden] are left out.
 */
class OpenSessions(
    terminals: Flow<List<SessionOn>>,
    private val terminalsNow: () -> List<SessionOn>,
    mqtt: Flow<List<SessionOn>>,
    private val mqttNow: () -> List<SessionOn>,
    /** [PendingUndo.hidden]. */
    private val hidden: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
) {
    /** Every session shown, terminals first, as it changes. */
    val through: Flow<List<SessionOn>> = combine(terminals, mqtt, hidden) { t, m, h -> (t + m).filterNot { sessionHidden(h, it) } }
        .distinctUntilChanged()

    /** How many sessions shown have not ended, as it changes. */
    val liveCount: Flow<Int> = through.map { list -> list.count { it.live } }.distinctUntilChanged()

    /** [through] as it is now, for a count taken when asked. Main thread. */
    fun now(): List<SessionOn> = (terminalsNow() + mqttNow()).filterNot { sessionHidden(hidden.value, it) }

    /** "Sessions keep running" was said once in this process. */
    private var leftRunningSaid = false

    /**
     * Leaving a terminal or the explorer: how many sessions keep running,
     * for the notice "N sessions keep running: Sessions tab"; only the
     * first time in this process with any live, else null. Main thread.
     */
    fun leftRunning(): Int? {
        val live = now().count { it.live }
        if (live == 0 || leftRunningSaid) return null
        leftRunningSaid = true
        return live
    }
}
