package ru.adsrv.frpjump

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.direct.DirectChoices
import ru.adsrv.frpjump.direct.DirectKeptStore
import ru.adsrv.frpjump.direct.DirectOfferedStore
import ru.adsrv.frpjump.direct.DirectNotifier
import ru.adsrv.frpjump.direct.NetworkWatcher
import ru.adsrv.frpjump.files.FileTargets
import ru.adsrv.frpjump.files.ListingCache
import ru.adsrv.frpjump.files.PhoneFiles
import ru.adsrv.frpjump.files.UploadNames
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.lock.AppLock
import ru.adsrv.frpjump.ui.applyTheme
import ru.adsrv.frpjump.locale.restoreAppLanguage
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.profiles.ProfileRuntime
import ru.adsrv.frpjump.profiles.ProfileShared
import ru.adsrv.frpjump.profiles.ProfileStore
import ru.adsrv.frpjump.profiles.Profiles
import ru.adsrv.frpjump.restore.RestoreRecord
import ru.adsrv.frpjump.session.AppSession
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.snippets.SnippetStore
import ru.adsrv.frpjump.ssh.SshEnvironment
import ru.adsrv.frpjump.ssh.SshKeyStore
import ru.adsrv.frpjump.ssh.SshPasswords
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.installFullBouncyCastle
import ru.adsrv.frpjump.store.AtomicTextFile
import ru.adsrv.frpjump.store.KeystoreSecretBox
import ru.adsrv.frpjump.tunnel.TunnelService
import ru.adsrv.frpjump.ui.web.clearWebData
import ru.adsrv.frpjump.update.ReleaseSource
import ru.adsrv.frpjump.update.UpdateRecordStore
import ru.adsrv.frpjump.update.Updater
import ru.adsrv.frpjump.update.apkProblem
import ru.adsrv.frpjump.update.archiveFacts
import ru.adsrv.frpjump.update.installedFacts
import ru.adsrv.frpjump.update.updateDownloads
import ru.adsrv.frpjump.web.WebPageMemory
import ru.adsrv.frpjump.web.WebSiteMemory
import java.io.File

