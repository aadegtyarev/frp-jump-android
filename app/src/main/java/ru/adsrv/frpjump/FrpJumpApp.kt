package ru.adsrv.frpjump

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.ports.DevicePortsStore
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.session.AppSession
import ru.adsrv.frpjump.store.CredentialStore
import ru.adsrv.frpjump.tunnel.TunnelManager
import java.io.File

class FrpJumpApp : Application() {
    lateinit var store: CredentialStore
        private set
    val api: FrpJumpApi by lazy { FrpJumpApi() }
    lateinit var devicePorts: DevicePortsStore
        private set
    lateinit var tunnels: TunnelManager
        private set

    val session = AppSession()

    /** Work that must finish even when the screen that started it goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        store = CredentialStore(filesDir)
        devicePorts = DevicePortsStore(filesDir)
        tunnels = TunnelManager(
            context = this,
            store = store,
            api = api,
            session = session,
            localPorts = LocalPortStore(filesDir),
            // Shipped as a "native library": Android only allows exec from nativeLibraryDir.
            frpcBinary = File(applicationInfo.nativeLibraryDir, "libfrpc.so"),
            workDir = File(noBackupFilesDir, "frpc"),
        )
    }

    /**
     * Drop this phone's enrollment locally: stop frpc and the service, forget
     * the local port map and the credentials. [keepPorts] keeps the owner's
     * custom ports per device (re-enrolling the same owner after a removal).
     */
    suspend fun forgetLocally(keepPorts: Boolean) {
        tunnels.shutdown()
        if (!keepPorts) devicePorts.wipe()
        store.wipe()
        session.clearRemoved()
    }
}
