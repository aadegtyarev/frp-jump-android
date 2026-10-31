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
import net.schmizz.sshj.userauth.UserAuthException
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.files.EntryType
import ru.adsrv.frpjump.files.FilesTarget
import ru.adsrv.frpjump.files.PhoneFiles
import ru.adsrv.frpjump.files.PhoneSideException
import ru.adsrv.frpjump.files.RemoteEntry
import ru.adsrv.frpjump.files.SftpBrowser
import ru.adsrv.frpjump.files.fileProblem
import ru.adsrv.frpjump.files.isDeviceRefusal
import ru.adsrv.frpjump.files.parentOf
import ru.adsrv.frpjump.files.resolvePath
import ru.adsrv.frpjump.files.targetFolder
import ru.adsrv.frpjump.files.visibleListing
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.ssh.HostKeyCheck
import ru.adsrv.frpjump.ssh.HostKeyRejected
import ru.adsrv.frpjump.ssh.PinnedHostKey
import ru.adsrv.frpjump.ssh.SshConnector
import ru.adsrv.frpjump.tunnel.PendingOp
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What the file screen can say about its connection. */
sealed interface FilesStatus {
    /** Nothing listed yet: connecting and reading the first folder. */
    data object Starting : FilesStatus

    data object Ready : FilesStatus

    /** The tunnel is coming up; the folder shows as last seen. */
    data object TunnelConnecting : FilesStatus

    /**
     * No tunnel, or SSH could not connect ([reason]): the folder as last
     * seen at [cachedAt] (null when it never was), nothing can be done to it.
     */
    data class Offline(val reason: String, val cachedAt: Long?) : FilesStatus

    /** Connected, but the folder could not be read, or the login was refused. */
    data class Failed(val message: String) : FilesStatus

    data class HostKeyUnknown(val presented: PinnedHostKey) : FilesStatus

    data class HostKeyChanged(val pinned: PinnedHostKey, val presented: PinnedHostKey) : FilesStatus

    /** The screen's login is gone (the app was restarted under it). */
    data object Gone : FilesStatus
}

/** One file going to or from the phone; [total] 0 when the size is unknown. */
data class Transfer(val name: String, val done: Long, val total: Long) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

data class FilesUiState(
    /** The folder shown; null until the home folder is known. */
    val path: String? = null,
    /** Its entries as the screen lists them (sorted, hidden ones filtered). */
    val entries: List<RemoteEntry> = emptyList(),
    /** Whether there are entries the hidden filter takes away. */
    val hasHidden: Boolean = false,
    val showHidden: Boolean = false,
    val status: FilesStatus = FilesStatus.Starting,
    /** A folder is being opened (a thin bar under the path). */
    val busy: Boolean = false,
    /** Pull to refresh in progress. */
    val refreshing: Boolean = false,
    /** Downloads, Open with and Share in progress, by the device path. */
    val downloads: Map<String, Transfer> = emptyMap(),
    /** Uploads into this screen's folders, in order. */
    val uploads: List<Transfer> = emptyList(),
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
    data class PickFolder(val entry: RemoteEntry) : FilesEvent
}

/**
 * The files of the device behind one SSH tunnel, over an SFTP connection
 * of their own ([SftpBrowser]); follows the tunnel: down, the last listing
 * of each folder shows from [ru.adsrv.frpjump.files.ListingCache].
 */
class FilesViewModel(private val app: FrpJumpApp, private val targetId: Int) : ViewModel() {
    val target: FilesTarget? = app.fileTargets.get(targetId)
    private val runtime = target?.let { app.profiles.runtime(it.profile) }

    private val _state = MutableStateFlow(FilesUiState(showHidden = app.settings.settings.value.filesShowHidden))
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