/** A device card of a profile, to open from outside the app's screens. */
data class OpenDevice(val profile: String, val device: String)

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

    /** What every SSH login needs: the terminal's sessions and the file screens share it. */
    lateinit var sshEnv: SshEnvironment
        private set

    /** File screens' logins, by the id in their route. */
    val fileTargets = FileTargets()

    /** Names uploads are writing to on any device, so two screens never write one part file. */
    val uploadNames = UploadNames()

    /** Last folder listings for the file screens' offline view, in the cache. */
    lateinit var listings: ListingCache
        private set
    lateinit var webSites: WebSiteMemory
        private set

    /** The page each tunnel's web view showed last, for this run: opening it again continues there. */
    val webPages = WebPageMemory()

    /** The terminal's saved commands, one list per phone; read off the main thread at start. */
    lateinit var snippets: SnippetStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var directKept: DirectKeptStore
        private set
    lateinit var network: NetworkWatcher
        private set
    lateinit var directChoices: DirectChoices
        private set
    lateinit var directNotifier: DirectNotifier
        private set
    lateinit var restoreRecord: RestoreRecord
        private set
    private lateinit var directOffered: DirectOfferedStore

    /** Settings › Version: newer releases on GitHub, their download and check. */
    lateinit var updater: Updater
        private set

    /** A device card to show, asked for by a notification; taken by the navigation. */
    val openDevice = MutableStateFlow<OpenDevice?>(null)

    val session = AppSession()

    /** Settings › Unlock with fingerprint: whether the screen is locked now. */
    val lock = AppLock()

    /** Set by the activity while it is started: the current profile then polls every 2 s. */
    val visible = MutableStateFlow(false)

    /** Work that must finish even when the screen that started it goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Texts.init(this)
        settings = SettingsStore(AtomicTextFile(File(filesDir, "settings.json")))
        restoreAppLanguage(settings.settings.value.language)
        applyTheme(settings.settings.value.theme)
        directKept = DirectKeptStore(AtomicTextFile(File(filesDir, "direct_kept.json")))
        network = NetworkWatcher(this)
        restoreRecord = RestoreRecord(AtomicTextFile(File(filesDir, "restore.json")))
        directOffered = DirectOfferedStore(AtomicTextFile(File(filesDir, "direct_offered.json")))
        snippets = SnippetStore(AtomicTextFile(File(filesDir, "snippets.json")), AtomicTextFile(File(filesDir, "snippets.corrupt.json")))
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
            directKept = directKept,
            network = network.id,
            restoreRecord = restoreRecord,
        )
        val store = ProfileStore(filesDir, boxFor = ::KeystoreSecretBox, storageFor = ::AtomicTextFile)
        profiles = Profiles(store, restoreRecord.profiles) { profile, profileStore, currentId ->
            ProfileRuntime(profile.id, profileStore.credentials(profile), profileStore.dir(profile.id), currentId, shared)
        }
        directNotifier = DirectNotifier(this, profiles, directOffered, network.id, restoreRecord.profiles).also { it.start(appScope) }
        directChoices = DirectChoices(profiles, directKept, network.id, directNotifier::retried)
        // Keystore and disk (and the v1 upgrade): off the main thread. The UI waits for it.
        appScope.launch(Dispatchers.IO) {
            snippets.load()
            // Before any profile applies its tunnels.
            directKept.load()
            // Before any tunnel comes back: offers made before a restart are not made again.
            directOffered.load()
            // Before any profile polls: which ones bring their tunnels back.
            val restoring = restoreRecord.load().isNotEmpty()
            network.start()
            // v1 kept one frpc's files right in here; each profile now has a directory of its own.
            frpcRoot.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            // Tunnels to bring back and no screen to retry from: try again a few times.
            val loaded = if (restoring) profiles.loadRetrying(LOAD_RETRIES_MS) else profiles.load()
            if (loaded) {
                // frpc directories of profiles no longer listed (forget interrupted).
                val ids = profiles.state.value.runtimes.keys
                frpcRoot.listFiles()?.filter { it.isDirectory && it.name !in ids }?.forEach { it.deleteRecursively() }
                restoreRecord.retain(ids)
            }
        }
        // Opened with tunnels up (or still to come back) while the service could not
        // start from the background: start it now, the app being in the foreground.
        appScope.launch {
            combine(visible, profiles.tunnelCount, profiles.restorePending) { shown, up, pending ->
                shown && (up > 0 || (pending ?: 0) > 0)
            }.distinctUntilChanged().collect { wanted ->
                if (wanted && !TunnelService.running) TunnelService.start(this@FrpJumpApp)
            }
        }
        updater = Updater(
            installed = BuildConfig.VERSION_NAME,
            records = UpdateRecordStore(AtomicTextFile(File(filesDir, "update.json"))),
            source = ReleaseSource(),
            downloads = updateDownloads(this),
            scope = appScope,
            inspect = { apk, version -> apkProblem(archiveFacts(this, apk), installedFacts(this), version) },
        ).also { it.start(visible) }
        installFullBouncyCastle()
        // Its own Keystore key, shared by all profiles: re-enrolling must not lose the SSH key.
        val sshBox = KeystoreSecretBox(SSH_KEY_ALIAS)
        sshKeys = SshKeyStore(AtomicTextFile(File(filesDir, "ssh_keys.json")), sshBox)
        sshPasswords = SshPasswords(AtomicTextFile(File(filesDir, "ssh_passwords.json")), sshBox)
        webSites = WebSiteMemory(AtomicTextFile(File(filesDir, "web_site.json")))
        sshEnv = SshEnvironment(sshKeys, sshPasswords, keyComment = ::sshKeyComment)
        listings = ListingCache({ profile, key -> AtomicTextFile(File(PhoneFiles.listingsDir(this, profile), "$key.json")) })
        // Files handed to other apps a day ago or more (Open with, Share).
        appScope.launch(Dispatchers.IO) { PhoneFiles.pruneOpenCache(this@FrpJumpApp, OPEN_CACHE_AGE_MS) }
        ssh = SshSessions(
            context = this,
            env = sshEnv,
            tunnelsUp = profiles.upTunnels,
            scope = appScope,
        )
    }

    /** The comment on the app's SSH public key: names the phone in `authorized_keys`. Keystore: off the main thread. */
    fun sshKeyComment(): String = "frp-jump@" + (profiles.current?.store?.load()?.deviceName ?: "android")

    /**
     * Forget profile [id] on this phone: close its SSH sessions, stop its
     * frpc, wipe its credentials, key, lists, ports and remembered SSH
     * passwords ([Profiles.forget]). [keepData] keeps the profile, empty,
     * with its custom ports, logins, host keys and passwords (enrolling it
     * again after its server removed it). Otherwise the web views' data goes
     * too when it is this profile's, and with the last profile also the
     * shared SSH key and passwords, as v1's "Forget this phone" did. The
     * passwords and the key go before the index drops the profile, so an
     * interruption leaves it listed rather than them behind.
     */
    suspend fun forgetProfile(id: String, keepData: Boolean) {
        ssh.closeProfile(id)
        profiles.forget(id, keepData) { last ->
            sshPasswords.forgetProfile(id)
            directKept.removeProfile(id)
            if (last) {
                sshKeys.wipe()
                sshPasswords.wipe()
                KeystoreSecretBox(SSH_KEY_ALIAS).destroy()
            }
        }
        // Its files handed to other apps go in any case; the folders seen on its devices with its data.
        withContext(Dispatchers.IO) { PhoneFiles.openCacheDir(this@FrpJumpApp, id).deleteRecursively() }
        webPages.forgetProfile(id)
        if (keepData) return
        withContext(Dispatchers.IO) { PhoneFiles.listingsDir(this@FrpJumpApp, id).deleteRecursively() }
        val noneLeft = profiles.state.value.index.profiles.isEmpty()
        // Interrupted before or after the memory is forgotten, it no longer matches any
        // site, so the next page opened clears the web data anyway.
        val clearWeb = withContext(Dispatchers.IO) {
            (noneLeft || webSites.belongsTo(id)).also { if (it) webSites.forget() }
        }
        // Logins to the devices' web UIs go with the rest. The web view's classes: main thread.
        if (clearWeb) clearWebData(null)
    }

    private companion object {
        const val SSH_KEY_ALIAS = "frpjump-ssh"
        const val OPEN_CACHE_AGE_MS = 24 * 60 * 60 * 1000L
        val LOAD_RETRIES_MS = listOf(10_000L, 30_000L, 60_000L, 60_000L, 60_000L)
    }
}
