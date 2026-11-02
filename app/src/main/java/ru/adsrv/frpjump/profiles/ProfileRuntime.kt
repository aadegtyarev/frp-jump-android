package ru.adsrv.frpjump.profiles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.connections.ConnectionStore
import ru.adsrv.frpjump.connections.migrateDevicePorts
import ru.adsrv.frpjump.connections.tunnelPins
import ru.adsrv.frpjump.devices.DeviceDirectory
import ru.adsrv.frpjump.devices.DeviceListCache
import ru.adsrv.frpjump.devices.ServerFeaturesCache
import ru.adsrv.frpjump.direct.DirectKeptStore
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.restore.RestoreRecord
import ru.adsrv.frpjump.session.Poller
import ru.adsrv.frpjump.session.ProfileSession
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.ssh.HostKeyStore
import ru.adsrv.frpjump.ssh.SshLoginMemory
import ru.adsrv.frpjump.store.AtomicTextFile
import ru.adsrv.frpjump.store.CredentialStore
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.tunnel.DirectMode
import ru.adsrv.frpjump.tunnel.FrpcRunner
import ru.adsrv.frpjump.tunnel.TunnelManager
import ru.adsrv.frpjump.web.WebCertStore
import java.io.File
import java.util.UUID

/** What every profile's runtime shares with the others. */
class ProfileShared(
    val api: FrpJumpApi,
    /** One map, one range for all profiles. */
    val localPorts: LocalPortStore,
    val settings: StateFlow<AppSettings>,
    /** The app is on screen. */
    val visible: StateFlow<Boolean>,
    /** frpc as shipped in the APK. */
    val frpcBinary: File,
    /** Each profile's frpc gets its own directory in here. */
    val frpcRoot: File,
    /** Starts the foreground service, see [TunnelManager]. */
    val startService: () -> Boolean,
    val scope: CoroutineScope,
    /** A profile file as a [TextStorage]: [AtomicTextFile] in the app. */
    val storageFor: (File) -> TextStorage = ::AtomicTextFile,
    /** "Keep through the server" choices of all profiles. */
    val directKept: DirectKeptStore,
    /** The current network's identity ([ru.adsrv.frpjump.direct.networkIdentity]); null without one. */
    val network: StateFlow<String?>,
    /** Which profiles had tunnels up, for a cold start; loaded before any runtime is made. */
    val restoreRecord: RestoreRecord,
)

/**
 * Everything one profile runs: its stores, device list, frpc and poller.
 * The constructor prepares the profile's files off Main. Polling, startup cache
 * work and runtime collectors start after [Profiles] publishes ownership and
 * calls [activate]. The prepared tunnel manager's idle observers close with it.
 */
