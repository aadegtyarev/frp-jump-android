package ru.adsrv.frpjump.mqtt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/** Where a session stands. */
sealed interface MqttState {
    data object Connecting : MqttState

    /** The broker refused the login: the dialog asks, with [user] filled in; [wrong] when a login was tried. */
    data class NeedsLogin(val user: String?, val wrong: Boolean) : MqttState

    data class Open(val version: MqttVersion) : MqttState

    /** The connection dropped; the client tries again ([reconnectDelayMs]) and keeps the tree. */
    data object Reconnecting : MqttState

    data class Failed(val reason: MqttFailure, val detail: String? = null) : MqttState

    /** Closed; nothing more happens. */
    data object Ended : MqttState
}

enum class MqttFailure {
    /** "No broker answers on port …". */
    NO_BROKER,

    /** "The broker refused MQTT 5 and 3.1.1". */
    NO_COMMON_VERSION,

    /** Another CONNACK refusal; the state's detail names it. */
    REFUSED,
}

/** The tree snapshot (`#`), as the bar under the path bar shows it. */
sealed interface SnapshotState {
    data object Idle : SnapshotState

    data object Reading : SnapshotState

    data class Done(val end: SnapshotEnd) : SnapshotState

    /** SUBACK failure for `#`: "The broker does not allow reading the whole tree". */
    data object Refused : SnapshotState
}

/** What every session needs. */
class MqttEnvironment(
    val logins: MqttLogins,
    val pins: PinStore,
    val connector: MqttConnector,
    val scheduler: MqttScheduler,
    val topicLimit: Int = TopicTree.DEFAULT_LIMIT,
)

/** The delay before reconnect attempt [step] (0 first): 1, 2 and 5 s, then every 10 s. */
fun reconnectDelayMs(step: Int): Long = RECONNECT_DELAYS_MS.getOrElse(step) { RECONNECT_DELAYS_MS.last() }

private val RECONNECT_DELAYS_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L)

/**
 * One explorer connected to the broker of connection [connectionId] of
 * [profile], through the tunnel on 127.0.0.1:[localPort]. It lives in the
 * app, not a screen. MQTT 5 first, 3.1.1 when the broker refuses the
 * version or closes before any CONNACK, then that version until it closes.
 * Anonymous unless a login is remembered; a refused login asks
 * ([MqttState.NeedsLogin], [login]). On open it subscribes the open branch
 * and the pins and takes the tree snapshot; a dropped connection is tried
 * again after [reconnectDelayMs] and does all of that again.
 *
 * Every public call may come from any thread; the work runs on the
 * scheduler's thread, which alone touches the fields below.
 */
