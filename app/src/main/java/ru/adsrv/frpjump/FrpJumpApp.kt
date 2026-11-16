package ru.adsrv.frpjump

import android.app.Application
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.direct.DirectChoices
import ru.adsrv.frpjump.direct.DirectKeptStore
import ru.adsrv.frpjump.direct.DirectChannel
import ru.adsrv.frpjump.direct.NetworkWatcher
import ru.adsrv.frpjump.files.FileTargets
import ru.adsrv.frpjump.files.FilesKeeper
import ru.adsrv.frpjump.files.ListingCache
import ru.adsrv.frpjump.files.PhoneFiles
import ru.adsrv.frpjump.files.UploadNames
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.lock.AppLock
import ru.adsrv.frpjump.ui.applyTheme
import ru.adsrv.frpjump.locale.restoreAppLanguage
import ru.adsrv.frpjump.mqtt.ExecutorScheduler
import ru.adsrv.frpjump.mqtt.HiveMqConnector
import ru.adsrv.frpjump.mqtt.MqttEnvironment
import ru.adsrv.frpjump.mqtt.MqttLogins
import ru.adsrv.frpjump.mqtt.MqttSessions
import ru.adsrv.frpjump.mqtt.PinStore
import ru.adsrv.frpjump.mqtt.PublishMemory
import ru.adsrv.frpjump.mqtt.plugins.PluginObserver
import ru.adsrv.frpjump.mqtt.plugins.PluginStore
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.ports.PortReservations
import ru.adsrv.frpjump.ports.profilePortPrefix
import ru.adsrv.frpjump.connections.tunnelPins
import ru.adsrv.frpjump.profiles.ProfileRuntime
import ru.adsrv.frpjump.profiles.ProfileShared
import ru.adsrv.frpjump.profiles.ProfileStore
import ru.adsrv.frpjump.profiles.Profiles
import ru.adsrv.frpjump.restore.RestoreRecord
import ru.adsrv.frpjump.session.AppSession
import ru.adsrv.frpjump.sessions.OpenSessions
import ru.adsrv.frpjump.sessions.SessionCloser
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.snippets.SnippetStore
import ru.adsrv.frpjump.notices.NoticeAction
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.notices.Notices
import ru.adsrv.frpjump.undo.PendingUndo
import ru.adsrv.frpjump.undo.TunnelDisconnects
import ru.adsrv.frpjump.undo.UndoKind
import ru.adsrv.frpjump.undo.undoText
import ru.adsrv.frpjump.ssh.SshEnvironment
import ru.adsrv.frpjump.ssh.SshKeyStore
import ru.adsrv.frpjump.ssh.SshPasswords
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.SessionRecordStore
import ru.adsrv.frpjump.ssh.TmuxNames
import ru.adsrv.frpjump.ssh.installFullBouncyCastle
import ru.adsrv.frpjump.store.AtomicTextFile
import ru.adsrv.frpjump.store.KeystoreSecretBox
import ru.adsrv.frpjump.tunnel.TunnelService
import ru.adsrv.frpjump.ui.web.clearWebData
import ru.adsrv.frpjump.ui.files.FilesSession
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

class FrpJumpApp : Application() {
    val api: FrpJumpApi by lazy { FrpJumpApi() }
    val profileInformation by lazy { ru.adsrv.frpjump.profiles.ProfileInformation(profiles.state, pendingUndo.hidden) }

    val sshTargets by lazy { ru.adsrv.frpjump.ssh.SshTargets(profiles.state, pendingUndo.hidden, sshEnv) }

    /** The owner's open terminal tabs for the assistant; the same hidden set as the Sessions list. */
    val assistantTerminalTabs by lazy {
        ru.adsrv.frpjump.assistant.AssistantTerminalTabs(
            // The owner's own tabs, then the assistant's sessions as its records say (attached or detached; never listed twice).
            tabs = { ssh.allNow().filter { it.openedBy == ru.adsrv.frpjump.ssh.SessionOpener.OWNER }.map { ru.adsrv.frpjump.assistant.SessionTab(it) } +
                assistantSessions.handles { id -> ssh.find(id)?.let { ru.adsrv.frpjump.assistant.SessionTab(it) } } },
            hidden = { pendingUndo.hidden.value },
            attach = { id -> assistantSessions.attach(id) },
            profileName = { id -> profiles.state.value.index.profiles.singleOrNull { it.id == id }?.name },
            connectionName = { profile, device, port -> savedSshConnection(profile, device, port)?.customName },
            connectionId = { profile, device, port -> savedSshConnection(profile, device, port)?.id },
            toolTimeoutMs = { settings.settings.value.assistantLimits.toolTimeoutSeconds * 1000L })
    }

