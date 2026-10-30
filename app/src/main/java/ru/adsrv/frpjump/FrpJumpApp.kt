package ru.adsrv.frpjump

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        installFullBouncyCastle()
        // Its own Keystore key: re-enrolling the phone must not lose the SSH key.
        val sshBox = KeystoreSecretBox("frpjump-ssh")
        sshLogins = SshLoginMemory(AtomicTextFile(File(filesDir, "ssh_logins.json")))
        sshKeys = SshKeyStore(AtomicTextFile(File(filesDir, "ssh_keys.json")), sshBox)
        sshPasswords = SshPasswords(AtomicTextFile(File(filesDir, "ssh_passwords.json")), sshBox)
        hostKeys = HostKeyStore(AtomicTextFile(File(filesDir, "ssh_host_keys.json")))
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
     * the local port map and the credentials. [keepPorts] keeps the owner's
     * custom ports per device and the SSH key, logins, passwords and host
     * keys (re-enrolling the same owner after a removal).
     */
    suspend fun forgetLocally(keepPorts: Boolean) {
        ssh.closeAll()
        tunnels.shutdown()
        if (!keepPorts) {
            devicePorts.wipe()
            sshKeys.wipe()
            sshPasswords.wipe()
            sshLogins.wipe()
            hostKeys.wipe()
            KeystoreSecretBox("frpjump-ssh").destroy()
        }
        store.wipe()
        session.clearRemoved()
    }
}