class MqttSession(
    val id: Int,
    val profile: String,
    val connectionId: String,
    val device: String,
    val port: Int,
    val localPort: Int,
    private val env: MqttEnvironment,
) {
    val tree = TopicTree(env.topicLimit)

    private val clientId = "frpjump-" + randomSuffix()
    private val scheduler = env.scheduler

    private val _state = MutableStateFlow<MqttState>(MqttState.Connecting)
    val state: StateFlow<MqttState> = _state

    private val _snapshot = MutableStateFlow<SnapshotState>(SnapshotState.Idle)
    val snapshot: StateFlow<SnapshotState> = _snapshot

    private val _branch = MutableStateFlow(MqttPath.ROOT)

    /** The level being viewed; at a path `p` other than the root, `p/#` is subscribed. */
    val branch: StateFlow<MqttPath> = _branch

    private val _pins = MutableStateFlow<List<String>>(emptyList())

    /** The pins' topic filters, in order. */
    val pins: StateFlow<List<String>> = _pins

    private val _dropped = MutableStateFlow(0L)

    /**
     * Messages dropped because [MAX_PENDING_MESSAGES] were already waiting
     * for the session's thread (a broker flooding faster than the phone
     * takes them): the tree may then miss values; the screen can say so.
     */
    val dropped: StateFlow<Long> = _dropped

    /** Messages handed over by the client and not yet taken on the scheduler's thread. */
    private val pending = AtomicInteger()

    private val _refused = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Filters the broker refused (SUBACK failure) on the current connection,
     * `#` included; one stays listed until it is granted on a later subscribe.
     */
    val refused: StateFlow<Set<String>> = _refused

    /** Set once a broker has answered on a version: kept until the session closes. */
    private var version: MqttVersion? = null
    private var sawVersionRefusal = false
    private var login: MqttLogin? = null

    /** [login] was typed in the dialog: on success it is remembered or forgotten as [rememberTyped] says. */
    private var loginTyped = false
    private var rememberTyped = false

    private var link: MqttLink? = null

    /** Bumped by every attempt and by close: callbacks of an older one are dropped. */
    private var generation = 0
    private var reconnectStep = 0
    private var cancelReconnect: (() -> Unit)? = null

    private val subscribed = LinkedHashSet<String>()

    /** The filters wanted now, in the order copies are matched: open branch, pins, the snapshot's `#`. */
    private var active: List<String> = emptyList()
    private val copies = CopyFilter(scheduler::now)

    private var snapshotOn = false
    private var snapshotRun = 0
    private var subackAt: Long? = null
    private var lastSnapshotMessageAt: Long? = null
    private var cancelSnapshotCheck: (() -> Unit)? = null

    /** Starts connecting: the remembered login, else anonymous. */
    fun connect() = scheduler.post { if (!ended) begin() }

    /** Tries again after [MqttState.Failed] (or from the login dialog, without a new login). */
    fun retry() = scheduler.post {
        val now = _state.value
        if (now is MqttState.Failed || now is MqttState.NeedsLogin) begin()
    }

    /** Connects with a login typed in the dialog; [remember] keeps it (sealed) once the broker takes it. */
    fun login(user: String, password: String, remember: Boolean) = scheduler.post {
        if (ended) return@post
        cancelReconnect?.invoke()
        login = MqttLogin(user, password)
        loginTyped = true
        rememberTyped = remember
        _state.value = MqttState.Connecting
        attempt(version ?: MqttVersion.V5)
    }

    /** Moves the live branch to [path]; the root has none. */
    fun open(path: MqttPath) = scheduler.post {
        if (_branch.value == path) return@post
        _branch.value = path
        reconcile()
    }

    /** Pins [filter] (kept for the connection) and subscribes it. */
    fun pin(filter: String) = changePins { if (filter in it) it else it + filter }

    fun unpin(filter: String) = changePins { it - filter }

    /** "Refresh tree": forgets every topic and takes a new snapshot. */
    fun refreshTree() = scheduler.post {
        if (ended) return@post
        tree.clear()
        copies.clear()
        if (link != null) startSnapshot()
    }

    /**
     * Disconnects for good. Any thread; the state turns [MqttState.Ended] on
     * the scheduler's thread, after whatever was posted before, and nothing
     * changes it afterwards.
     */
    fun close() {
        scheduler.post {
            if (ended) return@post
            _state.value = MqttState.Ended
            generation++
            cancelReconnect?.invoke()
            cancelSnapshotCheck?.invoke()
            link?.close()
            link = null
        }
    }

    private val ended: Boolean get() = _state.value == MqttState.Ended

    private fun begin() {
        if (ended) return
        cancelReconnect?.invoke()
        if (version == null) sawVersionRefusal = false
        login = env.logins.get(profile, connectionId)
        loginTyped = false
        _pins.value = env.pins.get(profile, connectionId)
        _state.value = MqttState.Connecting
        attempt(version ?: MqttVersion.V5)
    }

    private fun attempt(v: MqttVersion) {
        val gen = ++generation
        link?.close()
        link = null
        val events = object : LinkEvents {
            override fun message(topic: String, payload: ByteArray, retain: Boolean, qos: Int) {
                // Past the cap the newest is dropped: the thread is behind, and the tree is not complete anyway.
                if (pending.incrementAndGet() > MAX_PENDING_MESSAGES) {
                    pending.decrementAndGet()
                    _dropped.update { it + 1 }
                    return
                }
                scheduler.post {
                    pending.decrementAndGet()
                    if (gen == generation) onMessage(topic, payload, retain, qos)
                }
            }

            override fun lost() = scheduler.post { if (gen == generation) onLost() }
        }
        val done = { outcome: ConnectOutcome ->
            scheduler.post {
                when {
                    gen == generation && !ended -> onOutcome(v, outcome)
                    // Closed meanwhile: a connection that opened anyway goes at once.
                    outcome is ConnectOutcome.Opened -> outcome.link.close()
                }
            }
        }
        try {
            env.connector.connect(ConnectRequest(localPort, clientId, v, login), events, done)
        } catch (e: Exception) {
            done(ConnectOutcome.Refused(ConnectRefusal.OTHER, e.javaClass.simpleName))
        }
    }

    private fun onOutcome(v: MqttVersion, outcome: ConnectOutcome) {
        when (outcome) {
            is ConnectOutcome.Opened -> opened(v, outcome.link)
            is ConnectOutcome.Refused -> refused(v, outcome)
        }
    }

    private fun opened(v: MqttVersion, opened: MqttLink) {
        if (ended) return opened.close()
        link = opened
        version = v
        reconnectStep = 0
        val typed = login
        if (loginTyped && typed != null) {
            if (rememberTyped) env.logins.remember(profile, connectionId, typed) else env.logins.forget(profile, connectionId)
        }
        loginTyped = false
        _state.value = MqttState.Open(v)
        subscribed.clear()
        _refused.value = emptySet()
        startSnapshot()
    }

    private fun refused(v: MqttVersion, outcome: ConnectOutcome.Refused) {
        if (ended) return
        val reconnecting = _state.value == MqttState.Reconnecting
        when (outcome.refusal) {
            ConnectRefusal.BAD_LOGIN -> {
                // The broker answered on this version.
                version = v
                _state.value = MqttState.NeedsLogin(login?.user, wrong = login != null)
            }
            ConnectRefusal.UNSUPPORTED_VERSION, ConnectRefusal.CLOSED_BEFORE_CONNACK -> when {
                reconnecting -> scheduleReconnect()
                version == null && v == MqttVersion.V5 -> {
                    if (outcome.refusal == ConnectRefusal.UNSUPPORTED_VERSION) sawVersionRefusal = true
                    attempt(MqttVersion.V3_1_1)
                }
                // Only a broker that said "unsupported version" speaks MQTT at all; a bare close is no broker.
                sawVersionRefusal || outcome.refusal == ConnectRefusal.UNSUPPORTED_VERSION ->
                    _state.value = MqttState.Failed(MqttFailure.NO_COMMON_VERSION)
                else -> _state.value = MqttState.Failed(MqttFailure.NO_BROKER)
            }
            ConnectRefusal.NO_BROKER ->
                if (reconnecting) scheduleReconnect() else _state.value = MqttState.Failed(MqttFailure.NO_BROKER)
            ConnectRefusal.OTHER ->
                if (reconnecting) scheduleReconnect() else _state.value = MqttState.Failed(MqttFailure.REFUSED, outcome.detail)
        }
    }

    private fun onLost() {
        if (ended || _state.value !is MqttState.Open) return
        link = null
        subscribed.clear()
        stopSnapshot()
        _snapshot.value = SnapshotState.Idle
        _state.value = MqttState.Reconnecting
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val v = version ?: return
        cancelReconnect = scheduler.schedule(reconnectDelayMs(reconnectStep++)) {
            if (_state.value == MqttState.Reconnecting) attempt(v)
        }
    }

    private fun onMessage(topic: String, payload: ByteArray, retain: Boolean, qos: Int) {
        val v = version ?: return
        if (topic.isEmpty()) return
        val path = MqttPath.parse(topic)
        if (copies.isCopy(path, payload, retain, qos, matchingFilters(active, path))) return
        val received = tree.receive(path, payload, retain, qos, v)
        if (snapshotOn) {
            // Only what fills the tree keeps the snapshot going (see SnapshotRule), not live values of known topics.
            if (received == Received.NEW_TOPIC || received == Received.UNCHANGED) lastSnapshotMessageAt = scheduler.now()
            if (received == Received.OVER_LIMIT) checkSnapshot(snapshotRun)
        }
    }

    private fun changePins(change: (List<String>) -> List<String>) = scheduler.post {
        if (ended) return@post
        val now = change(env.pins.get(profile, connectionId))
        env.pins.set(profile, connectionId, now)
        _pins.value = now
        reconcile()
    }

    /** Subscribes what is wanted and not subscribed, unsubscribes the rest. */
    private fun reconcile() {
        val wanted = buildList {
            if (!_branch.value.isRoot) add(_branch.value.format() + "/#")
            addAll(_pins.value)
            if (snapshotOn) add(SNAPSHOT_FILTER)
        }.distinct()
        active = wanted
        val open = link ?: return
        (subscribed - wanted.toSet()).forEach {
            subscribed -= it
            if (it !in _refused.value) open.unsubscribe(it)
        }
        wanted.filter { it !in subscribed }.forEach { filter ->
            subscribed += filter
            val gen = generation
            val run = snapshotRun
            open.subscribe(filter) { granted -> scheduler.post { if (gen == generation) subscribed(filter, granted, run) } }
        }
    }

    private fun subscribed(filter: String, granted: Boolean, run: Int) {
        _refused.value = if (granted) _refused.value - filter else _refused.value + filter
        if (filter != SNAPSHOT_FILTER || !snapshotOn || run != snapshotRun) return
        if (!granted) {
            stopSnapshot()
            _snapshot.value = SnapshotState.Refused
            reconcile()
            return
        }
        subackAt = scheduler.now()
        checkSnapshot(run)
    }

    /** Subscribes `#` afresh (the broker re-sends every retained value) and watches when to stop. */
    private fun startSnapshot() {
        stopSnapshot()
        snapshotOn = true
        snapshotRun++
        subackAt = null
        lastSnapshotMessageAt = null
        _snapshot.value = SnapshotState.Reading
        subscribed -= SNAPSHOT_FILTER
        reconcile()
    }

    private fun checkSnapshot(run: Int) {
        val suback = subackAt
        if (!snapshotOn || run != snapshotRun || suback == null) return
        cancelSnapshotCheck?.invoke()
        val now = scheduler.now()
        val end = SnapshotRule.end(suback, lastSnapshotMessageAt, now, tree.limitReached)
        if (end == null) {
            cancelSnapshotCheck = scheduler.schedule(SnapshotRule.nextCheckAt(suback, lastSnapshotMessageAt) - now) { checkSnapshot(run) }
            return
        }
        stopSnapshot()
        reconcile()
        _snapshot.value = SnapshotState.Done(end)
    }

    private fun stopSnapshot() {
        snapshotOn = false
        cancelSnapshotCheck?.invoke()
        cancelSnapshotCheck = null
    }

    companion object {
        const val SNAPSHOT_FILTER = "#"

        /** Messages waiting for the scheduler's thread at most, per session. */
        const val MAX_PENDING_MESSAGES = 10_000

        private val random = SecureRandom()

        private fun randomSuffix(): String = (1..12).map { "0123456789abcdefghijklmnopqrstuvwxyz"[random.nextInt(36)] }.joinToString("")
    }
}
