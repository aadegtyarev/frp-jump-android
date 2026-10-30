package ru.adsrv.frpjump.tunnel

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.DesiredStateResponse
import ru.adsrv.frpjump.api.AgentApi
import ru.adsrv.frpjump.api.ProtocolMismatchException
import ru.adsrv.frpjump.ports.DevicePort
import ru.adsrv.frpjump.ports.LocalPortAllocator
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.ports.wireProtocolFor
import ru.adsrv.frpjump.session.AppSession
import ru.adsrv.frpjump.session.PollBackoff
import ru.adsrv.frpjump.store.StoredEnrollment
import ru.adsrv.frpjump.store.Credentials
import ru.adsrv.frpjump.ui.userMessage

/** One consumed grant as the card shows it. */
data class Tunnel(
    val device: String,
    val port: Int,
    val grantId: String,
    val localPort: Int,
    val state: VisitorState,
) {
    val key get() = localPortKey(device, port)
}

enum class PendingOp { CONNECTING, DISCONNECTING }

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
)

private data class Core(
    val planned: List<PlannedTunnel> = emptyList(),
    val pending: Map<String, PendingOp> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val syncError: String? = null,
    val mismatch: String? = null,
)

/**
 * The phone's agent loop, after the Linux agent's `poller.sync_once`:
 * heartbeat, fetch desired state, run frpc with one tunnel per consumed
 * grant. Desired state is the only source of truth for what runs; connect
 * and disconnect only ask the server and then sync.
 *
 * A protocol mismatch stops applying anything but leaves a running frpc
 * alone (`ProtocolMismatchError` in the agent). Polling is driven from
 * outside ([pollEvery]) so the UI and, later, a background setting choose
 * the interval; frpc keeps running between polls either way.
 */
