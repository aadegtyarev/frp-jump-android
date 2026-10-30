package ru.adsrv.frpjump

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.devices.DeviceDirectory
import ru.adsrv.frpjump.devices.DeviceListCache
import ru.adsrv.frpjump.session.Poller
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.ports.DevicePortsStore
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.session.AppSession
import ru.adsrv.frpjump.ssh.HostKeyStore
import ru.adsrv.frpjump.ssh.SshEnvironment
import ru.adsrv.frpjump.ssh.SshKeyStore
import ru.adsrv.frpjump.ssh.SshLoginMemory
import ru.adsrv.frpjump.ssh.SshPasswords
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.installFullBouncyCastle
import ru.adsrv.frpjump.store.AtomicTextFile
import ru.adsrv.frpjump.store.CredentialStore
import ru.adsrv.frpjump.store.KeystoreSecretBox
import ru.adsrv.frpjump.tunnel.FrpcRunner
import ru.adsrv.frpjump.tunnel.TunnelManager
import ru.adsrv.frpjump.tunnel.TunnelService
import ru.adsrv.frpjump.ui.web.clearWebData
import ru.adsrv.frpjump.web.WebSiteMemory
import java.io.File

class FrpJumpApp : Application() {
    lateinit var store: CredentialStore
        private set
    val api: FrpJumpApi by lazy { FrpJumpApi() }
    lateinit var devicePorts: DevicePortsStore
        private set
    lateinit var tunnels: TunnelManager
        private set
    lateinit var sshLogins: SshLoginMemory
        private set
    lateinit var sshKeys: SshKeyStore
        private set
    lateinit var sshPasswords: SshPasswords
        private set
    lateinit var hostKeys: HostKeyStore
        private set
    lateinit var ssh: SshSessions
        private set
    lateinit var webSites: WebSiteMemory
        private set
    lateinit var devices: DeviceDirectory
        private set
    lateinit var poller: Poller
        private set
    lateinit var settings: SettingsStore
        private set

    val session = AppSession()

    /** Work that must finish even when the screen that started it goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        store = CredentialStore(filesDir)
        settings = SettingsStore(AtomicTextFile(File(filesDir, "settings.json")))
        devicePorts = DevicePortsStore(filesDir)
        tunnels = TunnelManager(
            enrollment = store::state,
            api = api,
            session = session,
            localPorts = LocalPortStore(AtomicTextFile(File(filesDir, "local_ports.json"))),
            // Shipped as a "native library": Android only allows exec from nativeLibraryDir.
            runner = FrpcRunner(File(applicationInfo.nativeLibraryDir, "libfrpc.so"), File(noBackupFilesDir, "frpc")),
            startService = { TunnelService.start(this) },
        )
        settings.settings.value.let {
            tunnels.portRange = it.localPorts
            tunnels.relayOnly = it.relayOnly
        }
        appScope.launch {
            settings.settings.drop(1).collect {
                tunnels.portRange = it.localPorts
                tunnels.relayOnly = it.relayOnly
                // Running tunnels pick the change up now; otherwise the next poll does.
                if (tunnels.state.value.tunnels.isNotEmpty()) tunnels.syncAsync()
            }
        }
        devices = DeviceDirectory(store, api, session, DeviceListCache(AtomicTextFile(File(filesDir, "device_list.json"))))
        // Keystore and disk: off the main thread. The list shows before the server answers.
        appScope.launch(Dispatchers.IO) { devices.restore() }
        poller = Poller(
            scope = appScope,
            tunnelsUp = tunnels.state.map { it.tunnels.isNotEmpty() },
            backgroundMs = settings.settings.map { it.backgroundPoll.ms },
            removed = session.removed,
        ) { visible ->
            val synced = tunnels.poll()
            val listed = if (visible && devices.wanted) devices.refresh() else true
            synced && listed
        }
        installFullBouncyCastle()
        // Its own Keystore key: re-enrolling the phone must not lose the SSH key.
        val sshBox = KeystoreSecretBox("frpjump-ssh")
        sshLogins = SshLoginMemory(AtomicTextFile(File(filesDir, "ssh_logins.json")))
        sshKeys = SshKeyStore(AtomicTextFile(File(filesDir, "ssh_keys.json")), sshBox)
        sshPasswords = SshPasswords(AtomicTextFile(File(filesDir, "ssh_passwords.json")), sshBox)
        hostKeys = HostKeyStore(AtomicTextFile(File(filesDir, "ssh_host_keys.json")))
        webSites = WebSiteMemory(AtomicTextFile(File(filesDir, "web_site.json")))
        ssh = SshSessions(
            context = this,
            env = SshEnvironment(hostKeys, sshKeys, sshPasswords, keyComment = ::sshKeyComment),
            tunnels = tunnels.state,
            scope = appScope,
        )
    }

    /** The comment on the app's SSH public key: names the phone in `authorized_keys`. */
    fun sshKeyComment(): String = "frp-jump@" + (store.load()?.deviceName ?: "android")

    /**
     * Drop this phone's enrollment locally: stop frpc and the service, forget
     * the device list, the local port map and the credentials. [keepPorts] keeps the owner's
     * custom ports per device, the SSH key, logins, passwords and host
     * keys, and the web views' cookies (re-enrolling the same owner after a
     * removal).
     */
    suspend fun forgetLocally(keepPorts: Boolean) {
        ssh.closeAll()
        devices.forget()
        // Under the tunnel lock: no poll can restart frpc between the stop and the wipe.
        tunnels.shutdown {
            withContext(Dispatchers.IO) {
                if (!keepPorts) {
                    devicePorts.wipe()
                    sshKeys.wipe()
                    sshPasswords.wipe()
                    sshLogins.wipe()
                    hostKeys.wipe()
                    KeystoreSecretBox("frpjump-ssh").destroy()
                    webSites.forget()
                }
                store.wipe()
            }
        }
        // Logins to the devices' web UIs go with the rest.
        if (!keepPorts) clearWebData(null)
        session.clearRemoved()
    }
}
