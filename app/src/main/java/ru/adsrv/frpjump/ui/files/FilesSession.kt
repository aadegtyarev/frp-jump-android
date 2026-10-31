package ru.adsrv.frpjump.ui.files

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.userauth.UserAuthException
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.files.DownloadSink
import ru.adsrv.frpjump.files.FilesKeeper
import ru.adsrv.frpjump.files.CommandsRefused
import ru.adsrv.frpjump.files.FilesTarget
import ru.adsrv.frpjump.files.Found
import ru.adsrv.frpjump.files.PhoneFiles
import ru.adsrv.frpjump.files.PhoneSideException
import ru.adsrv.frpjump.files.RemoteEntry
import ru.adsrv.frpjump.files.RowJobs
import ru.adsrv.frpjump.files.SftpBrowser
import ru.adsrv.frpjump.files.TransferJobs
import ru.adsrv.frpjump.files.TransferSlots
import ru.adsrv.frpjump.files.downloadInto
import ru.adsrv.frpjump.files.fileProblem
import ru.adsrv.frpjump.files.isDeviceRefusal
import ru.adsrv.frpjump.files.isFolderAccess
import ru.adsrv.frpjump.files.parentOf
import ru.adsrv.frpjump.files.readEntry
import ru.adsrv.frpjump.files.readFolder
import ru.adsrv.frpjump.files.resolvePath
import ru.adsrv.frpjump.files.SearchEnd
import ru.adsrv.frpjump.files.runOneByOne
import ru.adsrv.frpjump.files.searchNames
import ru.adsrv.frpjump.files.targetFolder
import ru.adsrv.frpjump.files.uploadInto
import ru.adsrv.frpjump.files.visibleListing
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.ssh.HostKeyCheck
import ru.adsrv.frpjump.ssh.HostKeyRejected
import ru.adsrv.frpjump.ssh.offersSavedPassword
import ru.adsrv.frpjump.ssh.PinnedHostKey
import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.SshConnector
import ru.adsrv.frpjump.ssh.SshLogin
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.tunnel.TunnelRef
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** What the file screen can say about its connection. */
sealed interface FilesStatus {
    /** Nothing listed yet: connecting and reading the first folder. */
    data object Starting : FilesStatus

    data object Ready : FilesStatus

    /** The tunnel is coming up; the folder shows as last seen. */
    data object TunnelConnecting : FilesStatus

    /**
     * No tunnel, or SSH could not connect ([reason]): the folder as last
     * seen at [cachedAt] (null when it never was; [truncated] when only
     * the first entries were kept); nothing can be done to it.
     */
    data class Offline(val reason: String, val cachedAt: Long?, val truncated: Boolean = false) : FilesStatus

    /** Connected, but the folder could not be read; or the login was refused ([loginRefused]: "Change login"). */
    data class Failed(val message: String, val loginRefused: Boolean = false) : FilesStatus

    data class HostKeyUnknown(val presented: PinnedHostKey) : FilesStatus

    data class HostKeyChanged(val pinned: PinnedHostKey, val presented: PinnedHostKey) : FilesStatus

    /** The screen's login is gone (the app was restarted under it). */
    data object Gone : FilesStatus
}

/** What a download is for: the download folder, or the cache for another app. */
enum class TransferKind { DOWNLOAD, OPEN, SHARE }

/** One file going to or from the phone; [total] 0 when the size is unknown. [failure] keeps a failed one on screen with Retry. */
data class Transfer(
    val name: String,
    val done: Long,
    val total: Long,
    val kind: TransferKind = TransferKind.DOWNLOAD,
    val failure: String? = null,
    /** Queued behind the transfers going ([TransferSlots]); Cancel works meanwhile. */
    val waiting: Boolean = false,
) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * The search in subfolders of [root] for [query] (run [id], hidden names
 * when [hidden]): its hits so far, and once it is over ([running] false)
 * how it ended ([end] null: stopped by the owner), with [skipped] folders
 * it could not read and a failure's [message].
 */
data class DeepSearch(
    val id: Long,
    val query: String,
    val root: String,
    val hidden: Boolean,
    val found: List<Found> = emptyList(),
    val running: Boolean = true,
    val end: SearchEnd? = null,
    val skipped: Int = 0,
    val message: String? = null,
)

/**
 * [hits] of run [id] added, those already listed left out; null when this
 * is not that run going (stopped, or a newer run of the same text): a late
 * batch of an old run must not reach the list.
 */
fun DeepSearch.withHits(id: Long, hits: List<Found>): DeepSearch? {
    if (this.id != id || !running) return null
    val known = found.mapTo(HashSet()) { it.path }
    val fresh = hits.filter { known.add(it.path) }
    return if (fresh.isEmpty()) this else copy(found = found + fresh)
}

/** A file picked for upload, waiting, going, or failed ([transfer]'s failure) with Retry. */
data class Upload(val id: Long, val uri: Uri, val dir: String, val transfer: Transfer)

