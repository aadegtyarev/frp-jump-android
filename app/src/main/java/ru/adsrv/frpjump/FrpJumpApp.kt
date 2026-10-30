package ru.adsrv.frpjump

import android.app.Application
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.ports.DevicePortsStore
import ru.adsrv.frpjump.ports.LocalPortStore
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

    /** A one-time message for the enrollment screen, e.g. after "Forget this phone". */
    var notice: String? = null

    override fun onCreate() {
        super.onCreate()
        store = CredentialStore(filesDir)
        devicePorts = DevicePortsStore(filesDir)
        tunnels = TunnelManager(
            context = this,
            store = store,
            api = api,
            localPorts = LocalPortStore(filesDir),
            // Shipped as a "native library": Android only allows exec from nativeLibraryDir.
            frpcBinary = File(applicationInfo.nativeLibraryDir, "libfrpc.so"),
            workDir = File(noBackupFilesDir, "frpc"),
        )
    }
}
