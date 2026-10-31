package ru.adsrv.frpjump.tunnel

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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
)

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

    val state: StateFlow<TunnelsState> = combine(core, runner.states) { c, states ->
        TunnelsState(
            tunnels = c.planned.map {
                Tunnel(
                    device = it.grant.exposerDeviceName,
                    port = it.grant.targetPort,
                    grantId = it.grant.grantId,
                    localPort = it.localPort,
                    state = states[it.grant.grantId] ?: VisitorState(TunnelStatus.CONNECTING),
                    direct = c.modes[it.grant.grantId] ?: DirectMode.ON,
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
                while (states.values.any { it.status == TunnelStatus.TRYING || it.confirming }) {
                    delay(EXPIRY_TICK_MS)
                    withContext(Dispatchers.IO) { runner.expireTrying() }
                }
            }
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
            core.update { it.copy(mismatch = e.message) }
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
            fail(key, "The server has not listed the connection yet; it will appear on a later poll.")
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
        val (planned, modes) = withContext(Dispatchers.IO) {
            // Under the shared map's lock: two profiles planning at once never pick the same port.
            val plan = localPorts.update { ports ->
                planTunnels(profile, desired.consumed, ports, LocalPortAllocator(portRange), runner.heldPorts)
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
                val visitors = plan.map {
                    it.visitor(direct = modes[it.grant.grantId] == DirectMode.ON, attempt = attempts[it.grant.grantId] ?: 0)
                }
                runner.apply(launchFor(credentials, visitors), plan.map { it.localPort }.toSet())
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

    private companion object {
        const val TAG = "TunnelManager"
        const val CONNECT_WAIT_SECONDS = 15
        const val EXPIRY_TICK_MS = 1_000L
        const val PEER_RETRY_MS = 2_000L
    }
}