data class FilesUiState(
    /** The folder shown, a physical path; null until the home folder is known. */
    val path: String? = null,
    /** Its entries as the screen lists them (sorted, hidden ones filtered). */
    val entries: List<RemoteEntry> = emptyList(),
    /** Whether there are entries the hidden filter takes away. */
    val hasHidden: Boolean = false,
    val showHidden: Boolean = false,
    val user: String = "",
    val status: FilesStatus = FilesStatus.Starting,
    /** A folder is being opened (a thin bar under the path). */
    val busy: Boolean = false,
    /** Pull to refresh in progress. */
    val refreshing: Boolean = false,
    /** Downloads, Open with and Share in progress or failed, by the device path. */
    val downloads: Map<String, Transfer> = emptyMap(),
    /** Uploads into this screen's folders, in order. */
    val uploads: List<Upload> = emptyList(),
    /** The chosen download folder's name, when one is chosen. */
    val downloadFolder: String? = null,
    /** The entries picked by a long press and taps; empty while not selecting. */
    val selection: FileSelection = FileSelection(),
    /** The search in subfolders for the search field's text; null when none was started. */
    val deepSearch: DeepSearch? = null,
    /** A hit in a subfolder whose card is open over the hits. */
    val foundEntry: RemoteEntry? = null,
) {
    val online: Boolean get() = status == FilesStatus.Ready

    private val transfers: List<Transfer> get() = downloads.values + uploads.map { it.transfer }

    /** Transfers going or waiting: what leaving the screen would cancel. */
    val running: Int get() = transfers.count { it.failure == null }

    /** Failed transfers, on their rows with Retry and Dismiss. */
    val failed: Int get() = transfers.count { it.failure != null }
}

/** What the "Leave and cancel?" question does as the transfers go on. */
enum class LeaveAnswer { ASK, LEAVE, STAY }

/**
 * While the question is up: it stays while transfers go ([running]); once
 * none does, the screen leaves as asked when they all finished well, and
 * stays (the question closed) when more failed than when it was asked
 * ([failedAtAsk], [failedNow]), so the failures are seen.
 */
fun leaveAnswer(running: Int, failedAtAsk: Int, failedNow: Int): LeaveAnswer = when {
    running > 0 -> LeaveAnswer.ASK
    failedNow > failedAtAsk -> LeaveAnswer.STAY
    else -> LeaveAnswer.LEAVE
}

/** What the screen does once, outside its state. */
sealed interface FilesEvent {
    /** A message at the top of the app ([ru.adsrv.frpjump.notices.Notices]). */
    data class Notice(val text: String, val kind: NoticeKind) : FilesEvent

    /** Hand [file] (the device's [path]) to another app: view it ([share] false) or share it. */
    data class Handover(val path: String, val file: File, val mime: String, val name: String, val share: Boolean) : FilesEvent

    /** Share several files at once, each with its name. */
    data class ShareMany(val files: List<Pair<File, String>>) : FilesEvent

    /** No usable download folder: ask for one, then download [entries]. */
    data class PickFolder(val entries: List<RemoteEntry>) : FilesEvent

    /** Open the card of [path], a hit of the search in subfolders ([FilesUiState.foundEntry]). */
    data class ShowCard(val path: String) : FilesEvent
}

/**
 * The files of the device behind one SSH tunnel, over an SFTP connection
 * of their own ([SftpBrowser]); follows the tunnel: down, the last listing
 * of each folder shows from [ru.adsrv.frpjump.files.ListingCache].
 * App-scoped: its screen attaches through [FilesViewModel], and
 * [FilesKeeper] keeps it past the screen while a terminal is open on the
 * tunnel.
 */
class FilesSession(private val app: FrpJumpApp, private val targetId: Int) : FilesKeeper.Session {
    var target: FilesTarget? = app.fileTargets.get(targetId)
        private set
    private val runtime = target?.let { app.profiles.runtime(it.profile) }

    /** Lives until [close]; leaving the screen while kept does not end it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val tunnel: TunnelRef? get() = target?.takeIf { runtime != null }?.let { TunnelRef(it.profile, it.device, it.port) }
    override val user: String get() = target?.user.orEmpty()

    /** Where each folder was scrolled to, by its path: the screen returns there, also after the session was kept. Main thread. */
    val positions = mutableMapOf<String, Pair<Int, Int>>()

    private val _state = MutableStateFlow(FilesUiState(showHidden = app.settings.settings.value.filesShowHidden, user = target?.user.orEmpty()))
    val state: StateFlow<FilesUiState> = _state

    private val _events = Channel<FilesEvent>(Channel.BUFFERED)
    val events: Flow<FilesEvent> = _events.receiveAsFlow()

    /** Everything in the folder shown, before the hidden filter. */
    private var all: List<RemoteEntry> = emptyList()
    private var home: String? = null

    /** The tunnel's local port while it is up. */
    @Volatile
    private var localPort: Int? = null
    private var tunnelConnecting = false
    private var tunnelError: String? = null

    private val connectLock = Mutex()
    private var browser: SftpBrowser? = null
    private var connector: SshConnector? = null
    private var loadJob: Job? = null

    /** Downloads, Open with and Share run two at a time; the rest wait on their rows. */
    private val slots = TransferSlots()

    /** Transfers in flight: the connection closes only after they have cleaned up. */
    private val transfers = TransferJobs()

    /** One upload at a time from this screen. */
    private val uploadLock = Mutex()
    private val uploadIds = AtomicLong(1)

    /** The job of each download, Open with or Share going, by the device path: Cancel stops just that one. */
    private val downloadJobs = RowJobs<String>()

    /** The job of the upload being written now, by its id. */
    private val uploadJobs = RowJobs<Long>()

    /** The search in subfolders going, if any. */
    private var searchJob: Job? = null
    private val searchIds = AtomicLong(1)