    init {
        if (target == null || runtime == null) {
            _state.update { it.copy(status = FilesStatus.Gone) }
        } else {
            viewModelScope.launch {
                app.settings.settings.map { it.filesShowHidden to it.filesDownloadFolder }.distinctUntilChanged().collect { (hidden, folder) ->
                    val label = folder?.let { uri -> withContext(Dispatchers.IO) { runCatching { PhoneFiles.folderName(app, Uri.parse(uri)) }.getOrNull() } }
                    _state.update { it.copy(showHidden = hidden, downloadFolder = label) }
                    show(_state.value.path, all)
                }
            }
            val key = localPortKey(target.device, target.port)
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
        if (_state.value.status is FilesStatus.Offline || _state.value.status == FilesStatus.TunnelConnecting) {
            showCached(path, (_state.value.status as? FilesStatus.Offline)?.reason ?: tunnelDown())
        } else {
            load(path, refresh = false)
        }
    }

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

    /** "Go to the target" of a symlink. */
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
        transfer(entry) { b, progress ->
            val (doc, name) = withContext(Dispatchers.IO) { onPhone { PhoneFiles.createInTree(app, tree, entry.name) } }
            try {
                withContext(Dispatchers.IO) {
                    val out = onPhone { app.contentResolver.openOutputStream(doc) ?: throw IOException("cannot write $name") }
                    out.use { b.download(entry.path, it, progress) }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.IO) { PhoneFiles.delete(app, doc) }
                throw e
            }
            toast(Texts.get(R.string.files_downloaded, name))
        }
    }

    /** Tap on a file: to the cache, then "Open with". */
    fun openWith(entry: RemoteEntry) = handover(entry, share = false)

    fun share(entry: RemoteEntry) = handover(entry, share = true)

    private fun handover(entry: RemoteEntry, share: Boolean) {
        transfer(entry) { b, progress ->
            val file = withContext(Dispatchers.IO) {
                val file = onPhone { PhoneFiles.openCacheFile(app, entry.name) }
                try {
                    onPhone { FileOutputStream(file) }.use { b.download(entry.path, it, progress) }
                } catch (e: Throwable) {
                    file.delete()
                    throw e
                }
                file
            }
            _events.send(FilesEvent.Handover(file, PhoneFiles.mimeType(entry.name), entry.name, share))
        }
    }