class ProfileRuntime(
    val id: String,
    val store: CredentialStore,
    dir: File,
    /** The id of the profile the app shows. */
    private val currentId: StateFlow<String?>,
    private val shared: ProfileShared,
) {

    val session = ProfileSession()
    val hostKeys = HostKeyStore(shared.storageFor(File(dir, ProfileFiles.HOST_KEYS)))
    val sshLogins = SshLoginMemory(shared.storageFor(File(dir, ProfileFiles.SSH_LOGINS)))
    val webCerts = WebCertStore(shared.storageFor(File(dir, ProfileFiles.WEB_CERTS)))

    /** The profile's named connections; the old per-device ports become some on the first start. */
    val connections: ConnectionStore = shared.storageFor(File(dir, ProfileFiles.CONNECTIONS)).let { file ->
        migrateDevicePorts(
            profile = id,
            connections = file,
            legacy = shared.storageFor(File(dir, ProfileFiles.DEVICE_PORTS)),
            localPorts = shared.localPorts.load(),
            sshUser = { device, port -> sshLogins.get(device, port).user },
            newId = { UUID.randomUUID().toString() },
        )
        ConnectionStore(file, corruptCopy = shared.storageFor(File(dir, ProfileFiles.CONNECTIONS_CORRUPT)))
    }
    // Finish fallible file preparation before allocating either runtime or tunnel jobs.
    private val deviceCache = DeviceListCache(shared.storageFor(File(dir, ProfileFiles.DEVICE_LIST)))
    private val featuresCache = ServerFeaturesCache(shared.storageFor(File(dir, ProfileFiles.SERVER_FEATURES)))
    private val runner = FrpcRunner(shared.frpcBinary, File(shared.frpcRoot, id))
    private val initialPortRange = shared.settings.value.localPorts
    private val initiallyCurrent = currentId.value == id
    private val scope = CoroutineScope(shared.scope.coroutineContext + SupervisorJob(shared.scope.coroutineContext[Job]))
    private var activated = false
    private var closed = false

    val devices = DeviceDirectory(store, shared.api, session, deviceCache, featuresCache, scope)
    val tunnels = TunnelManager(
        profile = id,
        enrollment = store::state,
        api = shared.api,
        session = session,
        localPorts = shared.localPorts,
        // Shipped as a "native library": Android only allows exec from nativeLibraryDir.
        runner = runner,
        startService = shared.startService,
        directMode = { device ->
            when {
                !shared.settings.value.direct -> DirectMode.OFF
                shared.directKept.isKept(id, device, shared.network.value) -> DirectMode.KEPT
                else -> DirectMode.ON
            }
        },
        recordTunnels = { shared.restoreRecord.set(id, it) },
        pins = { tunnelPins(connections.connections.value) },
    )

    /** The local port map shared by all profiles: a pin is written there when it is saved. */
    val localPorts: LocalPortStore = shared.localPorts

    /** Whether the app shows this profile. */
    private val _current = MutableStateFlow(initiallyCurrent)
    val current: StateFlow<Boolean> = _current

    init {
        tunnels.portRange = initialPortRange
    }

    /** Called after publication; repeated profile updates must not start duplicate collectors. */
    @Synchronized
    internal fun activate() {
        if (closed || activated) return
        activated = true
        _current.value = currentId.value == id
        tunnels.portRange = shared.settings.value.localPorts
        scope.launch {
            currentId.map { it == id }.distinctUntilChanged().collect { _current.value = it }
        }
        scope.launch {
            shared.settings.map { it.localPorts }.distinctUntilChanged().collect {
                // Include the first value: settings may have changed while this batch was prepared.
                if (tunnels.portRange != it) {
                    tunnels.portRange = it
                    if (tunnels.state.value.tunnels.isNotEmpty()) tunnels.syncAsync()
                }
            }
        }
        // The setting or the network changed whether tunnels try direct: frpc reloads the
        // tunnels that change, the others keep their connections. ("Keep" and "Retry"
        // apply themselves, see DirectChoices.)
        scope.launch {
            combine(shared.settings.map { it.direct }, shared.network) { direct, network -> direct to network }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (tunnels.state.value.tunnels.isNotEmpty()) tunnels.reapply() }
        }
        Poller(
            scope = scope,
            visible = shared.visible,
            current = current,
            tunnelsUp = tunnels.state.map { it.tunnels.isNotEmpty() },
            backgroundMs = shared.settings.map { it.backgroundPoll.ms },
            removed = session.removed,
            restoring = combine(shared.restoreRecord.profiles, tunnels.state) { record, state ->
                (record?.get(id) ?: 0) > 0 && state.tunnels.isEmpty()
            },
        ) { shown ->
            val synced = tunnels.poll()
            val listed = if (shown && devices.wanted) devices.refresh() else true
            synced && listed
        }
        // The list shows before the server answers; then the server says what it supports.
        scope.launch(Dispatchers.IO) {
            devices.restore()
            devices.checkFeatures()
        }
    }

    /** Stops the poller and everything else this runtime runs; after "Forget profile". */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        tunnels.close()
    }
}