    /**
     * The assistant's own terminal sessions: the same sessions as the owner's tabs, opened by the assistant and not shown as a tab until the owner opens one;
     * remembered in the session records so that a restart lists them again. A session that needs the owner (a password) is asked for through [OwnerAsk]s.
     */
    /**
     * The skills of the assistant: the built-in ones from the app's assets and the owner's own, one app-private file (no-backup storage, like the chat). The size of a
     * skill is the owner's limit at the time of reading it back, so a lowered limit never loses a saved skill.
     */
    val assistantSkills by lazy {
        ru.adsrv.frpjump.assistant.SkillRegistry(
            lazy { ru.adsrv.frpjump.assistant.BuiltInSkills.load({ assets.list(it)?.toList().orEmpty() }, { assets.open(it) }) },
            ru.adsrv.frpjump.assistant.SkillStore(AtomicTextFile(File(noBackupFilesDir, "assistant_skills.json"))) { settings.settings.value.assistantLimits.skillChars })
    }

    val assistantSessions by lazy {
        ru.adsrv.frpjump.assistant.AssistantSessions(ru.adsrv.frpjump.assistant.SshSessionHost(ssh), ru.adsrv.frpjump.assistant.SshSessionTargets(sshTargets), sshEnv.tabNames, appScope,
            profileName = { id -> profiles.state.value.index.profiles.singleOrNull { it.id == id }?.name },
            notify = { ask -> if (!ru.adsrv.frpjump.assistant.chatShown(assistantChatResumed.value, lock.locked.value)) ru.adsrv.frpjump.assistant.AssistantNotifier(this).postOwnerAsk(ask) },
            cancelNotify = { id -> ru.adsrv.frpjump.assistant.AssistantNotifier(this).cancelOwnerAsk(id) },
            notice = { which ->
                notices.showInApp(ru.adsrv.frpjump.locale.Texts.get(when (which) {
                    ru.adsrv.frpjump.assistant.SessionNotice.TUNNEL_GONE -> R.string.session_tunnel_gone
                    ru.adsrv.frpjump.assistant.SessionNotice.CLOSE_TUNNEL_OFF -> R.string.session_close_tunnel_off
                }), ru.adsrv.frpjump.notices.NoticeKind.WARN)
            },
            enabled = { settings.assistantAvailability.tools[ru.adsrv.frpjump.assistant.AssistantToolId.TERMINAL_OPEN] },
            toolTimeoutMs = { settings.settings.value.assistantLimits.toolTimeoutSeconds * 1000L }).also { it.refresh() }
    }

    /** The saved SSH connection of a tab's profile, device and port that is not hidden by an Undo; null when none matches. */
    private fun savedSshConnection(profile: String, device: String, port: Int): ru.adsrv.frpjump.connections.Connection? {
        val hiddenNow = pendingUndo.hidden.value
        return profiles.state.value.runtimes[profile]?.connections?.connections?.value
            ?.firstOrNull { it.kind == ru.adsrv.frpjump.ports.PortKind.SSH && it.device == device && it.port == port &&
                ru.adsrv.frpjump.undo.PendingUndo.connectionKey(profile, it.id) !in hiddenNow }
    }

    val assistantSshCommands by lazy { ru.adsrv.frpjump.assistant.AssistantSshCommands(sshTargets) }

