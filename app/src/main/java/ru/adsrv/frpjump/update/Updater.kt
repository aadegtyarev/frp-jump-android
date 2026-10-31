package ru.adsrv.frpjump.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.ui.userMessage
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Where an update download is. */
sealed interface UpdateDownload {
    data object Idle : UpdateDownload

    /** [read] of [total] bytes (0: not known yet). */
    data class Running(val read: Long, val total: Long) : UpdateDownload

    /** Downloaded and its SHA-256 checked: [apk] is ready for the system installer. */
    data class Ready(val apk: File) : UpdateDownload

    data class Failed(val message: String) : UpdateDownload
}

data class UpdateState(
    /** A check is under way. */
    val checking: Boolean = false,
    /** When GitHub last answered a check. */
    val checkedAt: Long? = null,
    /** Why the last check the owner asked for failed; automatic ones fail quietly. */
    val checkError: String? = null,
    /** The latest release, when it is newer than this app. */
    val available: Release? = null,
    val download: UpdateDownload = UpdateDownload.Idle,
    /** A newer version not yet told about: the app says so once, then calls [Updater.announced]. */
    val announce: String? = null,
)

/**
 * Settings › Version and update (contract scenario 10): looks for a newer
 * release when the app is opened, at most once a day, or when the owner
 * asks; downloads its APK on the owner's tap and checks it against the
 * published SHA-256. Installing is the system installer's, on another tap.
 */
class Updater(
    private val installed: String,
    private val records: UpdateRecordStore,
    private val source: ReleaseSource,
    /** Where downloads go; emptied at start, as a new start means any download there is done with. */
    private val downloads: File,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state

    private var record = UpdateRecord()
    /** Done once [record] is read: a check must not start from (and then overwrite) an empty one. */
    private val loaded = CompletableDeferred<Unit>()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** Reads what the last check learnt, then checks whenever [visible] turns true and a check is due. Main thread. */
    fun start(visible: StateFlow<Boolean>) {
        scope.launch {
            record = withContext(Dispatchers.IO) {
                downloads.deleteRecursively()
                records.read()
            }
            show(record.release?.let(::parseRelease), checkedAt = record.checkedAt)
            loaded.complete(Unit)
            visible.filter { it }.collect {
                if (checkDue(now(), record.checkedAt)) check(byOwner = false)
            }
        }
    }

    /** Asks GitHub now; [byOwner] failures are shown, the automatic one's are not. Main thread. */
    fun check(byOwner: Boolean = true) {
        if (checkJob?.isActive == true) return
        _state.update { it.copy(checking = true, checkError = null) }
        checkJob = scope.launch {
            loaded.await()
            try {
                val answer = source.latest(record.etag)
                val at = now()
                record = when (answer) {
                    ReleaseSource.Latest.NotModified -> record.copy(checkedAt = at)
                    ReleaseSource.Latest.None -> record.copy(checkedAt = at, etag = null, release = null)
                    is ReleaseSource.Latest.Fresh -> record.copy(checkedAt = at, etag = answer.etag, release = answer.json)
                }
                val kept = record
                withContext(Dispatchers.IO) { runCatching { records.write(kept) } }
                show(record.release?.let(::parseRelease), checkedAt = at)
                _state.update { it.copy(checking = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ReleaseCheckException) {
                    // GitHub did answer (a rate limit, most likely): the next automatic check waits a day as well.
                    record = record.copy(checkedAt = now())
                    val kept = record
                    withContext(Dispatchers.IO) { runCatching { records.write(kept) } }
                }
                val message = if (e is ReleaseCheckException && e.status in RATE_LIMITED) {
                    Texts.get(R.string.update_rate_limited)
                } else {
                    Texts.get(R.string.update_check_failed, e.userMessage())
                }
                _state.update { it.copy(checking = false, checkError = if (byOwner) message else null) }
            }
        }
    }

    /** The newer version has been told about; not again for it. Main thread. */
    fun announced() {
        val version = _state.value.announce ?: return
        _state.update { it.copy(announce = null) }
        record = record.copy(announced = version)
        val kept = record
        scope.launch(Dispatchers.IO) { runCatching { records.write(kept) } }
    }

    /** Downloads the available release's APK and checks its SHA-256; [UpdateDownload.Ready] when done. Main thread. */
    fun download() {
        val release = _state.value.available ?: return
        if (downloadJob?.isActive == true) return
        val checksumUrl = release.checksumUrl
        if (checksumUrl == null) {
            _state.update { it.copy(download = UpdateDownload.Failed(Texts.get(R.string.update_no_checksum))) }
            return
        }
        _state.update { it.copy(download = UpdateDownload.Running(0, release.apkSize)) }
        downloadJob = scope.launch {
            val result = try {
                val expected = checksumFor(source.text(checksumUrl), release.apkName)
                    ?: throw IOException(Texts.get(R.string.update_no_checksum))
                val apk = File(downloads, release.apkName.replace('/', '_'))
                withContext(Dispatchers.IO) {
                    downloads.deleteRecursively()
                    downloads.mkdirs()
                }
                val actual = source.download(release.apkUrl, apk, MAX_APK_BYTES) { read, total ->
                    _state.update { it.copy(download = UpdateDownload.Running(read, if (total > 0) total else release.apkSize)) }
                }
                if (!checksumMatches(expected, actual)) {
                    withContext(Dispatchers.IO) { apk.delete() }
                    throw ChecksumMismatchException()
                }
                UpdateDownload.Ready(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChecksumMismatchException) {
                UpdateDownload.Failed(Texts.get(R.string.update_checksum_mismatch))
            } catch (e: Exception) {
                UpdateDownload.Failed(Texts.get(R.string.update_download_failed, e.userMessage()))
            }
            _state.update { it.copy(download = result) }
        }
    }

    /** Leaves a failed download's message; the owner may try again. Main thread. */
    fun clearDownloadError() {
        if (_state.value.download is UpdateDownload.Failed) _state.update { it.copy(download = UpdateDownload.Idle) }
    }

    private fun show(release: Release?, checkedAt: Long?) {
        val newer = release?.takeIf { isNewer(it.version, installed) }
        _state.update { current ->
            current.copy(
                checkedAt = checkedAt,
                available = newer,
                // A different release than the one downloaded: start over.
                download = if (newer?.apkUrl == current.available?.apkUrl) current.download else UpdateDownload.Idle,
                announce = newer?.version?.takeIf { it != record.announced },
            )
        }
    }

    private companion object {
        /** GitHub's answers to too many requests without a token. */
        val RATE_LIMITED = setOf(403, 429)
        /** The APK is about 20 MB; anything this large is not it. */
        const val MAX_APK_BYTES = 200L * 1024 * 1024
    }
}
