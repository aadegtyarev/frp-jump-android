package ru.adsrv.frpjump.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
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
import ru.adsrv.frpjump.files.FilesTarget
import ru.adsrv.frpjump.files.PhoneFiles
import ru.adsrv.frpjump.files.PhoneSideException
import ru.adsrv.frpjump.files.RemoteEntry
import ru.adsrv.frpjump.files.SftpBrowser
import ru.adsrv.frpjump.files.TransferJobs
import ru.adsrv.frpjump.files.downloadInto
import ru.adsrv.frpjump.files.fileProblem
import ru.adsrv.frpjump.files.isDeviceRefusal
import ru.adsrv.frpjump.files.isFolderAccess
import ru.adsrv.frpjump.files.parentOf
import ru.adsrv.frpjump.files.readFolder
import ru.adsrv.frpjump.files.resolvePath
import ru.adsrv.frpjump.files.targetFolder
import ru.adsrv.frpjump.files.uploadInto
import ru.adsrv.frpjump.files.visibleListing
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.ssh.HostKeyCheck
import ru.adsrv.frpjump.ssh.HostKeyRejected
import ru.adsrv.frpjump.ssh.PinnedHostKey
import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.SshConnector
import ru.adsrv.frpjump.ssh.SshLogin
import ru.adsrv.frpjump.tunnel.PendingOp
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
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
data class Transfer(val name: String, val done: Long, val total: Long, val kind: TransferKind = TransferKind.DOWNLOAD, val failure: String? = null) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
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
) {
    val online: Boolean get() = status == FilesStatus.Ready
}

/** What the screen does once, outside its state. */
sealed interface FilesEvent {
    data class Toast(val text: String) : FilesEvent

    /** Hand [file] to another app: view it ([share] false) or share it. */
    data class Handover(val file: File, val mime: String, val name: String, val share: Boolean) : FilesEvent

    /** No usable download folder: ask for one, then download [entry]. */
    data class PickFolder(val entry: RemoteEntry?) : FilesEvent
}

/**
 * The files of the device behind one SSH tunnel, over an SFTP connection
 * of their own ([SftpBrowser]); follows the tunnel: down, the last listing
 * of each folder shows from [ru.adsrv.frpjump.files.ListingCache].
 */
class FilesViewModel(private val app: FrpJumpApp, private val targetId: Int) : ViewModel() {
    var target: FilesTarget? = app.fileTargets.get(targetId)
        private set
    private val runtime = target?.let { app.profiles.runtime(it.profile) }

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

    /** Transfers in flight: the connection closes only after they have cleaned up. */
    private val transfers = TransferJobs()

    /** One upload at a time from this screen. */
    private val uploadLock = Mutex()
    private val uploadIds = AtomicLong(1)