    /** One download of [entry] at a time, shown on its row; the result as a toast. */
    private fun transfer(entry: RemoteEntry, block: suspend (SftpBrowser, (Long, Long) -> Unit) -> Unit) {
        if (entry.path in _state.value.downloads) return toast(Texts.get(R.string.files_busy, entry.name))
        val port = localPort
        if (!_state.value.online || port == null) return toast(Texts.get(R.string.files_needs_connection))
        if (entry.type != EntryType.FILE) {
            return toast(Texts.get(if (entry.link?.broken == true) R.string.files_broken_toast else R.string.files_not_regular, entry.name))
        }
        setDownload(entry.path, Transfer(entry.name, 0, entry.size))
        viewModelScope.launch {
            try {
                val b = connected(port)
                block(b, throttled { done, total -> setDownload(entry.path, Transfer(entry.name, done, total)) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(Texts.get(R.string.files_download_failed, entry.name, fileProblem(e)))
            } finally {
                _state.update { it.copy(downloads = it.downloads - entry.path) }
            }
        }
    }

    /** Uploads [uris] one after another into the folder shown now, "Keep both" on a name clash. */
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val dir = _state.value.path
        val port = localPort
        if (!_state.value.online || dir == null || port == null) return toast(Texts.get(R.string.files_needs_connection))
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { uris.map { PhoneFiles.picked(app, it) } }
            val queued = picked.map { Transfer(it.name, 0, it.size) }
            _state.update { it.copy(uploads = it.uploads + queued) }
            val uploaded = mutableListOf<String>()
            picked.forEachIndexed { i, file ->
                var shown = queued[i]
                val progress = throttled { done, total ->
                    val next = Transfer(file.name, done, total)
                    val was = shown
                    shown = next
                    _state.update { s -> s.copy(uploads = s.uploads.map { if (it === was) next else it }) }
                }
                try {
                    val b = connected(port)
                    val name = withContext(Dispatchers.IO) {
                        val input = onPhone { app.contentResolver.openInputStream(file.uri) ?: throw IOException("cannot read ${file.name}") }
                        input.use { b.upload(it, file.size, dir, file.name, progress) }
                    }
                    uploaded += name
                    if (_state.value.path == dir) reloadQuietly()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast(Texts.get(R.string.files_upload_failed, file.name, fileProblem(e)))
                } finally {
                    val gone = shown
                    _state.update { s -> s.copy(uploads = s.uploads.filterNot { it === gone }) }
                }
            }
            when (uploaded.size) {
                0 -> Unit
                1 -> toast(Texts.get(R.string.files_uploaded, uploaded.single()))
                else -> toast(Texts.plural(R.plurals.files_uploaded_many, uploaded.size, uploaded.size))
            }
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
                val dir = path ?: withContext(Dispatchers.IO) { b.home() }.also { found ->
                    home = found
                    withContext(Dispatchers.IO) { runCatching { app.listings.saveHome(t.profile, t.device, t.port, t.user, found) } }
                }
                val entries = withContext(Dispatchers.IO) { b.list(dir) }
                withContext(Dispatchers.IO) { runCatching { app.listings.save(t.profile, t.device, t.port, dir, entries) } }
                _state.update { it.copy(status = FilesStatus.Ready) }
                show(dir, entries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostKeyRejected) {
                dropBrowser()
                _state.update {
                    it.copy(
                        status = when (val check = e.check) {
                            is HostKeyCheck.Changed -> FilesStatus.HostKeyChanged(check.pinned, e.presented)
                            else -> FilesStatus.HostKeyUnknown(e.presented)
                        },
                    )
                }
            } catch (e: UserAuthException) {
                val message = withContext(Dispatchers.IO) { connector?.authFailure() } ?: fileProblem(e)
                dropBrowser()
                _state.update { it.copy(status = FilesStatus.Failed(message)) }
            } catch (e: Exception) {
                val at = path ?: home ?: "~"
                when {
                    !isDeviceRefusal(e) -> {
                        dropBrowser()
                        showCached(path, Texts.get(R.string.ssh_cannot_connect, fileProblem(e)))
                    }
                    // A folder that cannot be opened from one that is shown: say so and stay.
                    _state.value.status == FilesStatus.Ready && _state.value.path != null && path != _state.value.path ->
                        toast(Texts.get(R.string.files_list_failed, at, fileProblem(e)))
                    else -> {
                        _state.update { it.copy(path = path ?: it.path, status = FilesStatus.Failed(Texts.get(R.string.files_list_failed, at, fileProblem(e)))) }
                        show(path ?: _state.value.path, emptyList())
                    }
                }
            } finally {
                _state.update { it.copy(busy = false, refreshing = false) }
            }
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
                val entries = withContext(Dispatchers.IO) { connected(port).list(dir) }
                withContext(Dispatchers.IO) { runCatching { app.listings.save(t.profile, t.device, t.port, dir, entries) } }
                if (_state.value.path == dir) show(dir, entries)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Pull to refresh says what is wrong; the upload's own result was told already.
            }
        }
    }

    /** The listing last seen of [path] (the home folder when null), offline. */
    private fun showCached(path: String?, reason: String) {
        val t = target ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val listing = withContext(Dispatchers.IO) {
                val at = path ?: home ?: app.listings.loadHome(t.profile, t.device, t.port, t.user)
                at to at?.let { app.listings.load(t.profile, t.device, t.port, it) }
            }
            val status = if (localPort == null && tunnelConnecting) FilesStatus.TunnelConnecting else FilesStatus.Offline(reason, listing.second?.savedAt)
            _state.update { it.copy(status = status, busy = false, refreshing = false) }
            show(listing.first, listing.second?.entries.orEmpty())
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

    override fun onCleared() {
        dropBrowser()
        app.fileTargets.remove(targetId)
    }

    private companion object {
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

        /** Marks a failure as the phone's: its folder or cache, not the device. */
        inline fun <T> onPhone(block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            throw if (e is PhoneSideException) e else PhoneSideException(e)
        }
    }
}
