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

    /** How many sessions have not ended, as it changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val liveCount: Flow<Int> = _sessions
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(0) else combine(list.map { it.state }) { states -> states.count { it != MqttState.Ended } }
        }
        .distinctUntilChanged()

    /** Every session as the connection rows count them: its tunnel, and whether it is still live; as it changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val through: Flow<List<SessionOn>> = _sessions
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(list.map { s -> s.state.map { SessionOn(TunnelRef(s.profile, s.device, s.port), it != MqttState.Ended) } }) { it.toList() }
            }
        }
        .distinctUntilChanged()

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