    init {
        val t = target
        if (t == null || runtime == null) {
            _state.update { it.copy(status = FilesStatus.Gone) }
        } else {
            viewModelScope.launch {
                app.settings.settings.map { it.filesShowHidden to it.filesDownloadFolder }.distinctUntilChanged().collect { (hidden, folder) ->
                    val label = folder?.let { uri -> withContext(Dispatchers.IO) { runCatching { PhoneFiles.folderName(app, Uri.parse(uri)) }.getOrNull() } }
                    _state.update { it.copy(showHidden = hidden, downloadFolder = label) }
                    show(_state.value.path, all)
                }
            }
            val key = localPortKey(t.device, t.port)
            viewModelScope.launch {
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

    fun setShowHidden(show: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { app.settings.update { it.copy(filesShowHidden = show) } }
    }

    fun trustHostKey() {
        val s = _state.value.status as? FilesStatus.HostKeyUnknown ?: return
        val t = target ?: return
        _state.update { it.copy(status = FilesStatus.Starting) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runtime?.hostKeys?.pin(t.device, t.port, s.presented) }
            load(_state.value.path, refresh = false)
        }
    }

    fun forgetOldHostKey() {
        if (_state.value.status !is FilesStatus.HostKeyChanged) return
        val t = target ?: return
        _state.update { it.copy(status = FilesStatus.Starting) }
        viewModelScope.launch {
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
        return app.sshPasswords.has(t.profile, t.device, t.port, user.trim())
    }

    /** "Change login" after a refused one: log in again with what the dialog gave. */
    fun changeLogin(user: String, method: SshAuthMethod, password: String, remember: Boolean) {
        val t = target ?: return
        val login = SshLogin(user.trim().ifEmpty { SshLogin.DEFAULT_USER }, method)
        viewModelScope.launch(Dispatchers.IO) { runtime?.sshLogins?.remember(t.device, t.port, login) }
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
    fun setDownloadFolder(tree: Uri, then: RemoteEntry?) {
        viewModelScope.launch {
            val kept = withContext(Dispatchers.IO) {
                PhoneFiles.keep(app, tree) && runCatching { app.settings.update { it.copy(filesDownloadFolder = tree.toString()) } }.isSuccess
            }
            if (!kept) return@launch toast(Texts.get(R.string.files_folder_not_kept))
            then?.let(::download)
        }
    }

    /** Download into the chosen folder, "Keep both" on a name clash. */
    fun download(entry: RemoteEntry) {
        val tree = downloadFolder() ?: run {
            _events.trySend(FilesEvent.PickFolder(entry))
            return
        }
        transfer(entry, TransferKind.DOWNLOAD) { b, progress ->
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
            toast(Texts.get(R.string.files_downloaded, name))
        }
    }

    /** Tap on a file: to the cache, then "Open with". */
    fun openWith(entry: RemoteEntry) = transfer(entry, TransferKind.OPEN) { b, progress -> handover(b, entry, progress, share = false) }

    fun share(entry: RemoteEntry) = transfer(entry, TransferKind.SHARE) { b, progress -> handover(b, entry, progress, share = true) }

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
        _events.send(FilesEvent.Handover(file, PhoneFiles.mimeType(entry.name), entry.name, share))
    }

    /** One transfer of [entry] at a time, shown on its row; a failure stays there with Retry. */
    private fun transfer(entry: RemoteEntry, kind: TransferKind, block: suspend (SftpBrowser, (Long, Long) -> Unit) -> Unit) {
        val now = _state.value.downloads[entry.path]
        if (now != null && now.failure == null) return toast(Texts.get(R.string.files_busy, entry.name))
        val port = localPort
        if (!_state.value.online || port == null) return toast(Texts.get(R.string.files_needs_connection))
        if (!entry.transferable) {
            return toast(Texts.get(if (entry.link?.broken == true) R.string.files_broken_toast else R.string.files_not_regular, entry.name))
        }
        setDownload(entry.path, Transfer(entry.name, 0, entry.size, kind))
        transfers.track(
            viewModelScope.launch(start = TransferJobs.START) {
                try {
                    val b = connected(port)
                    block(b, throttled { done, total -> setDownload(entry.path, Transfer(entry.name, done, total, kind)) })
                    _state.update { it.copy(downloads = it.downloads - entry.path) }
                } catch (e: CancellationException) {
                    _state.update { it.copy(downloads = it.downloads - entry.path) }
                    throw e
                } catch (e: Exception) {
                    val message = connectionTrouble(e) ?: fileProblem(e)
                    setDownload(entry.path, Transfer(entry.name, 0, entry.size, kind, failure = message))
                    if (isFolderAccess(e)) _events.trySend(FilesEvent.PickFolder(entry))
                }
            },
        )
    }

    /** Uploads [uris] one after another into the folder shown now, "Keep both" on a name clash. */
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val dir = _state.value.path
        if (!_state.value.online || dir == null || localPort == null) return toast(Texts.get(R.string.files_needs_connection))
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { uris.map { PhoneFiles.picked(app, it) } }
            val queued = picked.map { Upload(uploadIds.getAndIncrement(), it.uri, dir, Transfer(it.name, 0, it.size)) }
            _state.update { it.copy(uploads = it.uploads + queued) }
            runUploads(queued)
        }
    }

    /** Retry of a failed upload, into the folder it was for. */
    fun retryUpload(id: Long) {
        val failed = _state.value.uploads.firstOrNull { it.id == id && it.transfer.failure != null } ?: return
        if (!_state.value.online || localPort == null) return toast(Texts.get(R.string.files_needs_connection))
        val again = failed.copy(transfer = failed.transfer.copy(done = 0, failure = null))
        _state.update { s -> s.copy(uploads = s.uploads.map { if (it.id == id) again else it }) }
        runUploads(listOf(again))
    }

    fun dismissUpload(id: Long) {
        _state.update { s -> s.copy(uploads = s.uploads.filterNot { it.id == id && it.transfer.failure != null }) }
    }

    private fun runUploads(items: List<Upload>) {
        val t = target ?: return
        transfers.track(
            viewModelScope.launch(start = TransferJobs.START) {
                val uploaded = mutableListOf<String>()
                for (item in items) {
                    val name = uploadLock.withLock { uploadOne(t, item) } ?: continue
                    uploaded += name
                }
                when (uploaded.size) {
                    0 -> Unit
                    1 -> toast(Texts.get(R.string.files_uploaded, uploaded.single()))
                    else -> toast(Texts.plural(R.plurals.files_uploaded_many, uploaded.size, uploaded.size))
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

    private fun load(path: String?, refresh: Boolean) {
        val t = target ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
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
                        toast(problem)
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
        loadJob = viewModelScope.launch {
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
        loadJob = viewModelScope.launch {
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
        _state.update {
            it.copy(path = path, entries = visibleListing(entries, it.showHidden), hasHidden = !it.showHidden && entries.any { e -> e.hidden })
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

    private fun toast(text: String) {
        _events.trySend(FilesEvent.Toast(text))
    }

    /**
     * The screen is gone: its transfers are cancelled (viewModelScope),
     * each removes its part file or half download, and only then the
     * connection closes; a transfer stuck on a stalled device is not waited
     * for long (closing the connection unblocks it). Files handed to other
     * apps stay a while, as a share target may read them later: only those
     * older than [HANDED_OVER_KEEP_MS] go.
     */
    override fun onCleared() {
        val b = browser
        browser = null
        app.appScope.launch(Dispatchers.IO) {
            withTimeoutOrNull(CLEANUP_WAIT_MS) { transfers.cancelAndJoin() }
            b?.close()
            PhoneFiles.pruneOpenCache(app, HANDED_OVER_KEEP_MS)
        }
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
