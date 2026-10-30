package ru.adsrv.frpjump

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.profiles.ProfileRuntime
import ru.adsrv.frpjump.profiles.ProfileShared
import ru.adsrv.frpjump.profiles.ProfileStore
import ru.adsrv.frpjump.profiles.Profiles
import ru.adsrv.frpjump.session.AppSession
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.ssh.SshEnvironment
import ru.adsrv.frpjump.ssh.SshKeyStore
import ru.adsrv.frpjump.ssh.SshPasswords
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.installFullBouncyCastle
import ru.adsrv.frpjump.store.AtomicTextFile
import ru.adsrv.frpjump.store.KeystoreSecretBox
import ru.adsrv.frpjump.tunnel.TunnelService
import ru.adsrv.frpjump.ui.web.clearWebData
import ru.adsrv.frpjump.web.WebSiteMemory
import java.io.File

class FrpJumpApp : Application() {
    val api: FrpJumpApi by lazy { FrpJumpApi() }
    lateinit var profiles: Profiles
        private set
    lateinit var sshKeys: SshKeyStore
        private set
    lateinit var sshPasswords: SshPasswords
        private set
    lateinit var ssh: SshSessions
        private set
    lateinit var webSites: WebSiteMemory
        private set
    lateinit var settings: SettingsStore
        private set

    val session = AppSession()

    /** Set by the activity while it is started: the current profile then polls every 2 s. */
    val visible = MutableStateFlow(false)

    /** Work that must finish even when the screen that started it goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(AtomicTextFile(File(filesDir, "settings.json")))
        val frpcRoot = File(noBackupFilesDir, "frpc")
        val shared = ProfileShared(
            api = api,
            localPorts = LocalPortStore(AtomicTextFile(File(filesDir, "local_ports.json"))),
            settings = settings.settings,
            visible = visible,
            frpcBinary = File(applicationInfo.nativeLibraryDir, "libfrpc.so"),
            frpcRoot = frpcRoot,
            startService = { TunnelService.start(this) },
            scope = appScope,
        )
        val store = ProfileStore(filesDir, boxFor = ::KeystoreSecretBox, storageFor = ::AtomicTextFile)
        profiles = Profiles(store) { profile, profileStore, currentId ->
            ProfileRuntime(profile.id, profileStore.credentials(profile), profileStore.dir(profile.id), currentId, shared)
        }
        // Keystore and disk (and the v1 upgrade): off the main thread. The UI waits for it.
        appScope.launch(Dispatchers.IO) {
            // v1 kept one frpc's files right in here; each profile now has a directory of its own.
            frpcRoot.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            profiles.load()
        }
        installFullBouncyCastle()
        // Its own Keystore key, shared by all profiles: re-enrolling must not lose the SSH key.
        val sshBox = KeystoreSecretBox(SSH_KEY_ALIAS)
        sshKeys = SshKeyStore(AtomicTextFile(File(filesDir, "ssh_keys.json")), sshBox)
        sshPasswords = SshPasswords(AtomicTextFile(File(filesDir, "ssh_passwords.json")), sshBox)
        webSites = WebSiteMemory(AtomicTextFile(File(filesDir, "web_site.json")))
        ssh = SshSessions(
            context = this,
            env = SshEnvironment(sshKeys, sshPasswords, keyComment = ::sshKeyComment),
            tunnelsUp = profiles.upTunnels,
            scope = appScope,
        )
    }

    /** The comment on the app's SSH public key: names the phone in `authorized_keys`. Keystore: off the main thread. */
    fun sshKeyComment(): String = "frp-jump@" + (profiles.current?.store?.load()?.deviceName ?: "android")

    /**
     * Forget profile [id] on this phone: close its SSH sessions, stop its
     * frpc, wipe its credentials, key, lists and ports ([Profiles.forget]).
     * [keepData] keeps the profile, empty, with its custom ports, logins and
     * host keys (enrolling it again after its server removed it). Otherwise
     * the web views' data goes too when it is this profile's, and with the
     * last profile gone also the shared SSH key and passwords, as v1's
     * "Forget this phone" did.
     */
    suspend fun forgetProfile(id: String, keepData: Boolean) {
        ssh.closeProfile(id)
        profiles.forget(id, keepData)
        if (keepData) return
        val noneLeft = profiles.state.value.index.profiles.isEmpty()
        val clearWeb = withContext(Dispatchers.IO) {
            if (noneLeft) {
                sshKeys.wipe()
                sshPasswords.wipe()
                KeystoreSecretBox(SSH_KEY_ALIAS).destroy()
            }
            (noneLeft || webSites.belongsTo(id)).also { if (it) webSites.forget() }
        }
        // Logins to the devices' web UIs go with the rest. The web view's classes: main thread.
        if (clearWeb) clearWebData(null)
    }

    private companion object {
        const val SSH_KEY_ALIAS = "frpjump-ssh"
    }
}