    init {
        val t = target
        if (t == null || runtime == null) {
            _state.update { it.copy(status = FilesStatus.Gone) }
        } else {
            scope.launch {
                app.settings.settings.map { it.filesShowHidden to it.filesDownloadFolder }.distinctUntilChanged().collect { (hidden, folder) ->
                    val label = folder?.let { uri -> withContext(Dispatchers.IO) { runCatching { PhoneFiles.folderName(app, Uri.parse(uri)) }.getOrNull() } }
                    _state.update { it.copy(showHidden = hidden, downloadFolder = label) }
                    show(_state.value.path, all)
                    // Hidden files switched during a search: its hits are for the other setting.
                    _state.value.deepSearch?.takeIf { it.hidden != hidden }?.let { search ->
                        if (_state.value.online) searchSubfolders(search.query) else clearSearch()
                    }
                }
            }
            val key = localPortKey(t.device, t.port)
            scope.launch {
                runtime.tunnels.state
                    .map { s -> TunnelView(s.tunnels.firstOrNull { it.key == key }?.localPort, s.pending[key] == PendingOp.CONNECTING, s.errors[key]) }
                    .distinctUntilChanged()
                    .collect(::onTunnel)
            }
        }
    }

    /** What the screen follows of its tunnel: up at a local port, coming up, or why it failed. */
    private data class TunnelView(val localPort: Int?, val connecting: Boolean, val error: String?)

    private fun onTunnel(tunnel: TunnelView) {
        val before = localPort
        val port = tunnel.localPort
        localPort = port
        tunnelConnecting = tunnel.connecting
        tunnelError = tunnel.error
        if (port == null) {
            loadJob?.cancel()
            dropBrowser()
            showCached(_state.value.path, tunnelDown())
        } else if (port != before) {
            load(_state.value.path, refresh = false)
        }
    }

    /** Opens folder [path] (null: the home folder). Offline, it shows the listing last seen there. */
    fun open(path: String?) {
        val status = _state.value.status
        if (status is FilesStatus.Offline || status == FilesStatus.TunnelConnecting) {
            showCached(path, (status as? FilesStatus.Offline)?.reason ?: tunnelDown())
        } else {
            load(path, refresh = false)
        }
    }

    /** A tap on a folder or a link to one: where it really is, so links inside resolve against it. */
    fun openFolder(entry: RemoteEntry) = open(entry.folderPath)

    /** A path typed in the path bar: absolute, relative to the folder shown, `~` for home. */
    fun openTyped(text: String) = open(resolvePath(_state.value.path ?: home ?: "/", text, home))

    /** Back: the folder above, false at `/` (the screen then closes). */
    fun up(): Boolean {
        val path = _state.value.path ?: return false
        if (path == "/") return false
        open(parentOf(path))
        return true
    }

    /** Pull to refresh, and Retry: reconnects when needed; with the tunnel down, brings it up. */
    fun refresh() {
        val t = target ?: return
        if (localPort == null) {
            if (!tunnelConnecting) runtime?.tunnels?.connectAsync(t.device, t.port, PortKind.SSH)
            _state.update { it.copy(refreshing = false) }
            return
        }
        load(_state.value.path, refresh = true)
    }

    /** "Go to the target" of a symlink: the target folder, made physical when it is read. */
    fun goToTarget(entry: RemoteEntry) {
        targetFolder(entry)?.let(::open)
    }

    fun setShowHidden(show: Boolean) = scope.launch(Dispatchers.IO) {
        runCatching { app.settings.update { it.copy(filesShowHidden = show) } }
    }

    fun trustHostKey() {
        val s = _state.value.status as? FilesStatus.HostKeyUnknown ?: return
        val t = target ?: return
        _state.update { it.copy(status = FilesStatus.Starting) }
        scope.launch {
            withContext(Dispatchers.IO) { runtime?.hostKeys?.pin(t.device, t.port, s.presented) }
            load(_state.value.path, refresh = false)
        }
    }

    fun forgetOldHostKey() {
        if (_state.value.status !is FilesStatus.HostKeyChanged) return
        val t = target ?: return
        _state.update { it.copy(status = FilesStatus.Starting) }
        scope.launch {
            withContext(Dispatchers.IO) { runtime?.hostKeys?.forget(t.device, t.port) }
            load(_state.value.path, refresh = false)
        }
    }

    /** The login dialog's start for "Change login": this screen's user, the method last used on the port. */
    fun login(): SshLogin {
        val t = target ?: return SshLogin()
        return (runtime?.sshLogins?.get(t.device, t.port) ?: SshLogin()).copy(user = t.user)
    }

    fun hasRememberedPassword(user: String): Boolean {
        val t = target ?: return false
        val refusal = app.sshEnv.refusals.get(t.profile, t.device, t.port, user.trim())
        return offersSavedPassword(refusal, user.trim(), user) { app.sshPasswords.has(t.profile, t.device, t.port, user.trim()) }
    }

    /** "Change login" after a refused one: log in again with what the dialog gave. */
    fun changeLogin(user: String, method: SshAuthMethod, password: String, remember: Boolean) {
        val t = target ?: return
        // A new password typed: the refusal of the old one says nothing about it.
        if (method == SshAuthMethod.PASSWORD && password.isNotEmpty()) app.sshEnv.refusals.clear(t.profile, t.device, t.port, user.trim())
        val login = SshLogin(user.trim().ifEmpty { SshLogin.DEFAULT_USER }, method)
        scope.launch(Dispatchers.IO) { runtime?.sshLogins?.remember(t.device, t.port, login) }
        val auth = when (method) {
            SshAuthMethod.KEY -> SshAuth.Key
            SshAuthMethod.PASSWORD -> SshAuth.Password(password.ifEmpty { null }, remember)
        }
        val next = t.copy(user = login.user, auth = auth)
        target = next
        app.fileTargets.replace(targetId, next)
        dropBrowser()
        _state.update { it.copy(user = next.user, status = FilesStatus.Starting) }
        load(if (next.user == t.user) _state.value.path else null, refresh = false)
    }