class TunnelManager(
    /** The stored enrollment, read on every cycle ([ru.adsrv.frpjump.store.CredentialStore.state]). */
    private val enrollment: () -> StoredEnrollment,
    private val api: AgentApi,
    private val session: AppSession,
    private val localPorts: LocalPortStore,
    private val runner: FrpcControl,
    /** Starts the foreground service; false when Android refuses. */
    private val startService: () -> Boolean,
    private val stopServiceNow: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncing = Mutex()
    private val core = MutableStateFlow(Core())

    /** Whether this manager has the foreground service running; only touched under [syncing]. */
    private var serviceUp = false

    /** Settings to come (stage 5+); kept as parameters here. */
    var portRange: IntRange = 40000..40999
    var relayOnly: Boolean = false

    val state: StateFlow<TunnelsState> = combine(core, runner.states) { c, states ->
        TunnelsState(
            tunnels = c.planned.map {
                Tunnel(
                    device = it.grant.exposerDeviceName,
                    port = it.grant.targetPort,
                    grantId = it.grant.grantId,
                    localPort = it.localPort,
                    state = states[it.grant.grantId] ?: VisitorState(TunnelStatus.CONNECTING),
                )
            },
            pending = c.pending,
            errors = c.errors,
            syncError = c.syncError,
            mismatch = c.mismatch,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TunnelsState())

    init {
        // The server removed this phone (seen by any call, the device list too):
        // the REMOVED screen says tunnels are stopped, so stop them.
        scope.launch {
            session.removed.collect { removed -> if (removed) syncing.withLock { stopAllLocked() } }
        }
    }

    /** frpc's recent output, for diagnostics. */
    fun frpcLog(): List<String> = runner.recentLog()

    /**
     * Syncs now, then every [intervalMs] until cancelled; after failures it
     * waits 10 s, then 30 s ([PollBackoff]). Paused while the server has
     * removed this phone ([AppSession.removed]).
     */
    suspend fun pollEvery(intervalMs: Long) {
        val backoff = PollBackoff()
        while (true) {
            session.removed.first { !it }
            delay(backoff.next(syncOnce().ok, intervalMs))
        }
    }

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
            // Unreadable just now (Keystore busy): leave what runs alone, try again later.
            is StoredEnrollment.Unavailable -> return SyncResult(null, ok = false)
        }
        if (session.removed.value) {
            stopAllLocked()
            return SyncResult(null, ok = true)
        }
        try {
            api.heartbeat(credentials)
            val desired = api.desiredState(credentials)
            apply(credentials, desired)
            core.update { it.copy(syncError = null, mismatch = null) }
            SyncResult(desired, ok = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtocolMismatchException) {
            core.update { it.copy(mismatch = e.message) }
            SyncResult(null, ok = false)
        } catch (e: Exception) {
            if (noteRemoval(e, credentials)) stopAllLocked()
            core.update { it.copy(syncError = e.userMessage()) }
            SyncResult(null, ok = false)
        }
    }

    /**
     * A 401 means the server no longer knows this token: the phone was
     * deleted. Only for the token still stored: a late answer to a call made
     * before "Forget" (or a re-enrollment) says nothing about the new one.
     */
    private fun noteRemoval(e: Exception, used: Credentials): Boolean {
        if (e !is ApiException || e.status != 401 || credentials()?.apiToken != used.apiToken) return false
        session.markRemoved()
        return true
    }

    /** Stops frpc and the service; the caller holds [syncing]. */
    private suspend fun stopAllLocked() {
        withContext(Dispatchers.IO) { runner.stop() }
        core.update { it.copy(planned = emptyList()) }
        stopService()
    }

    fun connectAsync(device: String, port: DevicePort) = scope.launch { connect(device, port) }

    fun disconnectAsync(device: String, port: Int) = scope.launch { disconnect(device, port) }

    fun disconnectAllAsync() = scope.launch { disconnectAll() }

    fun syncAsync() = scope.launch { sync() }

    /**
     * Ask for the grant, then poll desired state every second for up to
     * [CONNECT_WAIT_SECONDS] until it lists the grant (the server answers
     * connect before the grant is necessarily visible to every poll).
     */
    suspend fun connect(device: String, port: DevicePort) {
        val key = localPortKey(device, port.port)
        val credentials = credentials() ?: return
        if (!begin(key, PendingOp.CONNECTING)) return
        try {
            val grant = api.connect(credentials, device, port.port, wireProtocolFor(port))
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
     * whatever the server said; the next successful poll brings back
     * anything the server still lists.
     */
    suspend fun disconnectAll() {
        val credentials = credentials()
        val tunnels = core.value.planned
        if (credentials != null) {
            for (t in tunnels) {
                try {
                    api.disconnect(credentials, t.grant.exposerDeviceName, t.grant.targetPort)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "disconnect ${t.grant} failed: ${e.userMessage()}")
                }
            }
        }
        syncing.withLock { stopAllLocked() }
        sync()
    }

    /**
     * "Forget this phone": stop everything, drop frpc's files and the port
     * map, then [wipeCredentials], all in one locked section: a poll that
     * waits for the lock finds no credentials and starts nothing.
     */
    suspend fun shutdown(wipeCredentials: suspend () -> Unit) = syncing.withLock {
        withContext(Dispatchers.IO) {
            runner.wipe()
            localPorts.wipe()
        }
        core.value = Core()
        stopService()
        wipeCredentials()
    }

    private suspend fun apply(credentials: Credentials, desired: DesiredStateResponse) {
        val planned = withContext(Dispatchers.IO) {
            val ports = localPorts.load()
            val before = ports.toMap()
            val plan = planTunnels(desired.consumed, ports, LocalPortAllocator(portRange), runner.heldPorts)
            if (ports != before) localPorts.save(ports)
            if (plan.isEmpty()) {
                runner.stop()
            } else {
                runner.apply(launchFor(credentials, plan), plan.map { it.localPort }.toSet())
            }
            plan
        }
        core.update { it.copy(planned = planned) }
        if (planned.isEmpty()) stopService() else if (!serviceUp) serviceUp = startService()
    }

    private fun stopService() {
        if (serviceUp) stopServiceNow()
        serviceUp = false
    }

    private fun launchFor(c: Credentials, plan: List<PlannedTunnel>): FrpcLaunch {
        val relayOnly = relayOnly
        val visitors = plan.map { it.visitor() }
        return FrpcLaunch(
            configFor = { tls ->
                Toml.write(
                    buildFrpcConfig(
                        // From enrollment, as the agent's state.json relay_addr/relay_port.
                        deviceId = c.deviceId,
                        apiToken = c.apiToken,
                        serverAddr = c.serverAddr,
                        serverPort = c.serverPort,
                        tls = tls,
                        visitors = visitors,
                        relayOnly = relayOnly,
                    ),
                )
            },
            certPem = c.certPem,
            keyPem = c.keyPem,
            caPem = c.caCertPem,
            grantIds = plan.map { it.grant.grantId },
            relayOnly = relayOnly,
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
    }
}
