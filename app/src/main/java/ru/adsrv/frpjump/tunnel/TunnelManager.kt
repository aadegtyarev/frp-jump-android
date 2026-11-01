package ru.adsrv.frpjump.tunnel

import java.util.concurrent.ConcurrentHashMap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.NetworkException
import ru.adsrv.frpjump.api.DesiredStateResponse
import ru.adsrv.frpjump.api.AgentApi
import ru.adsrv.frpjump.api.ProtocolMismatchException
import ru.adsrv.frpjump.ports.LocalPortAllocator
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.ports.wireProtocolFor
import ru.adsrv.frpjump.session.ProfileSession
import ru.adsrv.frpjump.store.StoredEnrollment
import ru.adsrv.frpjump.store.Credentials
import ru.adsrv.frpjump.ui.userMessage
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.R
import java.io.IOException

/**
 * Whether a tunnel tries a direct path: [OFF] by the setting, [KEPT] through
 * the server because the owner chose so for its device on this network.
 */
enum class DirectMode { ON, KEPT, OFF }

/** One consumed grant as the card shows it. */
data class Tunnel(
    val device: String,
    val port: Int,
    val grantId: String,
    val localPort: Int,
    val state: VisitorState,
    val direct: DirectMode = DirectMode.ON,
    /** Its local port is pinned by a connection: never moved, also when another app takes it. */
    val pinned: Boolean = false,
) {
    val key get() = localPortKey(device, port)
}

enum class PendingOp { CONNECTING, DISCONNECTING }

/** Why the last poll could not reach the server: what a restore waits for (in this order when several). */
enum class Waiting { UNLOCK, NETWORK }

data class TunnelsState(
    val tunnels: List<Tunnel> = emptyList(),
    /** Connect/disconnect in flight, by [localPortKey]. */
    val pending: Map<String, PendingOp> = emptyMap(),
    /** Last connect/disconnect error, by [localPortKey]. */
    val errors: Map<String, String> = emptyMap(),
    /** Last poll failure, cleared by the next success. */
    val syncError: String? = null,
    /** Set while the server speaks another wire version: nothing is applied. */
    val mismatch: String? = null,
    /** Set while polls fail for want of a network or of the Keystore (locked); cleared by a success. */
    val waiting: Waiting? = null,
) {
    /**
     * As the screens show it: the tunnels in [dark] (by [localPortKey]; their
     * disconnect waits for Undo) left out, as if down. They still run.
     */
    fun withoutDark(dark: Set<String>): TunnelsState = if (dark.isEmpty()) this else copy(tunnels = tunnels.filter { it.key !in dark })
}

private data class Core(
    val planned: List<PlannedTunnel> = emptyList(),
    /** Per grant id, as last applied. */
    val modes: Map<String, DirectMode> = emptyMap(),
    val pending: Map<String, PendingOp> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val syncError: String? = null,
    val mismatch: String? = null,
    val waiting: Waiting? = null,
)

/**
 * One profile's agent loop, after the Linux agent's `poller.sync_once`:
 * heartbeat, fetch desired state, run frpc with one tunnel per consumed
 * grant. Desired state is the only source of truth for what runs; connect
 * and disconnect only ask the server and then sync.
 *
 * A protocol mismatch stops applying anything but leaves a running frpc
 * alone (`ProtocolMismatchError` in the agent). Polling is driven from
 * outside ([poll], by the profile's poller); frpc keeps running between polls.
 * Each profile has its own manager and its own frpc ([runner]).
 */
