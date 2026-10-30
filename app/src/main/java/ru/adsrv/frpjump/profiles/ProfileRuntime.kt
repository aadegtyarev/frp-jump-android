package ru.adsrv.frpjump.profiles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.connections.ConnectionStore
import ru.adsrv.frpjump.connections.migrateDevicePorts
import ru.adsrv.frpjump.devices.DeviceDirectory
import ru.adsrv.frpjump.devices.DeviceListCache
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.session.Poller
import ru.adsrv.frpjump.session.ProfileSession
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.ssh.HostKeyStore
import ru.adsrv.frpjump.ssh.SshLoginMemory
import ru.adsrv.frpjump.store.AtomicTextFile
import ru.adsrv.frpjump.store.CredentialStore
import ru.adsrv.frpjump.store.TextStorage
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
)

/**
 * Everything one profile runs: its stores, device list, frpc and poller.
 * The constructor reads the profile's files: create it off the main thread.
 */
class ProfileRuntime(
    val id: String,
    val store: CredentialStore,
    dir: File,
    /** The id of the profile the app shows. */
    currentId: StateFlow<String?>,
    shared: ProfileShared,
) {
    private val scope = CoroutineScope(shared.scope.coroutineContext + SupervisorJob(shared.scope.coroutineContext[Job]))

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
    val devices = DeviceDirectory(store, shared.api, session, DeviceListCache(shared.storageFor(File(dir, ProfileFiles.DEVICE_LIST))))
    val tunnels = TunnelManager(
        profile = id,
        enrollment = store::state,
        api = shared.api,
        session = session,
        localPorts = shared.localPorts,
        // Shipped as a "native library": Android only allows exec from nativeLibraryDir.
        runner = FrpcRunner(shared.frpcBinary, File(shared.frpcRoot, id)),
        startService = shared.startService,
    )

    /** Whether the app shows this profile. */
    val current: StateFlow<Boolean> =
        currentId.map { it == id }.stateIn(scope, SharingStarted.Eagerly, currentId.value == id)

    init {
        shared.settings.value.let {
            tunnels.portRange = it.localPorts
            tunnels.relayOnly = it.relayOnly
        }
        scope.launch {
            shared.settings.drop(1).collect {
                tunnels.portRange = it.localPorts
                tunnels.relayOnly = it.relayOnly
                // Running tunnels pick the change up now; otherwise the next poll does.
                if (tunnels.state.value.tunnels.isNotEmpty()) tunnels.syncAsync()
            }
        }
        Poller(
            scope = scope,
            visible = shared.visible,
            current = current,
            tunnelsUp = tunnels.state.map { it.tunnels.isNotEmpty() },
            backgroundMs = shared.settings.map { it.backgroundPoll.ms },
            removed = session.removed,
        ) { shown ->
            val synced = tunnels.poll()
            val listed = if (shown && devices.wanted) devices.refresh() else true
            synced && listed
        }
        // The list shows before the server answers.
        scope.launch(Dispatchers.IO) { devices.restore() }
    }

    /** Stops the poller and everything else this runtime runs; after "Forget profile". */
    fun close() {
        scope.cancel()
        tunnels.close()
    }
}
