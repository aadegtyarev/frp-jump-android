package ru.adsrv.frpjump.mqtt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.ports.profilePortKey
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.SessionOn
import ru.adsrv.frpjump.tunnel.TunnelRef

/**
 * The app's MQTT explorer sessions, at most one per connection. Like the
 * SSH sessions they outlive the screens; a session ends when its tunnel
 * goes away (Disconnect, Disconnect all, Forget), or when closed.
 */
class MqttSessions(
    private val env: MqttEnvironment,
    /** Every tunnel up, in all profiles, as [profilePortKey]s. */
    tunnelsUp: Flow<Set<String>>,
    scope: CoroutineScope,
    /** Where tunnel changes are taken: the main thread, a test's own in tests. */
    main: CoroutineDispatcher = Dispatchers.Main,
) {
    private val _sessions = MutableStateFlow<List<MqttSession>>(emptyList())
    val sessions: StateFlow<List<MqttSession>> = _sessions
    private var nextId = 1

    /** Sessions closed because their tunnel went away (ids are never reused); written on the main thread, read from any. */
    private val closedByTunnel: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Every session as the connection rows count them: its tunnel, and whether it is still live; as it changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val through: Flow<List<SessionOn>> = _sessions
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(list.map { s -> s.state.map { s.on(it) } }) { it.toList() }
            }
        }
        .distinctUntilChanged()

    /** [through] as it is now. Any thread. */
    fun throughNow(): List<SessionOn> = _sessions.value.map { it.on(it.state.value) }

    private fun MqttSession.on(state: MqttState) = SessionOn(TunnelRef(profile, device, port), state != MqttState.Ended, SessionKind.MQTT, id)

    init {
        scope.launch(main) {
            tunnelsUp.distinctUntilChanged().collect { up ->
                _sessions.value.filter { profilePortKey(it.profile, it.device, it.port) !in up }
                    .forEach {
                        closedByTunnel += it.id
                        close(it.id)
                    }
            }
        }
    }

    /**
     * The session of connection [connectionId] of [profile]: the one open
     * ("MQTT" returns to it), else a new one to [device]:[port] through
     * [localPort], connecting. Main thread.
     */
    fun open(profile: String, connectionId: String, device: String, port: Int, localPort: Int): MqttSession {
        find(profile, connectionId)?.let { return it }
        val session = MqttSession(nextId++, profile, connectionId, device, port, localPort, env)
        _sessions.update { it + session }
        session.connect()
        return session
    }

    fun find(id: Int): MqttSession? = _sessions.value.firstOrNull { it.id == id }

    fun find(profile: String, connectionId: String): MqttSession? =
        _sessions.value.firstOrNull { it.profile == profile && it.connectionId == connectionId }

    fun close(id: Int) {
        val session = find(id) ?: return
        session.close()
        _sessions.update { list -> list.filter { it.id != id } }
    }

    /** How many times each pin was made, so a late unpin can tell it was pinned again meanwhile. */
    private val pinsMade = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun pinKey(profile: String, connectionId: String, filter: String) = "$profile/$connectionId\u0000$filter"

    /** A mark of [filter]'s pinning now; [unpin] given it does nothing once the filter was pinned again. */
    fun pinMark(profile: String, connectionId: String, filter: String): Int = pinsMade[pinKey(profile, connectionId, filter)] ?: 0

    /** Pins [filter] through the connection's open session. Main thread. */
    fun pin(profile: String, connectionId: String, filter: String) {
        val session = find(profile, connectionId) ?: return
        pinsMade.merge(pinKey(profile, connectionId, filter), 1, Int::plus)
        session.pin(filter)
    }

    /** Replaces the pin [old] with [new] through the connection's open session ("Change mask"). Main thread. */
    fun replacePin(profile: String, connectionId: String, old: String, new: String) {
        val session = find(profile, connectionId) ?: return
        pinsMade.merge(pinKey(profile, connectionId, new), 1, Int::plus)
        session.replacePin(old, new)
    }

    /**
     * Unpins [filter] of a connection: through its session when one is open
     * (it unsubscribes), else in the store only; not when [mark] is given
     * and the filter was pinned again since it was taken. Main thread.
     */
    fun unpin(profile: String, connectionId: String, filter: String, mark: Int? = null) {
        if (mark != null && mark != pinMark(profile, connectionId, filter)) return
        val session = find(profile, connectionId)
        if (session != null) {
            session.unpin(filter)
        } else {
            env.scheduler.post { env.pins.set(profile, connectionId, env.pins.get(profile, connectionId) - filter) }
        }
    }

    fun closeAll() = _sessions.value.forEach { close(it.id) }

    /** Closes the sessions of [profile] ("Forget profile"). */
    fun closeProfile(profile: String) = _sessions.value.filter { it.profile == profile }.forEach { close(it.id) }

    /** Closes the session of a connection being deleted. */
    fun closeConnection(profile: String, connectionId: String) = find(profile, connectionId)?.let { close(it.id) }

    /**
     * Runs [change] to the stored logins and pins after every session closed
     * before has finished writing them (the sessions' thread), and waits for
     * it. Off the main thread.
     */
    fun changeStored(change: () -> Unit) = env.scheduler.runAfterPending(change)

    /** Whether session [id] went because its tunnel was closed. Any thread. */
    fun closedByTunnel(id: Int): Boolean = id in closedByTunnel
}
