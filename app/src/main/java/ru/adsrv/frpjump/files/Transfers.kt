package ru.adsrv.frpjump.files

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Reading or writing the phone's copy failed, not the device's. */
class PhoneSideException(cause: IOException) : IOException(cause.message, cause)

/** Where a download goes on the phone: [open] once, [discard] what was written when it does not finish. */
interface DownloadSink {
    fun open(): OutputStream

    fun discard()
}

/**
 * Copies the device's file at [path] into [sink]. A failure or a cancel
 * (Back, the screen closed) discards the half-written copy, so no
 * truncated file is left under its real name.
 */
suspend fun downloadInto(fs: RemoteFs, path: String, sink: DownloadSink, onProgress: (Long, Long) -> Unit) {
    try {
        val read = fs.openRead(path)
        read.input.use { input ->
            phoneSide(true) { sink.open() }.use { out -> copy(input, out, read.size, phoneIsInput = false, onProgress) }
        }
    } catch (e: Throwable) {
        // Blocking and not a suspension point: it runs in a cancelled coroutine too.
        runCatching { sink.discard() }
        throw e
    }
}

/**
 * The names uploads in this app are writing to, per device ([scope]) and
 * folder: two uploads of one name at once get two names, and a name stays
 * taken until its upload is renamed into it.
 */
class UploadNames {
    private val held = mutableSetOf<String>()

    /**
     * [name], or the first "Keep both" name neither on the device ([exists],
     * given the path) nor held. The device is asked outside the lock: a
     * stalled one must not hold up other screens' uploads.
     */
    fun reserve(scope: String, dir: String, name: String, exists: (String) -> Boolean): String {
        val onDevice = mutableSetOf<String>()
        while (true) {
            val candidate = synchronized(held) { keepBothName(name) { key(scope, dir, it) in held || it in onDevice } }
            if (exists(childOf(dir, candidate))) {
                onDevice += candidate
                continue
            }
            // Taken by another upload while the device was asked: try the next one.
            val got = synchronized(held) { held.add(key(scope, dir, candidate)) }
            if (got) return candidate
        }
    }

    fun release(scope: String, dir: String, name: String) {
        synchronized(held) { held -= key(scope, dir, name) }
    }

    private fun key(scope: String, dir: String, name: String) = scope + "\n" + childOf(dir, name)
}

/** A part file younger than this may belong to an upload still going (another phone, another app). */
const val STALE_PART_SECONDS = 10 * 60L

/**
 * Uploads [input] into folder [dir] of the device [scope] names as [name],
 * or `name (1).ext` when it is taken (on the device, or by another upload
 * in [names]). It is written to a hidden part file next to it and renamed
 * at the end, so a half upload never replaces anything. A part file there
 * already is replaced when it is older than [STALE_PART_SECONDS] by its
 * modified time (an interrupted upload; [nowSeconds] is the device's
 * clock as best known), else the upload takes the next "Keep both" name.
 * When the name was taken between the check and the rename, the next free
 * one is tried once. A failed or cancelled upload removes the part file it
 * made, never one it did not. Returns the name it got.
 */
suspend fun uploadInto(
    fs: RemoteFs,
    input: InputStream,
    size: Long,
    dir: String,
    name: String,
    names: UploadNames,
    scope: String,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
    onProgress: (Long, Long) -> Unit,
): String {
    val held = mutableListOf<String>()
    // Names whose part file someone else is writing.
    val busy = mutableSetOf<String>()
    val reserve = { names.reserve(scope, dir, name) { fs.exists(it) || it.substringAfterLast('/') in busy }.also { held += it } }
    try {
        var final: String
        var part: String
        while (true) {
            final = reserve()
            part = childOf(dir, uploadPartName(final))
            val existing = try {
                fs.lstat(part)
            } catch (e: RemoteRefusal) {
                if (e.reason != Refusal.NO_SUCH) throw e
                null
            }
            if (existing == null) break
            if (nowSeconds - existing.modified >= STALE_PART_SECONDS) {
                fs.remove(part)
                break
            }
            busy += final
        }
        var created = false
        try {
            val out = fs.createExclusive(part)
            created = true
            out.use { copy(input, it, size, phoneIsInput = true, onProgress) }
            try {
                fs.rename(part, childOf(dir, final))
            } catch (e: RemoteRefusal) {
                if (!fs.exists(childOf(dir, final))) throw e
                final = reserve()
                fs.rename(part, childOf(dir, final))
            }
        } catch (e: Throwable) {
            // Ours only. Blocking: it runs in a cancelled coroutine too, before the connection is closed.
            if (created) runCatching { fs.remove(part) }
            throw e
        }
        return final
    } finally {
        held.forEach { names.release(scope, dir, it) }
    }
}

/**
 * The transfers of a screen, so its connection is closed only after each
 * one, cancelled, has removed its part file.
 */
class TransferJobs {
    private val jobs = mutableSetOf<Job>()

    /** Tracks [job] (started lazily, so it cannot finish before it is tracked) and starts it. */
    fun track(job: Job): Job {
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs -= job } }
        job.start()
        return job
    }

    suspend fun cancelAndJoin() {
        val running = synchronized(jobs) { jobs.toList() }
        running.forEach { it.cancel() }
        running.joinAll()
    }

    companion object {
        /** For [track]: launch with this, so the job waits to be tracked. */
        val START = CoroutineStart.LAZY
    }
}

/** Copies with progress; a failure on the phone's side ([phoneIsInput] says which) comes as [PhoneSideException]. */
internal suspend fun copy(input: InputStream, out: OutputStream, total: Long, phoneIsInput: Boolean, onProgress: (Long, Long) -> Unit) {
    val buffer = ByteArray(BUFFER)
    var done = 0L
    var told = 0L
    onProgress(0, total)
    while (true) {
        currentCoroutineContext().ensureActive()
        val n = phoneSide(phoneIsInput) { input.read(buffer) }
        if (n < 0) break
        phoneSide(!phoneIsInput) { out.write(buffer, 0, n) }
        done += n
        if (done - told >= PROGRESS_STEP) {
            told = done
            onProgress(done, total)
        }
    }
    phoneSide(!phoneIsInput) { out.flush() }
    onProgress(done, total)
}

private inline fun <T> phoneSide(phone: Boolean, block: () -> T): T = try {
    block()
} catch (e: IOException) {
    throw if (phone && e !is PhoneSideException) PhoneSideException(e) else e
}

private const val BUFFER = 32 * 1024
private const val PROGRESS_STEP = 64 * 1024L