    /** The owner's saved tunnels for the assistant's `start_tunnel` and `stop_tunnel`: the screens' own operations over the same tunnels and Undo queue. */
    val assistantTunnels by lazy {
        val up = ru.adsrv.frpjump.ops.tunnel.TunnelUp(waitMs = { settings.settings.value.assistantLimits.tunnelWaitSeconds * 1000L }, host = object : ru.adsrv.frpjump.ops.tunnel.TunnelUpHost {
            override fun tunnels(profile: String) = profiles.runtime(profile)?.tunnels?.let { manager ->
                object : ru.adsrv.frpjump.ops.tunnel.TunnelUpTunnels {
                    override val state get() = manager.state
                    override fun connectAsync(device: String, port: Int, kind: ru.adsrv.frpjump.ports.PortKind) { manager.connectAsync(device, port, kind) }
                    override suspend fun awaitDevice(device: String, port: Int, fresh: Boolean) = manager.awaitDevice(device, port, fresh)
                }
            }
            override fun revive(tunnel: ru.adsrv.frpjump.tunnel.TunnelRef) = disconnects.revive(tunnel)
            override suspend fun awaitDisconnected(tunnel: ru.adsrv.frpjump.tunnel.TunnelRef) = disconnects.awaitDisconnected(tunnel)
        })
        val down = ru.adsrv.frpjump.ops.tunnel.TunnelDown(disconnects, object : ru.adsrv.frpjump.ops.tunnel.TunnelDownHost {
            override fun profiles() = this@FrpJumpApp.profiles.state.value.runtimes.keys
            override fun up(profile: String) = this@FrpJumpApp.profiles.runtime(profile)?.tunnels?.state?.value?.tunnels.orEmpty().map { ru.adsrv.frpjump.tunnel.TunnelRef(profile, it.device, it.port) }
            // Only OneNow is asked of this host (the assistant's stop of one tunnel): nothing is restoring or told in bulk.
            override fun restoring() = emptySet<String>()
            override fun tellAsync(profile: String) = Unit
            override suspend fun tellAll() = Unit
        })
        val saved = ru.adsrv.frpjump.assistant.AppSavedTunnels(profiles.state, pendingUndo.hidden, disconnects, { sessions.now() },
            label = { c -> c.label { kind -> ru.adsrv.frpjump.ui.connections.kindWord(this, kind) } })
        ru.adsrv.frpjump.assistant.AssistantTunnels(saved, up, down)
    }

    lateinit var profiles: Profiles
        private set
    lateinit var sshKeys: SshKeyStore
        private set
    lateinit var sshPasswords: SshPasswords
        private set
    lateinit var ssh: SshSessions
        private set

    /** Broker logins kept with Remember, sealed with the SSH passwords' key. */
    lateinit var mqttLogins: MqttLogins
        private set

    /** The MQTT explorer's pins, per connection. */
    lateinit var mqttPins: PinStore
        private set

    /** The publish sheet's retain, QoS and recent payloads per topic, per connection. */
    lateinit var mqttPublished: PublishMemory
        private set

    /** The MQTT plugins' settings: app level and each connection's own on or off. */
    lateinit var mqttPlugins: PluginStore
        private set

    /** Applies the plugin settings to the MQTT sessions, with or without a screen. */
    lateinit var mqttPluginObserver: PluginObserver
        private set

    /** The MQTT explorer's sessions, one per connection. */
    lateinit var mqtt: MqttSessions
        private set

    /** The terminal and MQTT sessions together, as they are counted. */
    lateinit var sessions: OpenSessions
        private set

    /** Closing a terminal or MQTT session with Undo. */
    lateinit var closeSessions: SessionCloser
        private set

    /** What every SSH login needs: the terminal's sessions and the file screens share it. */
    lateinit var sshEnv: SshEnvironment
        private set

    /** File screens' logins, by the id in their route. */
    val fileTargets = FileTargets()