    /** The download folder if the app may still write there. */
    fun downloadFolder(): Uri? = app.settings.settings.value.filesDownloadFolder?.let(Uri::parse)?.takeIf { PhoneFiles.canWrite(app, it) }

    /** The folder picker's answer: kept for the next downloads; [then] is downloaded into it. */
    fun setDownloadFolder(tree: Uri, then: List<RemoteEntry>) {
        scope.launch {
            val kept = withContext(Dispatchers.IO) {
                PhoneFiles.keep(app, tree) && runCatching { app.settings.update { it.copy(filesDownloadFolder = tree.toString()) } }.isSuccess
            }
            if (!kept) return@launch toast(Texts.get(R.string.files_folder_not_kept), NoticeKind.WARN)
            // The selection asked for the folder: it ends now that its files go.
            if (then.isNotEmpty()) clearSelection()
            downloadAll(then)
        }
    }

    /** Download into the chosen folder, "Keep both" on a name clash. */
    fun download(entry: RemoteEntry) {
        download(entry, onDone = null)
    }

    /**
     * [onDone] gets the name it was saved as, in place of the "downloaded"
     * toast; [onFolderLost] the entry when the folder's access was lost, in
     * place of asking for it. Null when nothing started.
     */
    private fun download(entry: RemoteEntry, onDone: ((String) -> Unit)?, onFolderLost: ((RemoteEntry) -> Unit)? = null): Job? {
        val tree = downloadFolder() ?: run {
            _events.trySend(FilesEvent.PickFolder(listOf(entry)))
            return null
        }
        return transfer(entry, TransferKind.DOWNLOAD, onFolderLost) { b, progress ->
            val name = withContext(Dispatchers.IO) {
                val (doc, name) = try {
                    PhoneFiles.createInTree(app, tree, entry.name)
                } catch (e: IOException) {
                    throw PhoneSideException(e)
                }
                val sink = object : DownloadSink {
                    override fun open(): OutputStream = app.contentResolver.openOutputStream(doc) ?: throw IOException("cannot write $name")

                    override fun discard() = PhoneFiles.delete(app, doc)
                }
                downloadInto(b.fs, entry.path, sink, progress)
                name
            }
            // Cancelled just as it finished: no "downloaded" for a row the owner cancelled.
            currentCoroutineContext().ensureActive()
            if (onDone != null) onDone(name) else toast(Texts.get(R.string.files_downloaded, name), NoticeKind.DONE)
        }
    }

    /**
     * Of [entries], those that can start now: none while offline, and not
     * those being transferred already; one toast says what was left out.
     */
    private fun startable(entries: List<RemoteEntry>): List<RemoteEntry> {
        if (!_state.value.online || localPort == null) return emptyList<RemoteEntry>().also { toast(Texts.get(R.string.files_needs_connection), NoticeKind.WARN) }
        val busy = entries.filter { e -> _state.value.downloads[e.path]?.let { it.failure == null } == true }
        when (busy.size) {
            0 -> Unit
            1 -> toast(Texts.get(R.string.files_busy, busy.single().name))
            else -> toast(Texts.plural(R.plurals.files_busy_many, busy.size, busy.size))
        }
        return entries - busy.toSet()
    }

    /**
     * Several files, each on its row, two at a time; one toast for those
     * that arrived, and one folder picker for those that lost the folder.
     */
    private fun downloadAll(entries: List<RemoteEntry>) {
        if (entries.isEmpty()) return
        if (entries.size == 1) return download(entries.single())
        if (downloadFolder() == null) {
            _events.trySend(FilesEvent.PickFolder(entries))
            return
        }
        val names = Collections.synchronizedList(mutableListOf<String>())
        val lost = Collections.synchronizedList(mutableListOf<RemoteEntry>())
        val jobs = startable(entries).mapNotNull { e -> download(e, onDone = { names += it }, onFolderLost = { lost += it }) }
        if (jobs.isEmpty()) return
        scope.launch {
            jobs.joinAll()
            when (names.size) {
                0 -> Unit
                1 -> toast(Texts.get(R.string.files_downloaded, names.single()), NoticeKind.DONE)
                else -> toast(Texts.plural(R.plurals.files_downloaded_many, names.size, names.size), NoticeKind.DONE)
            }
            // Asked once for the batch; each of them is downloaded again into the folder picked.
            if (lost.isNotEmpty()) _events.send(FilesEvent.PickFolder(entries.filter { it in lost }))
        }
    }

    /** Long press on a row, and taps while selecting: [entry] in or out of the selection. */
    fun toggleSelected(entry: RemoteEntry) {
        _state.update { it.copy(selection = it.selection.toggle(it.path, entry)) }
    }

