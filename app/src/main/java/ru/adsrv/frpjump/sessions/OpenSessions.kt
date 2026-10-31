package ru.adsrv.frpjump.sessions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.adsrv.frpjump.tunnel.SessionOn

/**
 * The app's open sessions of both kinds, terminal and MQTT, as everything
 * that counts them sees them: the notification, the disconnect warnings,
 * the connection rows and the "keeps running" notice. Each kind gives its
 * sessions as they change ([terminals], [mqtt]) and as they are now
 * ([terminalsNow], [mqttNow]).
 */
class OpenSessions(
    terminals: Flow<List<SessionOn>>,
    private val terminalsNow: () -> List<SessionOn>,
    mqtt: Flow<List<SessionOn>>,
    private val mqttNow: () -> List<SessionOn>,
) {
    /** Every session, terminals first, as it changes. */
    val through: Flow<List<SessionOn>> = combine(terminals, mqtt) { t, m -> t + m }.distinctUntilChanged()

    /** How many sessions have not ended, as it changes. */
    val liveCount: Flow<Int> = through.map { list -> list.count { it.live } }.distinctUntilChanged()

    /** [through] as it is now, for a count taken when asked. Main thread. */
    fun now(): List<SessionOn> = terminalsNow() + mqttNow()

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