    /** File sessions, by the id in their route: kept past their screen while a terminal is open on the tunnel. Main thread. */
    val files = FilesKeeper { id -> FilesSession(this, id) }

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
    /** Optional assistant kernel; no provider or transport is started by constructing it. */
    lateinit var assistant: ru.adsrv.frpjump.agent.AgentModule
        private set
    lateinit var assistantProvider: ru.adsrv.frpjump.assistant.AssistantProviderStore
        private set
    /** Read on first use from the Send's IO path, never on Main; a missing asset surfaces as a setup problem. */
    val assistantPrompts: Lazy<ru.adsrv.frpjump.assistant.AssistantPrompts> = lazy { ru.adsrv.frpjump.assistant.AssistantPrompts.load(assets::open) }
    /** Whether an attempt runs or waits for an answer, for the bottom navigation's dot; exists from the start, the owner below does not. */
    val assistantActivity = kotlinx.coroutines.flow.MutableStateFlow(ru.adsrv.frpjump.assistant.ChatActivity.IDLE)
    /** How many background waits the assistant has now, for its notification; exists from the start, like [assistantActivity]. */
    val assistantWaitCount = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** How the last assistant attempt ended, for the notification; exists from the start, like [assistantActivity]. */
    val assistantEnd = kotlinx.coroutines.flow.MutableStateFlow(ru.adsrv.frpjump.assistant.ChatEnd.NONE)
    /** Tests only: an owner standing in for the app's (the notification's Stop reaches it through [stopAssistantAttempt]). */
    @androidx.annotation.VisibleForTesting
    internal var chatOwnerOverride: ru.adsrv.frpjump.assistant.AssistantChatOwner? = null
    /** True while a Stop is requested for the running assistant attempt (the notification goes at once). */
    val assistantStopping = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val chatOwner = lazy {
        ru.adsrv.frpjump.assistant.AssistantChatOwner(appScope, settings, assistantProvider, assistant, profileInformation, assistantSshCommands,
            assistantHistory, pendingUndo, assistantPrompts,
            prepare = { assistantProvider.provider(ru.adsrv.frpjump.assistant.AssistantProviderDraft(checkNotNull(it.settings), it.revision)) },
            realtime = android.os.SystemClock::elapsedRealtime, activityOut = assistantActivity, endOut = assistantEnd, stoppingOut = assistantStopping, removed = currentProfileRemoved,
            terminalTabs = assistantTerminalTabs, tunnels = assistantTunnels, sessions = assistantSessions, skills = assistantSkills, waitCountOut = assistantWaitCount)
    }
    /** True while the current profile's server has removed this phone; followed by the REMOVED screen and by the assistant's owner. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentProfileRemoved: kotlinx.coroutines.flow.Flow<Boolean> by lazy {
        profiles.state.map { it.current }.distinctUntilChanged().flatMapLatest { it?.session?.removed ?: kotlinx.coroutines.flow.flowOf(false) }
    }
    /** The app-lived owner of the chat conversation, built on the first chat opening (never at app start). */
    val assistantChatOwner: ru.adsrv.frpjump.assistant.AssistantChatOwner get() = chatOwnerOverride ?: chatOwner.value
    /** Stops a running assistant attempt without building the owner when no chat was ever opened (the server removed this phone's profile). */
    /** The notification's "Cancel all": every wait of the live conversation ends, without building the owner when none lives. */
    fun cancelAssistantWaits() { (chatOwnerOverride ?: if (chatOwner.isInitialized()) chatOwner.value else null)?.cancelWaits() }
    fun stopAssistantAttempt() { (chatOwnerOverride ?: if (chatOwner.isInitialized()) chatOwner.value else null)?.stop() }
    val assistantHistory by lazy {
        ru.adsrv.frpjump.assistant.AssistantChatHistory(AtomicTextFile(File(noBackupFilesDir, "assistant_chat.json")),
            KeystoreSecretBox("frpjump-assistant-chat-v1"), appScope)
    }
    lateinit var directKept: DirectKeptStore
        private set
    lateinit var network: NetworkWatcher
        private set
    lateinit var directChoices: DirectChoices
        private set
    lateinit var restoreRecord: RestoreRecord
        private set

    /** Settings › Version: newer releases on GitHub, their download and check. */
    lateinit var updater: Updater
        private set

    /** The assistant notification's tap asks to open the chat (set in-process by its trampoline); taken once by the navigation. */
    val openAssistantRequest = MutableStateFlow(false)

    /** The "needs your input" notification's Open asks for the assistant's session with this id as a tab (set in-process by its trampoline); taken once by the navigation. */
    val showSessionRequest = MutableStateFlow<Int?>(null)

    /** True while the chat route is resumed (the top screen of a resumed activity); the chat is shown when also not locked. */
    val assistantChatResumed = MutableStateFlow(false)

    /** A terminal selection quoted for the chat draft (Terminal › ⋮ › Ask the assistant); the chat takes it when it opens. */
    val assistantQuote = MutableStateFlow<String?>(null)

    val session = AppSession()