class TunnelManager(
    /** The profile's id: its local ports are kept under it. */
    private val profile: String,
    /** The stored enrollment, read on every cycle ([ru.adsrv.frpjump.store.CredentialStore.state]). */
    private val enrollment: () -> StoredEnrollment,
    private val api: AgentApi,
    private val session: ProfileSession,
    /** Shared by all profiles' managers. */
    private val localPorts: LocalPortStore,
    private val runner: FrpcControl,
    /**
     * Starts the foreground service; false when Android refuses. It stops
     * itself once no profile lists a tunnel ([TunnelService]).
     */
    private val startService: () -> Boolean,
    /** Per device: try direct, or not (setting off, or kept through the server on this network). Read on every apply. */
    private val directMode: (device: String) -> DirectMode = { DirectMode.ON },
    /**
     * How many tunnels run now, after every apply or stop, on the IO
     * dispatcher: kept for a cold start to bring them back
     * ([ru.adsrv.frpjump.restore.RestoreRecord]).
     */
    private val recordTunnels: (count: Int) -> Unit = {},
    /** The profile's pinned local ports by [localPortKey] ([ru.adsrv.frpjump.connections.tunnelPins]); read on every apply. */
    private val pins: () -> Map<String, Int> = { emptyMap() },
    /** How long a pinned tunnel whose port another app holds waits before it tries the port again. */
    private val pinnedRetryMs: Long = PINNED_RETRY_MS,
    /** Opens a connection to a local port and closes it ([touchLocalPort]); blocking. */
    private val touchPort: (localPort: Int) -> Unit = ::touchLocalPort,
    /** How often a tunnel waiting for its device tries a connection. */
    private val deviceProbeMs: Long = DEVICE_PROBE_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncing = Mutex()
    private val core = MutableStateFlow(Core())

    /** Whether this manager has the foreground service running; only touched under [syncing]. */
    private var serviceUp = false

    /** From the settings; read when the next sync plans the tunnels. */
    @Volatile
    var portRange: IntRange = 40000..40999

    /** The last desired state applied, for [reapply]; only touched under [syncing]. */
    private var lastDesired: DesiredStateResponse? = null

    /** "Retry direct" count per grant id ([VisitorSpec.attempt]); only touched under [syncing]. */
    private val attempts = mutableMapOf<String, Int>()

    /** Moves to another local port per grant id since it last worked ([rebind]); cleared by the states' collector too. */
    private val rebinds = ConcurrentHashMap<String, Int>()

    /** Pinned tunnels left out of the next [apply]'s visitors, so the one after starts them afresh ([retryPinned]). */
    private var leftOut: Set<String> = emptySet()

    /**
     * What a pinned tunnel being tried again showed (its port taken), by
     * grant id: while it is left out and starting again it keeps showing
     * that, not "Connecting…"; dropped once it is up or gone.
     */
    private val retryShown = ConcurrentHashMap<String, VisitorState>()

    /** Ports the next [apply] probes although frpc was given them: it could not listen there. */
    private var reprobe: Set<Int> = emptySet()

    val state: StateFlow<TunnelsState> = combine(core, runner.states) { c, states ->
        TunnelsState(
            tunnels = c.planned.map {
                Tunnel(
                    device = it.grant.exposerDeviceName,
                    port = it.grant.targetPort,
                    grantId = it.grant.grantId,
                    localPort = it.localPort,
                    state = (states[it.grant.grantId] ?: VisitorState(TunnelStatus.CONNECTING)).let { state ->
                        val shown = retryShown[it.grant.grantId]?.takeIf { _ -> it.pinned && state.status == TunnelStatus.CONNECTING } ?: state
                        // A pinned port stays: say plainly who is in the way.
                        when {
                            it.pinned && portTaken(shown) ->
                                shown.copy(detail = Texts.get(R.string.local_port_taken_pinned, it.localPort, PINNED_RETRY_MS / 1000))
                            shown.deviceMissing -> shown.copy(detail = Texts.get(R.string.device_port_not_open, it.grant.targetPort))
                            else -> shown
                        }
                    },
                    direct = c.modes[it.grant.grantId] ?: DirectMode.ON,
                    pinned = it.pinned,
                )
            },
            pending = c.pending,
            errors = c.errors,
            syncError = c.syncError,
            mismatch = c.mismatch,
            waiting = c.waiting,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TunnelsState())

    init {
        // The server removed this phone (seen by any call, the device list too):
        // the REMOVED screen says tunnels are stopped, so stop them.
        scope.launch {
            session.removed.collect { removed -> if (removed) syncing.withLock { stopAllLocked() } }
        }
        // Another app took a tunnel's local port before frpc could listen on it: move the tunnel.
        scope.launch {
            runner.states.collect { states ->
                // A tunnel that works again may move again the next time its port is taken.
                val working = states.filterValues { it.status != TunnelStatus.FAILED && it.status != TunnelStatus.CONNECTING }.keys
                rebinds.keys.removeAll(working)
                retryShown.keys.removeAll(working)
                val taken = states.filterValues(::portTaken).keys
                if (taken.isNotEmpty()) scope.launch { rebind(taken) }
            }
        }
        // A pinned tunnel whose port another app holds stays on it and tries it again now and then,
        // until it is up, disconnected, or no longer pinned there (the set changes, the wait starts over).
        scope.launch {
            combine(core, runner.states) { c, states ->
                c.planned.filter { it.pinned && states[it.grant.grantId]?.let(::portTaken) == true }.map { it.grant.grantId }.toSet()
            }.distinctUntilChanged().collectLatest { waiting ->
                if (waiting.isEmpty()) return@collectLatest
                while (true) {
                    delay(pinnedRetryMs)
                    // Not in this block: its own reloads change the set and would cut it off between them.
                    scope.launch { retryPinned(waiting) }.join()
                }
            }
        }
        // "Trying direct…" ends after TRY_DIRECT_MS, and a punch is confirmed, even when frpc says nothing more.
        scope.launch {
            runner.states.collectLatest { states ->
                // The device has no direct tunnel to meet yet: try again soon, not at frpc's next check.
                val waiting = states.filterValues { it.status == TunnelStatus.TRYING && it.waitingForPeer }.keys
                if (waiting.isNotEmpty()) {
                    delay(PEER_RETRY_MS)
                    // Not in this block: the reload's new states cancel it.
                    scope.launch { retryGrants(waiting) }
                }
                while (states.values.any { it.status == TunnelStatus.TRYING || it.confirming || it.waitingForDevice }) {
                    delay(EXPIRY_TICK_MS)
                    withContext(Dispatchers.IO) { runner.expireTrying() }
                }
            }
        }
    }

    init {
        // A tunnel waiting for its device tries a connection every deviceProbeMs: frpc says
        // when the device refuses one, never when one gets through, so this is how the wait ends.
        scope.launch {
            runner.states.map { states -> states.filterValues { it.waitingForDevice }.keys }.distinctUntilChanged().collectLatest { waiting ->
                while (waiting.isNotEmpty()) {
                    delay(deviceProbeMs)
                    for (grant in waiting) {
                        val localPort = core.value.planned.firstOrNull { it.grant.grantId == grant }?.localPort ?: continue
                        touchDevice(grant, localPort)
                    }
                }
            }
        }
    }

    /** One connection to [grant]'s [localPort]; when the device did not refuse it, the tunnel works ([FrpcLogParser.deviceReady]). */
    private suspend fun touchDevice(grant: String, localPort: Int) {
        val before = withContext(Dispatchers.IO) { runner.refusalsOf(grant) }
        withContext(Dispatchers.IO) { touchPort(localPort) }
        // frpc logs a refusal as it closes the connection: let its line be read.
        delay(REFUSAL_SETTLE_MS)
        withContext(Dispatchers.IO) { runner.deviceReady(grant, before) }
    }

    /**
     * For an opener (terminal, explorer, files) about to use [device]:[port]'s
     * tunnel: once it listens, one connection checks that the device takes
     * it when [fresh] (brought up just now: the device may still be
     * registering it) or when it is waiting for the device; then it waits
     * while it is ([DEVICE_WAIT_MS] at most). Null when it can be used (or is
     * gone: the opener finds out), otherwise why not.
     */
    suspend fun awaitDevice(device: String, port: Int, fresh: Boolean): String? {
        val key = localPortKey(device, port)
        fun tunnelIn(s: TunnelsState) = s.tunnels.firstOrNull { it.key == key }
        val outcome = withTimeoutOrNull(DEVICE_WAIT_MS + 2 * deviceProbeMs) {
            val listening = state.map(::tunnelIn).first { it == null || it.state.status != TunnelStatus.CONNECTING } ?: return@withTimeoutOrNull null
            val s = listening.state
            if (fresh || s.waitingForDevice || s.deviceMissing) touchDevice(listening.grantId, listening.localPort)
            state.map(::tunnelIn).first { it == null || !it.state.waitingForDevice }
        }
        return when {
            outcome?.state?.deviceMissing == true -> outcome.state.detail
            outcome == null && tunnelIn(state.value)?.state?.waitingForDevice == true -> Texts.get(R.string.device_port_not_open, port)
            else -> null
        }
    }

    /** frpc's recent output, for diagnostics. */
    fun frpcLog(): List<String> = runner.recentLog()

    /** One poll cycle (the profile's [ru.adsrv.frpjump.session.Poller]); false when it failed. */
    suspend fun poll(): Boolean = syncOnce().ok

    /** One cycle; returns the desired state it applied, or null when it could not. */
    suspend fun sync(): DesiredStateResponse? = syncOnce().desired

    private class SyncResult(val desired: DesiredStateResponse?, val ok: Boolean)

    /**
     * Everything that starts frpc runs under [syncing] and checks there that
     * it still may: credentials present, not removed by the server. Forget
     * wipes the credentials under the same lock ([shutdown]), so no cycle can
     * start frpc (and write its key) after it.
     */
    private suspend fun syncOnce(): SyncResult = syncing.withLock {
        val credentials = when (val stored = enrollment()) {
            is StoredEnrollment.Enrolled -> stored.credentials
            // Forgotten (or never enrolled): nothing of the old enrollment may keep running.
            StoredEnrollment.NotEnrolled -> {
                stopAllLocked()
                return SyncResult(null, ok = true)
            }
            // Unreadable just now (Keystore busy, locked): leave what runs alone, try again later.
            is StoredEnrollment.Unavailable -> {
                core.update { it.copy(waiting = Waiting.UNLOCK) }
                return SyncResult(null, ok = false)
            }
        }
        if (session.removed.value) {
            stopAllLocked()
            return SyncResult(null, ok = true)
        }
        try {
            api.heartbeat(credentials)
            val desired = api.desiredState(credentials)
            apply(credentials, desired)
            core.update { it.copy(syncError = null, mismatch = null, waiting = null) }
            SyncResult(desired, ok = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtocolMismatchException) {
            core.update { it.copy(mismatch = e.userMessage()) }
            SyncResult(null, ok = false)
        } catch (e: Exception) {
            if (noteRemoval(e, credentials)) stopAllLocked()
            core.update { it.copy(syncError = e.userMessage(), waiting = if (e.isNoNetwork()) Waiting.NETWORK else null) }
            SyncResult(null, ok = false)
        }
    }

    /** The server could not be reached at all (not an answer it gave). */
    private fun Exception.isNoNetwork() = this is IOException || (this is NetworkException && cause is IOException)

    /**
     * A 401 means the server no longer knows this token: the profile was
     * deleted there. Only for the token still stored: a late answer to a call made
     * before "Forget" (or a re-enrollment) says nothing about the new one.
     */
    private fun noteRemoval(e: Exception, used: Credentials): Boolean {
        if (e !is ApiException || e.status != 401 || credentials()?.apiToken != used.apiToken) return false
        session.markRemoved()
        return true
    }

    /**
     * Applies the last desired state again, without asking the server:
     * whether a tunnel tries direct changed (the setting, "Keep through the
     * server", the network). frpc reloads only the tunnels that changed.
     */
    suspend fun reapply() = syncing.withLock {
        val credentials = credentials() ?: return@withLock
        val desired = lastDesired ?: return@withLock
        if (session.removed.value) return@withLock
        applyQuietly(credentials, desired)
    }

    fun reapplyAsync() = scope.launch { reapply() }

    /**
     * "Retry direct" for [device]: each of its tunnels that is not direct
     * gets its xtcp visitor renamed, so the reload starts it afresh and it
     * punches again at once, while the other tunnels (and these ones'
     * connections through the server) stay. Tunnels kept through the server
     * until now get theirs from the mode change ([apply]). False when the
     * device has no tunnel up.
     */
    suspend fun retryDirect(device: String): Boolean = syncing.withLock {
        val credentials = credentials() ?: return@withLock false
        val desired = lastDesired ?: return@withLock false
        val grants = core.value.planned.filter { it.grant.exposerDeviceName == device }.map { it.grant.grantId }
        if (grants.isEmpty()) return@withLock false
        grants.filter { core.value.modes[it] == DirectMode.ON && runner.states.value[it]?.status != TunnelStatus.DIRECT }
            .forEach { attempts[it] = (attempts[it] ?: 0) + 1 }
        applyQuietly(credentials, desired)
        true
    }

    /** A new attempt for [grants] that are still trying direct and waiting for their device. */
    private suspend fun retryGrants(grants: Set<String>) = syncing.withLock {
        val credentials = credentials() ?: return@withLock
        val desired = lastDesired ?: return@withLock
        val still = grants.filter { runner.states.value[it]?.waitingForPeer == true && core.value.modes[it] == DirectMode.ON }
        if (still.isEmpty()) return@withLock
        still.forEach { attempts[it] = (attempts[it] ?: 0) + 1 }
        applyQuietly(credentials, desired)
    }

    /**
     * Tunnels in [grants] whose local port is taken by someone else get a
     * free one, at most [MAX_REBINDS] times each until it works again, and
     * frpc reloads. A pinned one never moves: it stays failed, saying so,
     * until the port is free or the connection's pin changes.
     */
    private suspend fun rebind(grants: Set<String>) = syncing.withLock {
        val credentials = credentials() ?: return@withLock
        val desired = lastDesired ?: return@withLock
        if (session.removed.value) return@withLock
        val pinned = core.value.planned.filter { it.pinned }.map { it.grant.grantId }.toSet()
        val still = grants.filter { grant ->
            grant !in pinned && runner.states.value[grant]?.let(::portTaken) == true && (rebinds[grant] ?: 0) < MAX_REBINDS
        }
        if (still.isEmpty()) return@withLock
        still.forEach { rebinds[it] = (rebinds[it] ?: 0) + 1 }
        reprobe = core.value.planned.filter { it.grant.grantId in still }.map { it.localPort }.toSet()
        Log.i(TAG, "local port taken, moving ${still.size} tunnel(s)")
        applyQuietly(credentials, desired)
    }

    /**
     * Pinned tunnels in [grants] still kept off their port try it again: frpc
     * drops their visitors and takes them back (two reloads; the other
     * tunnels are untouched), so they listen anew. With nothing else
     * running, frpc simply restarts.
     */
    private suspend fun retryPinned(grants: Set<String>) = syncing.withLock {
        val credentials = credentials() ?: return@withLock
        val desired = lastDesired ?: return@withLock
        if (session.removed.value) return@withLock
        val planned = core.value.planned
        val still = planned.filter { it.pinned && it.grant.grantId in grants && runner.states.value[it.grant.grantId]?.let(::portTaken) == true }
            .map { it.grant.grantId }.toSet()
        if (still.isEmpty()) return@withLock
        Log.i(TAG, "pinned local port taken, trying it again for ${still.size} tunnel(s)")
        still.forEach { grant -> runner.states.value[grant]?.let { retryShown[grant] = it } }
        try {
            if (planned.all { it.grant.grantId in still }) {
                withContext(Dispatchers.IO) { runner.stop() }
            } else {
                leftOut = still
                applyQuietly(credentials, desired)
            }
        } finally {
            // Never left out for longer than this one reload, whatever happened.
            leftOut = emptySet()
        }
        applyQuietly(credentials, desired)
    }

    private fun portTaken(state: VisitorState) =
        state.status == TunnelStatus.FAILED && state.detail?.contains(PORT_TAKEN, ignoreCase = true) == true

    private suspend fun applyQuietly(credentials: Credentials, desired: DesiredStateResponse) {
        try {
            apply(credentials, desired)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "reapply failed: ${e.userMessage()}")
        }
    }

    /**
     * Stops frpc and the service; the caller holds [syncing]. [forget]
     * false keeps the restore record: the server may still list the
     * tunnels, so they are still to come back.
     */
    private suspend fun stopAllLocked(forget: Boolean = true) {
        withContext(Dispatchers.IO) {
            runner.stop()
            if (forget) recordTunnels(0)
        }
        lastDesired = null
        core.update { it.copy(planned = emptyList()) }
        stopService()
    }

    fun connectAsync(device: String, port: Int, kind: PortKind) = scope.launch { connect(device, port, kind) }

    fun disconnectAsync(device: String, port: Int) = scope.launch { disconnect(device, port) }


    fun syncAsync() = scope.launch { sync() }

    /**
     * Ask for the grant, then poll desired state every second for up to
     * [CONNECT_WAIT_SECONDS] until it lists the grant (the server answers
     * connect before the grant is necessarily visible to every poll).
     */
    suspend fun connect(device: String, port: Int, kind: PortKind) {
        val key = localPortKey(device, port)
        val credentials = credentials() ?: return
        if (!begin(key, PendingOp.CONNECTING)) return
        try {
            val grant = api.connect(credentials, device, port, wireProtocolFor(kind, port))
            repeat(CONNECT_WAIT_SECONDS) { attempt ->
                if (attempt > 0) delay(1_000)
                val desired = sync()
                if (desired != null && desired.consumed.any { it.grantId == grant.grantId }) return
            }
            fail(key, Texts.get(R.string.tunnel_not_listed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteRemoval(e, credentials)
            fail(key, e.userMessage())
        } finally {
            end(key)
        }
    }

    suspend fun disconnect(device: String, port: Int) {
        val key = localPortKey(device, port)
        val credentials = credentials() ?: return
        if (!begin(key, PendingOp.DISCONNECTING)) return
        try {
            api.disconnect(credentials, device, port)
            sync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteRemoval(e, credentials)
            fail(key, e.userMessage())
        } finally {
            end(key)
        }
    }

    /**
     * Disconnect every tunnel on the server, then stop frpc and the service
     * whatever the server said. False when the server could not be told
     * (no network, credentials unreadable just now): then the restore
     * record stays, since the server still lists the tunnels and the next
     * successful poll brings them back.
     */
    suspend fun disconnectAll(): Boolean {
        val told = when (val stored = enrollment()) {
            is StoredEnrollment.Enrolled -> tellServer(stored.credentials)
            // Nothing of a forgotten enrollment can come back.
            StoredEnrollment.NotEnrolled -> true
            is StoredEnrollment.Unavailable -> false
        }
        syncing.withLock { stopAllLocked(forget = told) }
        sync()
        return told
    }

    /**
     * Disconnects the tunnels running; with none (still being brought back
     * after a cold start) those the server lists, so they do not come back
     * on the next poll. False when any of it failed.
     */
    private suspend fun tellServer(credentials: Credentials): Boolean {
        val running = core.value.planned.map { it.grant }
        val grants = running.ifEmpty {
            try {
                api.desiredState(credentials).consumed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "desired state for disconnect all failed: ${e.userMessage()}")
                return false
            }
        }
        var told = true
        for (t in grants) {
            try {
                api.disconnect(credentials, t.exposerDeviceName, t.targetPort)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "disconnect $t failed: ${e.userMessage()}")
                told = false
            }
        }
        return told
    }

    /**
     * "Forget profile": stop everything, drop frpc's files and the profile's
     * local ports, then [wipeCredentials], all in one locked section: a poll
     * that waits for the lock finds no credentials and starts nothing.
     */
    suspend fun shutdown(wipeCredentials: suspend () -> Unit) = syncing.withLock {
        withContext(Dispatchers.IO) {
            runner.wipe()
            localPorts.removeProfile(profile)
            recordTunnels(0)
        }
        lastDesired = null
        core.value = Core()
        stopService()
        wipeCredentials()
    }

    private suspend fun apply(credentials: Credentials, desired: DesiredStateResponse) {
        lastDesired = desired
        val live = desired.consumed.map { it.grantId }.toSet()
        attempts.keys.retainAll(live)
        rebinds.keys.retainAll(live)
        retryShown.keys.retainAll(live)
        val probe = reprobe
        reprobe = emptySet()
        val (planned, modes) = withContext(Dispatchers.IO) {
            // Under the shared map's lock: two profiles planning at once never pick the same port.
            val plan = localPorts.update { ports ->
                planTunnels(profile, desired.consumed, ports, LocalPortAllocator(portRange), runner.heldPorts - probe, pins())
            }
            val modes = plan.associate { it.grant.grantId to directMode(it.grant.exposerDeviceName) }
            // Direct again (kept or off until now): a new xtcp name, so lines its old
            // visitor of the same name logged late cannot be taken for this attempt's.
            val before = core.value.modes
            modes.forEach { (grant, mode) ->
                if (mode == DirectMode.ON && before[grant] != null && before[grant] != DirectMode.ON) attempts[grant] = (attempts[grant] ?: 0) + 1
            }
            if (plan.isEmpty()) {
                runner.stop()
            } else {
                val skip = leftOut
                leftOut = emptySet()
                val serving = plan.filter { it.grant.grantId !in skip }
                val visitors = serving.map {
                    it.visitor(direct = modes[it.grant.grantId] == DirectMode.ON, attempt = attempts[it.grant.grantId] ?: 0)
                }
                runner.apply(launchFor(credentials, visitors), serving.map { it.localPort }.toSet())
            }
            recordTunnels(plan.size)
            plan to modes
        }
        core.update { it.copy(planned = planned, modes = modes) }
        if (planned.isEmpty()) stopService() else if (!serviceUp) serviceUp = startService()
    }

    /** Stops this manager's background work for good (the profile was forgotten); call after [shutdown]. */
    fun close() = scope.cancel()

    /** The service sees the tunnel count drop to zero and stops itself; start it again for the next one. */
    private fun stopService() {
        serviceUp = false
    }

    private fun launchFor(c: Credentials, visitors: List<VisitorSpec>): FrpcLaunch {
        return FrpcLaunch(
            configFor = { tls, admin ->
                Toml.write(
                    buildFrpcConfig(
                        // From enrollment, as the agent's state.json relay_addr/relay_port.
                        deviceId = c.deviceId,
                        apiToken = c.apiToken,
                        serverAddr = c.serverAddr,
                        serverPort = c.serverPort,
                        tls = tls,
                        visitors = visitors,
                        admin = admin,
                    ),
                )
            },
            certPem = c.certPem,
            keyPem = c.keyPem,
            caPem = c.caCertPem,
            visitors = visitors,
            connection = listOf(c.deviceId, c.apiToken, c.serverAddr, c.serverPort.toString()).joinToString("\n"),
        )
    }

    private fun credentials(): Credentials? = (enrollment() as? StoredEnrollment.Enrolled)?.credentials

    private fun begin(key: String, op: PendingOp): Boolean {
        var started = false
        core.update {
            if (key in it.pending) it else it.copy(pending = it.pending + (key to op), errors = it.errors - key).also { started = true }
        }
        return started
    }

    private fun end(key: String) = core.update { it.copy(pending = it.pending - key) }

    private fun fail(key: String, message: String) = core.update { it.copy(errors = it.errors + (key to message)) }

    companion object {
        /** A pinned port another app holds is tried again this often. */
        const val PINNED_RETRY_MS = 30_000L

        private const val TAG = "TunnelManager"
        private const val CONNECT_WAIT_SECONDS = 15
        private const val EXPIRY_TICK_MS = 1_000L
        private const val PEER_RETRY_MS = 2_000L
        private const val DEVICE_PROBE_MS = 2_000L
        private const val REFUSAL_SETTLE_MS = 300L
        private const val MAX_REBINDS = 3

        /** In frpc's "start error" when a visitor cannot listen on its port. */
        private const val PORT_TAKEN = "address already in use"


    }
}