    fun selectAll() {
        _state.update { it.copy(selection = it.selection.selectAll(it.path, it.entries)) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = FileSelection()) }
    }

    /** The selection's files; says when folders or others were left out. */
    private fun selectedFiles(): List<RemoteEntry> {
        val now = _state.value
        val (files, left) = transferablePart(now.selection.picked(now.entries))
        if (files.isNotEmpty() && left > 0) toast(Texts.plural(R.plurals.files_selection_skipped, left, left))
        return files
    }

    /**
     * "Download" in the selection bar: its files into the download folder.
     * With none chosen yet the selection stays until one is (a cancelled
     * picker loses nothing).
     */
    fun downloadSelected() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        if (downloadFolder() == null) {
            _events.trySend(FilesEvent.PickFolder(files))
            return
        }
        clearSelection()
        downloadAll(files)
    }

    /** "Share" in the selection bar: its files to the cache, two at a time, then one share of them all. */
    fun shareSelected() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        clearSelection()
        if (files.size == 1) return share(files.single())
        val got = Collections.synchronizedMap(LinkedHashMap<String, Pair<File, String>>())
        val started = startable(files)
        val jobs = started.mapNotNull { e -> transfer(e, TransferKind.SHARE) { b, progress -> got[e.path] = toCache(b, e, progress) to e.name } }
        if (jobs.isEmpty()) return
        scope.launch {
            jobs.joinAll()
            // What arrived, in the list's order; a failed one stays on its row with Retry.
            val ready = started.mapNotNull { got[it.path] }
            if (ready.isEmpty()) return@launch
            if (ready.size < started.size) toast(Texts.get(R.string.files_shared_some, ready.size, started.size))
            _events.send(FilesEvent.ShareMany(ready))
        }
    }

    /** Tap on a file: to the cache, then "Open with". */
    fun openWith(entry: RemoteEntry) {
        transfer(entry, TransferKind.OPEN) { b, progress -> handover(b, entry, progress, share = false) }
    }

    fun share(entry: RemoteEntry) {
        transfer(entry, TransferKind.SHARE) { b, progress -> handover(b, entry, progress, share = true) }
    }

    /** Retry of a failed download, Open with or Share. */
    fun retry(entry: RemoteEntry) {
        val failed = _state.value.downloads[entry.path]?.takeIf { it.failure != null } ?: return
        _state.update { it.copy(downloads = it.downloads - entry.path) }
        when (failed.kind) {
            TransferKind.DOWNLOAD -> download(entry)
            TransferKind.OPEN -> openWith(entry)
            TransferKind.SHARE -> share(entry)
        }
    }

    fun dismissFailure(path: String) {
        _state.update { s -> s.copy(downloads = s.downloads.filterNot { it.key == path && it.value.failure != null }) }
    }

    private suspend fun handover(b: SftpBrowser, entry: RemoteEntry, progress: (Long, Long) -> Unit, share: Boolean) {
        val file = toCache(b, entry, progress)
        // Cancelled just as it finished: the file is not opened or shared.
        currentCoroutineContext().ensureActive()
        _events.send(FilesEvent.Handover(entry.path, file, PhoneFiles.mimeType(entry.name), entry.name, share))
    }

    /** [entry] downloaded into the app's cache, for another app. */
    private suspend fun toCache(b: SftpBrowser, entry: RemoteEntry, progress: (Long, Long) -> Unit): File {
        val t = checkNotNull(target)
        val file = withContext(Dispatchers.IO) {
            val file = try {
                PhoneFiles.openCacheFile(app, t.profile, entry.name)
            } catch (e: IOException) {
                throw PhoneSideException(e)
            }
            val sink = object : DownloadSink {
                override fun open(): OutputStream = FileOutputStream(file)

                override fun discard() {
                    file.parentFile?.deleteRecursively()
                }
            }
            downloadInto(b.fs, entry.path, sink, progress)
            file
        }
        currentCoroutineContext().ensureActive()
        return file
    }

    /**
     * One transfer of [entry] at a time, shown on its row; a failure stays
     * there with Retry. Returns its job, or null when it did not start (and
     * a toast says why).
     */
    private fun transfer(
        entry: RemoteEntry,
        kind: TransferKind,
        /** A lost download folder: in place of asking for it at once (a batch asks once). */
        onFolderLost: ((RemoteEntry) -> Unit)? = null,
        block: suspend (SftpBrowser, (Long, Long) -> Unit) -> Unit,
    ): Job? {
        val now = _state.value.downloads[entry.path]
        if (now != null && now.failure == null) return null.also { toast(Texts.get(R.string.files_busy, entry.name)) }
        val port = localPort
        if (!_state.value.online || port == null) return null.also { toast(Texts.get(R.string.files_needs_connection), NoticeKind.WARN) }
        if (!entry.transferable) {
            return null.also { toast(Texts.get(if (entry.link?.broken == true) R.string.files_broken_toast else R.string.files_not_regular, entry.name), NoticeKind.WARN) }
        }
        setDownload(entry.path, Transfer(entry.name, 0, entry.size, kind, waiting = true))
        val job = scope.launch(start = TransferJobs.START) {
            val me = currentCoroutineContext().job
            // A cancelled one leaves the row alone: it may show a new transfer of the same file by now.
            val mine = { downloadJobs.owns(entry.path, me) }
            try {
                slots.run(onStart = { if (mine()) setDownload(entry.path, Transfer(entry.name, 0, entry.size, kind)) }) {
                    val b = connected(port)
                    block(b, throttled { done, total -> if (mine()) setDownload(entry.path, Transfer(entry.name, done, total, kind)) })
                }
                if (mine()) _state.update { it.copy(downloads = it.downloads - entry.path) }
            } catch (e: CancellationException) {
                if (mine()) _state.update { it.copy(downloads = it.downloads - entry.path) }
                throw e
            } catch (e: Exception) {
                if (!mine()) return@launch
                val message = connectionTrouble(e) ?: fileProblem(e)
                setDownload(entry.path, Transfer(entry.name, 0, entry.size, kind, failure = message))
                if (isFolderAccess(e)) onFolderLost?.invoke(entry) ?: _events.trySend(FilesEvent.PickFolder(listOf(entry)))
            } finally {
                downloadJobs.release(entry.path, me)
            }
        }
        downloadJobs.put(entry.path, job)
        return transfers.track(job)
    }

    /**
     * Cancel on a running download, Open with or Share: only that one stops;
     * its half copy is removed as on any cancel. The row clears at once.
     */
    fun cancelDownload(path: String) {
        if (_state.value.downloads[path]?.failure != null) return
        downloadJobs.cancel(path)
        _state.update { it.copy(downloads = it.downloads - path) }
    }

    /** Uploads [uris] one after another into the folder shown now, "Keep both" on a name clash. */
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val dir = _state.value.path
        if (!_state.value.online || dir == null || localPort == null) return toast(Texts.get(R.string.files_needs_connection), NoticeKind.WARN)
        scope.launch {
            val picked = withContext(Dispatchers.IO) { uris.map { PhoneFiles.picked(app, it) } }
            val queued = picked.map { Upload(uploadIds.getAndIncrement(), it.uri, dir, Transfer(it.name, 0, it.size)) }
            _state.update { it.copy(uploads = it.uploads + queued) }
            runUploads(queued)
        }
    }

    /** Retry of a failed upload, into the folder it was for. */
    fun retryUpload(id: Long) {
        val failed = _state.value.uploads.firstOrNull { it.id == id && it.transfer.failure != null } ?: return
        if (!_state.value.online || localPort == null) return toast(Texts.get(R.string.files_needs_connection), NoticeKind.WARN)
        val again = failed.copy(transfer = failed.transfer.copy(done = 0, failure = null))
        _state.update { s -> s.copy(uploads = s.uploads.map { if (it.id == id) again else it }) }
        runUploads(listOf(again))
    }

    /**
     * Cancel on an upload going or waiting: only that one stops, its part
     * file is removed as on any cancel; the others go on. The row clears at once.
     */
    fun cancelUpload(id: Long) {
        if (_state.value.uploads.none { it.id == id && it.transfer.failure == null }) return
        _state.update { s -> s.copy(uploads = s.uploads.filterNot { it.id == id }) }
        uploadJobs.cancel(id)
    }

    fun dismissUpload(id: Long) {
        _state.update { s -> s.copy(uploads = s.uploads.filterNot { it.id == id && it.transfer.failure != null }) }
    }

    private fun runUploads(items: List<Upload>) {
        val t = target ?: return
        transfers.track(
            scope.launch(start = TransferJobs.START) {
                // A row gone from the list was cancelled while it waited.
                val uploaded = runOneByOne(
                    items, uploadJobs, key = { it.id },
                    wanted = { item -> _state.value.uploads.any { it.id == item.id } },
                ) { item -> uploadLock.withLock { uploadOne(t, item) } }
                when (uploaded.size) {
                    0 -> Unit
                    1 -> toast(Texts.get(R.string.files_uploaded, uploaded.single()), NoticeKind.DONE)
                    else -> toast(Texts.plural(R.plurals.files_uploaded_many, uploaded.size, uploaded.size), NoticeKind.DONE)
                }
            },
        )
    }

    /** One upload; its row goes when it is done, stays with the reason and Retry when it fails. Returns the name it got. */
    private suspend fun uploadOne(t: FilesTarget, item: Upload): String? {
        val setTransfer = { transfer: Transfer -> _state.update { s -> s.copy(uploads = s.uploads.map { if (it.id == item.id) it.copy(transfer = transfer) else it }) } }
        val name = item.transfer.name
        try {
            val port = localPort ?: throw IOException("no tunnel")
            val b = connected(port)
            val got = withContext(Dispatchers.IO) {
                val input = try {
                    app.contentResolver.openInputStream(item.uri) ?: throw IOException("cannot read $name")
                } catch (e: IOException) {
                    throw PhoneSideException(e)
                }
                input.use {
                    uploadInto(b.fs, it, item.transfer.total, item.dir, name, app.uploadNames, "${t.profile}/${t.device}:${t.port}", onProgress = throttled { done, total ->
                        setTransfer(Transfer(name, done, total))
                    })
                }
            }
            _state.update { s -> s.copy(uploads = s.uploads.filterNot { it.id == item.id }) }
            if (_state.value.path == item.dir) reloadQuietly()
            return got
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setTransfer(Transfer(name, 0, item.transfer.total, failure = connectionTrouble(e) ?: fileProblem(e)))
            return null
        }
    }

    /**
     * "Search subfolders": [query] in the names below the folder shown, on
     * the device; hits come into [FilesUiState.deepSearch] as they are found.
     */
    fun searchSubfolders(query: String) {
        val root = _state.value.path ?: return
        val text = query.trim()
        // Names hold no `/`: the screen says so in place of searching.
        if (text.isEmpty() || '/' in text) return
        val port = localPort
        if (!_state.value.online || port == null) return toast(Texts.get(R.string.files_needs_connection), NoticeKind.WARN)
        searchJob?.cancel()
        val hidden = _state.value.showHidden
        val id = searchIds.getAndIncrement()
        _state.update { it.copy(deepSearch = DeepSearch(id, text, root, hidden)) }
        searchJob = scope.launch {
            // Only this run's state: a newer one, or none, is left alone.
            val mine = { now: FilesUiState -> now.deepSearch?.let { it.id == id && it.running } == true }
            val end = { change: (DeepSearch) -> DeepSearch ->
                _state.update { now -> if (mine(now)) now.copy(deepSearch = change(now.deepSearch!!).copy(running = false)) else now }
            }
            try {
                val b = connected(port)
                val outcome = searchNames(b::exec, root, text, hidden) { hits ->
                    _state.update { now -> now.deepSearch?.withHits(id, hits)?.let { now.copy(deepSearch = it) } ?: now }
                }
                end { it.copy(end = outcome.end, skipped = outcome.skipped, message = outcome.message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CommandsRefused) {
                    end { it.copy(end = SearchEnd.NO_COMMANDS) }
                } else {
                    val message = connectionTrouble(e) ?: fileProblem(e)
                    end { it.copy(end = SearchEnd.FAILED, message = message) }
                }
            }
        }
    }

    /** Stop: the `find` ends, the hits so far stay. */
    fun stopSearch() {
        searchJob?.cancel()
        searchJob = null
        _state.update { now -> now.deepSearch?.takeIf { it.running }?.let { now.copy(deepSearch = it.copy(running = false, end = null)) } ?: now }
    }

    /** The text changed or the search closed: the search in subfolders ends and its hits go. */
    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        _state.update { it.copy(deepSearch = null, foundEntry = null) }
    }

    /** A tap on a hit: a folder opens; a file's card opens over the hits. */
    fun openFound(found: Found) {
        val port = localPort
        if (!_state.value.online || port == null) return toast(Texts.get(R.string.files_needs_connection), NoticeKind.WARN)
        _state.update { it.copy(busy = true) }
        scope.launch {
            try {
                val b = connected(port)
                val entry = withContext(Dispatchers.IO) { readEntry(b.fs, parentOf(found.path), found.path.substringAfterLast('/')) }
                if (entry.isDir) {
                    open(entry.folderPath)
                } else {
                    _state.update { it.copy(foundEntry = entry) }
                    _events.send(FilesEvent.ShowCard(entry.path))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(Texts.get(R.string.files_list_failed, found.relative, connectionTrouble(e) ?: fileProblem(e)), NoticeKind.WARN)
            } finally {
                // A folder opened from the hit shows its own bar until it is read.
                if (loadJob?.isActive != true) _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun load(path: String?, refresh: Boolean) {
        val t = target ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            val port = localPort ?: return@launch showCached(path, tunnelDown())
            _state.update { it.copy(busy = !refresh, refreshing = refresh) }
            try {
                val b = connected(port)
                val folder = readFolder(b.fs, path ?: ".")
                if (path == null) {
                    home = folder.path
                    withContext(Dispatchers.IO) { runCatching { app.listings.saveHome(t.profile, t.device, t.port, t.user, folder.path) } }
                }
                withContext(Dispatchers.IO) { runCatching { app.listings.save(t.profile, t.device, t.port, folder.path, folder.entries) } }
                _state.update { it.copy(status = FilesStatus.Ready) }
                show(folder.path, folder.entries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (connectionTrouble(e, path) == null) {
                    val at = path ?: home ?: "~"
                    val problem = Texts.get(R.string.files_list_failed, at, fileProblem(e))
                    if (_state.value.status == FilesStatus.Ready && _state.value.path != null && path != _state.value.path) {
                        // A folder that cannot be opened from one that is shown: say so and stay.
                        toast(problem, NoticeKind.WARN)
                    } else {
                        _state.update { it.copy(path = path ?: it.path, status = FilesStatus.Failed(problem)) }
                        show(path ?: _state.value.path, emptyList())
                    }
                }
            } finally {
                _state.update { it.copy(busy = false, refreshing = false) }
            }
        }
    }

    /**
     * What a failure says about the connection, as loading and transfers
     * both meet it: a host key to decide on, a refused login, the
     * connection gone (offline, the folder as last seen). Sets the screen's
     * status and returns the words for it; null when it is not about the
     * connection (the device refused one operation, or the phone failed).
     */
    private suspend fun connectionTrouble(e: Throwable, path: String? = _state.value.path): String? = when {
        e is HostKeyRejected -> {
            dropBrowser()
            _state.update {
                it.copy(
                    status = when (val check = e.check) {
                        is HostKeyCheck.Changed -> FilesStatus.HostKeyChanged(check.pinned, e.presented)
                        else -> FilesStatus.HostKeyUnknown(e.presented)
                    },
                )
            }
            Texts.get(if (e.check is HostKeyCheck.Changed) R.string.terminal_status_host_key_changed else R.string.terminal_status_host_key_unknown)
        }
        e is UserAuthException -> {
            val message = withContext(Dispatchers.IO) { connector?.authFailure() } ?: fileProblem(e)
            dropBrowser()
            _state.update { it.copy(status = FilesStatus.Failed(message, loginRefused = true)) }
            message
        }
        isDeviceRefusal(e) || e is PhoneSideException || isFolderAccess(e) || e !is IOException -> null
        else -> {
            dropBrowser()
            val reason = Texts.get(R.string.ssh_cannot_connect, fileProblem(e))
            showCached(path, reason)
            fileProblem(e)
        }
    }

    /** The folder shown again after an upload, where it was, without the loading bar. */
    private fun reloadQuietly() {
        if (loadJob?.isActive == true) return
        val t = target ?: return
        val dir = _state.value.path ?: return
        val port = localPort ?: return
        loadJob = scope.launch {
            try {
                val folder = readFolder(connected(port).fs, dir)
                withContext(Dispatchers.IO) { runCatching { app.listings.save(t.profile, t.device, t.port, folder.path, folder.entries) } }
                if (_state.value.path == dir) show(folder.path, folder.entries)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Pull to refresh says what is wrong; the upload's own result is on its row.
            }
        }
    }

    /** The listing last seen of [path] (the home folder when null), offline. */
    private fun showCached(path: String?, reason: String) {
        val t = target ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            val (at, listing) = withContext(Dispatchers.IO) {
                val at = path ?: home ?: app.listings.loadHome(t.profile, t.device, t.port, t.user)
                at to at?.let { app.listings.load(t.profile, t.device, t.port, it) }
            }
            val status = if (localPort == null && tunnelConnecting) {
                FilesStatus.TunnelConnecting
            } else {
                FilesStatus.Offline(reason, listing?.savedAt, listing?.truncated == true)
            }
            _state.update { it.copy(status = status, busy = false, refreshing = false) }
            show(at, listing?.entries.orEmpty())
        }
    }

    /** Why there is no tunnel: its last connect's failure, else that it is not connected. */
    private fun tunnelDown(): String = tunnelError ?: Texts.get(R.string.files_tunnel_down)

    private fun show(path: String?, entries: List<RemoteEntry>) {
        all = entries
        // Another folder: the search was for the one before.
        if (path != _state.value.path) clearSearch()
        _state.update {
            val visible = visibleListing(entries, it.showHidden)
            it.copy(
                path = path,
                entries = visible,
                hasHidden = !it.showHidden && entries.any { e -> e.hidden },
                selection = it.selection.keepIn(path, visible),
            )
        }
    }

    /** The open SFTP connection through the tunnel at [port], made again when it dropped. */
    private suspend fun connected(port: Int): SftpBrowser = connectLock.withLock {
        browser?.takeIf { it.isOpen && it.connector.localPort == port }?.let { return it }
        val t = checkNotNull(target)
        val hostKeys = checkNotNull(runtime).hostKeys
        browser?.let { old -> withContext(Dispatchers.IO) { old.close() } }
        browser = null
        val made = SshConnector(t.profile, hostKeys, t.device, t.port, port, t.user, t.auth, app.sshEnv)
        connector = made
        val b = SftpBrowser(made)
        try {
            withContext(Dispatchers.IO) { b.open() }
        } catch (e: Throwable) {
            withContext(Dispatchers.IO + NonCancellable) { b.close() }
            throw e
        }
        browser = b
        b
    }

    private fun dropBrowser() {
        val b = browser ?: return
        browser = null
        app.appScope.launch(Dispatchers.IO) { b.close() }
    }

    private fun setDownload(path: String, transfer: Transfer) {
        _state.update { it.copy(downloads = it.downloads + (path to transfer)) }
    }

    private fun toast(text: String, kind: NoticeKind = NoticeKind.INFO) {
        _events.trySend(FilesEvent.Notice(text, kind))
    }

    /**
     * The screen left, the session kept: its transfers stop as they would
     * on close (the screen asked first), the selection ends; the
     * connection, folder and listing stay for the screen's return.
     */
    override fun leave() {
        clearSearch()
        _state.update { it.copy(downloads = emptyMap(), uploads = emptyList(), selection = FileSelection()) }
        app.appScope.launch(Dispatchers.IO) { withTimeoutOrNull(CLEANUP_WAIT_MS) { transfers.cancelAndJoin() } }
    }

    /**
     * The screen is back: the kept folder shows at once, and is read again
     * (the connection made again into it when it dropped meanwhile). What
     * was said while no screen listened is stale and goes.
     */
    override fun resume() {
        while (_events.tryReceive().isSuccess) Unit
        if (localPort != null) load(_state.value.path, refresh = false)
    }

    /**
     * The session ends: its transfers are cancelled, each removes its part
     * file or half download, and only then the connection closes; a
     * transfer stuck on a stalled device is not waited for long (closing
     * the connection unblocks it). Files handed to other apps stay a
     * while, as a share target may read them later: only those older than
     * [HANDED_OVER_KEEP_MS] go.
     */
    override fun close() {
        val b = browser
        browser = null
        app.appScope.launch(Dispatchers.IO) {
            withTimeoutOrNull(CLEANUP_WAIT_MS) { transfers.cancelAndJoin() }
            b?.close()
            PhoneFiles.pruneOpenCache(app, HANDED_OVER_KEEP_MS)
        }
        scope.cancel()
        app.fileTargets.remove(targetId)
    }

    private companion object {
        const val CLEANUP_WAIT_MS = 15_000L
        const val HANDED_OVER_KEEP_MS = 2 * 60 * 60 * 1000L

        /** A row's bar moves in steps of a percent at most: a big file must not redraw the list thousands of times. */
        fun throttled(report: (Long, Long) -> Unit): (Long, Long) -> Unit {
            var last = -1L
            return { done, total ->
                val step = if (total > 0) done * 100 / total else done / (256 * 1024)
                if (step != last || done == total) {
                    last = step
                    report(done, total)
                }
            }
        }
    }
}