    /** Settings › Unlock with fingerprint: whether the screen is locked now. */
    val lock = AppLock(systemCredentialAction = {
        getSystemService(android.app.KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(null, null)?.action
    })

    /** Set by the activity while it is started: the current profile then polls every 2 s. */
    val visible = MutableStateFlow(false)

    /** Messages at the top of the app; toasts while no screen of it is started. */
    val notices by lazy {
        Notices(
            foreground = { visible.value },
            locked = { lock.locked.value },
            fallback = { text, long ->
                Handler(Looper.getMainLooper()).post { Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
            },
        )
    }

    /** Work that must finish even when the screen that started it goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Deletes, disconnects and closes waiting for their Undo to run out, with
     * the notice they share ("Deleted: 3 · Undo", "Disconnected: 2 · Undo"). Commits run in [appScope]; all of them are done
     * at once when the screens stop or the app locks, see [onCreate].
     */
    val pendingUndo by lazy {
        PendingUndo(
            timer = { ms, block ->
                val job = appScope.launch {
                    delay(ms)
                    block()
                }
                val cancel: () -> Unit = { job.cancel() }
                cancel
            },
            run = { commit -> appScope.launch { commit() } },
            announce = { counts, undo ->
                if (counts == null) {
                    notices.dismissKey(UNDO_NOTICE)
                } else {
                    val text = undoText(counts) { kind, n ->
                        Texts.get(
                            when (kind) {
                                UndoKind.DELETED -> R.string.undo_deleted
                                UndoKind.DISCONNECTED -> R.string.undo_disconnected
                                UndoKind.CLOSED -> R.string.undo_closed
                            },
                            n,
                        )
                    }
                    notices.show(text, NoticeKind.DONE, key = UNDO_NOTICE, action = NoticeAction(Texts.get(R.string.undo), undo), first = true)
                }
            },
        )
    }

    /** Disconnecting tunnels with Undo: dark at once, stopped when the notice goes. Main thread. */
    val disconnects by lazy {
        TunnelDisconnects(
            pendingUndo,
            isUp = { t -> profiles.runtime(t.profile)?.tunnels?.state?.value?.tunnels?.any { it.device == t.device && it.port == t.port } == true },
        ) { t ->
            // In the tunnel manager's own scope, as a disconnect always ran; a failure shows on its row.
            profiles.runtime(t.profile)?.tunnels?.disconnectAsync(t.device, t.port)?.join()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // First, before anything can open a connection: the swap removes "BC" for a moment, and a TLS
        // handshake in that moment fails ("KeyStoreException: BKS not found"), as the first /version did.
        installFullBouncyCastle()
        Texts.init(this)
        settings = SettingsStore(AtomicTextFile(File(filesDir, "settings.json")))
        assistantProvider = ru.adsrv.frpjump.assistant.AssistantProviderStore(
            AtomicTextFile(File(filesDir, "assistant_provider.json")), KeystoreSecretBox("frpjump-assistant-provider-v1"), settings.assistantChat)
        assistant = ru.adsrv.frpjump.assistant.bindAssistantLifecycle(settings, appScope)
        restoreAppLanguage(settings.settings.value.language)
        applyTheme(settings.settings.value.theme)
        directKept = DirectKeptStore(AtomicTextFile(File(filesDir, "direct_kept.json")))
        network = NetworkWatcher(this)
        restoreRecord = RestoreRecord(AtomicTextFile(File(filesDir, "restore.json")))
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
            reservations = {
                val all = profiles.state.value.runtimes
                PortReservations(
                    profiles = all.keys.toSet(),
                    pinned = all.values.flatMap { runtime ->
                        tunnelPins(runtime.connections.connections.value).map { (key, port) ->
                            profilePortPrefix(runtime.id) + key to port
                        }
                    }.toMap(),
                    held = all.values.flatMap { it.tunnels.heldReservations.toList() }.toMap(),
                )
            },
        )
        val store = ProfileStore(filesDir, boxFor = ::KeystoreSecretBox, storageFor = ::AtomicTextFile)
        // Tunnels whose disconnect waits for Undo are counted as down, but run on.
        val dark = pendingUndo.hidden.map(PendingUndo::darkTunnels).stateIn(appScope, SharingStarted.Eagerly, emptySet())
        profiles = Profiles(store, restoreRecord.profiles, dark) { profile, profileStore, currentId ->
            ProfileRuntime(profile.id, profileStore.credentials(profile), profileStore.dir(profile.id), currentId, shared)
        }
        directChoices = DirectChoices(profiles, directKept, network.id)
        // Keystore and disk (and the v1 upgrade): off the main thread. The UI waits for it.
        appScope.launch(Dispatchers.IO) {
            snippets.load()
            // Before any profile applies its tunnels.
            directKept.load()
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
        // Its own Keystore key, shared by all profiles: re-enrolling must not lose the SSH key.
        val sshBox = KeystoreSecretBox(SSH_KEY_ALIAS)
        sshKeys = SshKeyStore(AtomicTextFile(File(filesDir, "ssh_keys.json")), sshBox)
        sshPasswords = SshPasswords(AtomicTextFile(File(filesDir, "ssh_passwords.json")), sshBox)
        webSites = WebSiteMemory(AtomicTextFile(File(filesDir, "web_site.json")))
        sshEnv = SshEnvironment(
            sshKeys, sshPasswords, keyComment = ::sshKeyComment,
            tmuxNames = TmuxNames(AtomicTextFile(File(filesDir, "tmux_names.json"))),
            tabNames = SessionRecordStore(AtomicTextFile(File(filesDir, "tab_names.json"))),
            historyRows = { settings.settings.value.terminalHistoryRows },
            reconnectGiveUpMs = { settings.settings.value.assistantLimits.reconnectMinutes * 60_000L },
            visible = visible,
        )
        listings = ListingCache({ profile, key -> AtomicTextFile(File(PhoneFiles.listingsDir(this, profile), "$key.json")) })
        // Files handed to other apps a day ago or more (Open with, Share), and viewer copies
        // left behind when the process went: older than a few minutes, no viewer is reading them.
        appScope.launch(Dispatchers.IO) {
            // Older versions' "Direct didn't work" channel (and with it what is still shown) and offers file.
            DirectChannel.retireLegacy(
                { id -> getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(id) },
                AtomicTextFile(File(filesDir, "direct_offered.json")),
            )
            PhoneFiles.pruneOpenCache(this@FrpJumpApp, OPEN_CACHE_AGE_MS)
            PhoneFiles.pruneViewCache(this@FrpJumpApp, VIEW_CACHE_AGE_MS)
        }
        // Deletes waiting for Undo are done when the screens stop: the process may go
        // any time after (swiped away from recents, killed in the background).
        // A message posted just before the app went away is a toast, not lost; none waits over the lock screen.
        appScope.launch {
            visible.collect {
                if (!it) {
                    pendingUndo.commitAll()
                    notices.toBackground()
                }
            }
        }
        // The assistant's notification while the chat is off screen: it is shown when its route is resumed and the app is not locked.
        ru.adsrv.frpjump.assistant.AssistantNotifier(this).start(
            appScope, assistantActivity, assistantEnd, combine(assistantChatResumed, lock.locked) { resumed, locked -> ru.adsrv.frpjump.assistant.chatShown(resumed, locked) }.distinctUntilChanged(),
            settings.settings.map { it.assistantEnabled }.distinctUntilChanged(), assistantStopping, assistantWaitCount)
        // Undo's time counts from when its notice is on screen (a host draws it), not from the tap.
        appScope.launch {
            combine(notices.queue, notices.hosts) { queue, hosts -> queue.shown?.takeIf { it.key == UNDO_NOTICE && hosts.isNotEmpty() }?.id }
                .distinctUntilChanged()
                .collect { if (it != null) pendingUndo.noticeShown() }
        }
        appScope.launch {
            lock.locked.collect {
                if (it) {
                    pendingUndo.commitAll()
                    notices.clear()
                }
            }
        }
        ssh = SshSessions(
            context = this,
            notices = notices,
            env = sshEnv,
            tunnelsUp = profiles.upTunnels,
            scope = appScope,
            network = network.id,
            hidden = pendingUndo.hidden,
        )
        mqttLogins = MqttLogins(AtomicTextFile(File(filesDir, "mqtt_logins.json")), sshBox)
        mqttPins = PinStore(AtomicTextFile(File(filesDir, "mqtt_pins.json")))
        mqttPublished = PublishMemory(AtomicTextFile(File(filesDir, "mqtt_published.json")))
        mqttPlugins = PluginStore(
            file = AtomicTextFile(File(filesDir, "mqtt_plugins_v2.json")),
            legacy = AtomicTextFile(File(filesDir, "mqtt_plugins.json")),
            corruptCopy = AtomicTextFile(File(filesDir, "mqtt_plugins_v2.corrupt.json")),
            legacyCorruptCopy = AtomicTextFile(File(filesDir, "mqtt_plugins.corrupt.json")),
        )
        mqtt = MqttSessions(
            env = MqttEnvironment(mqttLogins, mqttPins, HiveMqConnector(), ExecutorScheduler(),
                ingressLimits = { settings.settings.value.mqttIngress },
                cacheLimits = { settings.settings.value.mqttCache }),
            tunnelsUp = profiles.upTunnels,
            scope = appScope,
        )
        mqttPluginObserver = PluginObserver(mqttPlugins, mqtt.sessions, appScope)
        sessions = OpenSessions(ssh.through, ssh::throughNow, mqtt.through, mqtt::throughNow, pendingUndo.hidden)
        closeSessions = SessionCloser(pendingUndo, closeTerminal = { id ->
            // The assistant's own sessions (also the remembered ones, which are attached to end their tmux session) go through the assistant's close.
            if (assistantSessions.isAssistant(id)) appScope.launch { assistantSessions.closeByOwner(id) } else ssh.close(id, endTmux = true)
        }, closeMqtt = mqtt::close)
        // A kept file session closes with the last terminal session on its tunnel.
        appScope.launch { ssh.through.collect(files::onTerminals) }
    }

    /** The comment on the app's SSH public key: names the phone in `authorized_keys`. Keystore: off the main thread. */
    fun sshKeyComment(): String = "frp-jump@" + (profiles.current?.store?.load()?.deviceName ?: "android")

    /**
     * Forget profile [id] on this phone: close its SSH and MQTT sessions,
     * stop its frpc, wipe its credentials, key, lists, ports, remembered SSH
     * passwords, broker logins, MQTT pins, publish memory and plugin settings ([Profiles.forget]). [keepData] keeps the profile, empty,
     * with its custom ports, logins, host keys and passwords (enrolling it
     * again after its server removed it). Otherwise the web views' data goes
     * too when it is this profile's, and with the last profile also the
     * shared SSH key and passwords, as v1's "Forget this phone" did. The
     * passwords and the key go before the index drops the profile, so an
     * interruption leaves it listed rather than them behind.
     */
    suspend fun forgetProfile(id: String, keepData: Boolean) {
        // Not left to be done on a profile already gone.
        pendingUndo.commitAll()
        ssh.closeProfile(id)
        mqtt.closeProfile(id)
        profiles.forget(id, keepData) { last ->
            sshPasswords.forgetProfile(id)
            mqtt.changeStored {
                mqttLogins.forgetProfile(id)
                mqttPins.forgetProfile(id)
                mqttPublished.forgetProfile(id)
                mqttPlugins.forgetProfile(id)
                if (last) {
                    mqttLogins.wipe()
                    mqttPins.wipe()
                    mqttPublished.wipe()
                    mqttPlugins.wipe()
                }
            }
            sshEnv.refusals.forgetProfile(id)
            directKept.removeProfile(id)
            if (last) {
                sshKeys.wipe()
                sshPasswords.wipe()
                KeystoreSecretBox(SSH_KEY_ALIAS).destroy()
            }
        }
        // Its files handed to other apps go in any case; the folders seen on its devices with its data.
        withContext(Dispatchers.IO) {
            PhoneFiles.openCacheDir(this@FrpJumpApp, id).deleteRecursively()
            PhoneFiles.viewCacheDir(this@FrpJumpApp, id).deleteRecursively()
        }
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
        const val UNDO_NOTICE = "undo"
        const val OPEN_CACHE_AGE_MS = 24 * 60 * 60 * 1000L

        /** Viewer copies older than this go at start-up. */
        const val VIEW_CACHE_AGE_MS = 5 * 60 * 1000L
        val LOAD_RETRIES_MS = listOf(10_000L, 30_000L, 60_000L, 60_000L, 60_000L)
    }
}
