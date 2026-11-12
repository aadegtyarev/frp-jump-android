package ru.adsrv.frpjump.image

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.agent.blockingCancellable
import java.io.Closeable
import java.io.IOException
import java.time.LocalDateTime
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What the progress bar shows: encoding has no figure, uploading has bytes sent of the total. A batch of several images
 * says which one it is on: [index] of [count] (1-based); a single image keeps count 1 and its own plain words.
 */
sealed interface ImageInsertState {
    /** One image, or the part of a batch before the first image is being read. */
    data object Encoding : ImageInsertState

    /** Image [index] of a batch of [count] (more than one) is being read and encoded. */
    data class Preparing(val index: Int, val count: Int) : ImageInsertState
    data class Uploading(val sent: Long, val total: Long, val index: Int = 1, val count: Int = 1) : ImageInsertState
}

/**
 * What one paste carries for [paths]: the absolute paths joined by single spaces, never by a newline (Claude Code makes
 * "[Image #1] [Image #2]" of space-joined paths in one paste, and keeps newline-separated ones as text). A batch is one
 * paste of this, in the order the images were given.
 */
fun joinImagePaths(paths: List<String>): String = paths.joinToString(" ")

sealed interface ImageInsertOutcome {
    /** The path (of a batch: every path, in one paste) went into the terminal. */
    data object Inserted : ImageInsertOutcome

    /**
     * Some images of a batch went into the terminal (one paste) and [notSent] others did not, [firstReason] being why the
     * first of those failed. Only ever for a batch in which at least one image was inserted.
     */
    data class InsertedPartly(val inserted: Int, val notSent: Int, val firstReason: ImageFailure) : ImageInsertOutcome

    /** Nothing was inserted; [reason] says why. */
    data class Failed(val reason: ImageFailure) : ImageInsertOutcome
}

/**
 * Where an image goes: one terminal tab, as it was when the owner asked. Never another tab, and never the same tab's
 * next connection.
 */
interface ImageTarget {
    /**
     * A new SFTP channel on the tab's connection, with SFTP started on it (blocking, an IO thread). [track] is given what
     * to close to end the opening from another thread: whatever channel the opening has made is owned from the start.
     */
    fun open(track: (Closeable) -> Unit): RemoteImages

    /** The tab's connection and terminal are still the ones captured, and the tab is still one the owner sees. */
    fun stillCurrent(): Boolean

    /** The terminal's paste of [text] into the captured tab, [enter] being a following Enter. Main thread. */
    fun paste(text: String, enter: Boolean): Boolean
}

/** This target, but not current once [visible] is false (the tab closed with its Undo pending, replaced, hidden). */
fun ImageTarget.onlyWhile(visible: () -> Boolean): ImageTarget {
    val inner = this
    return object : ImageTarget by inner {
        override fun stillCurrent(): Boolean = inner.stillCurrent() && visible()
    }
}

/**
 * Encodes one image or a batch (at most [ImageLimits.BATCH_MAX]), sends them to the device and pastes their paths in one
 * paste, without Enter. A batch goes one image at a time over ONE SFTP channel: each is encoded, uploaded and released
 * before the next, so no more than one image is in memory. Cancelling the coroutine (leaving the screen, Cancel, the tab
 * going) ends it with the device's partial file and the complete files of this batch removed when the connection allows;
 * it throws [CancellationException] then and inserts nothing. Closing what a blocking call is on (the picture's stream, the
 * SFTP channel, the channel being opened) is the only thing that interrupts it; the tab's connection and shell are never
 * touched. Every other throwable ends as a [ImageInsertOutcome.Failed], never as a crash.
 *
 * Failures: an image that cannot be read is skipped; a failed upload ends the batch (the rest are not sent). The images
 * that went are pasted ([ImageInsertOutcome.InsertedPartly]); none at all is [ImageInsertOutcome.Failed] with the first
 * reason. A lost connection or a changed tab inserts nothing, whatever was already uploaded.
 */
class ImageInsertJob(
    private val target: ImageTarget,
    /**
     * One reader per picture, in the order to paste; each reads and encodes its picture (blocking). [track] takes what to
     * close to end a blocked read. Throws [ImageFailureException]. Only the first [ImageLimits.BATCH_MAX] are used.
     */
    sources: List<(track: (Closeable) -> Unit) -> EncodedImage>,
    private val onState: (ImageInsertState) -> Unit,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val random: Random = java.security.SecureRandom(),
    private val insertOn: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val removalMs: Long = REMOVAL_MS,
    private val cleanupMs: Long = CLEANUP_MS,
    /** Closes a channel or stream without waiting for it (closing an sshj channel can wait 30 s for the peer). */
    private val closeWith: (Closeable) -> Unit = BackgroundClose::close,
    /** Runs the age cleanup of the images folder on a thread of its own, so the path never waits for it. */
    private val cleanupRunner: (Runnable) -> Unit = BackgroundClose::run,
) {
    private val sources = sources.take(ImageLimits.BATCH_MAX)

    suspend fun run(): ImageInsertOutcome = try {
        runChecked()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "image insert failed: ${e.javaClass.simpleName}")
        ImageInsertOutcome.Failed(ImageFailure.SAVE_FAILED)
    }

    private suspend fun runChecked(): ImageInsertOutcome {
        if (sources.isEmpty()) return ImageInsertOutcome.Failed(ImageFailure.UNREADABLE)
        // The tab may have gone (closed with its Undo pending) between the owner's tap, the picker and now.
        if (!target.stillCurrent()) return ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST)
        val count = sources.size
        val closer = Closer(closeWith)
        val created = Created()
        val channel = Channel()
        val paths = ArrayList<String>()
        val failures = ArrayList<ImageFailure>()
        try {
            for ((i, source) in sources.withIndex()) {
                val number = i + 1
                if (i > 0 && !target.stillCurrent()) return ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST)
                onState(if (count > 1) ImageInsertState.Preparing(number, count) else ImageInsertState.Encoding)
                val reading = Closer(closeWith)
                val image = try {
                    blockingCancellable(onCancel = reading::cancel) { source(reading::track) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ImageFailureException) {
                    failures += e.reason
                    continue
                } catch (e: OutOfMemoryError) {
                    failures += ImageFailure.TOO_LARGE
                    continue
                } catch (e: Exception) {
                    failures += ImageFailure.UNREADABLE
                    continue
                } finally {
                    reading.close()
                }
                if (!target.stillCurrent()) return ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST)
                onState(ImageInsertState.Uploading(0, image.bytes.size.toLong(), number, count))
                try {
                    paths += blockingCancellable(onCancel = closer::cancel) { send(image, number, count, closer, created, channel) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    removeCreated(created, everything = false)
                    val reason = reasonOf(e, channel.store != null)
                    // The connection is gone or the tab changed: nothing is inserted (what is complete stays, cleaned by age).
                    if (reason == ImageFailure.CONNECTION_LOST) return ImageInsertOutcome.Failed(reason)
                    // The rest of the batch is not sent: the channel or the device is not to be trusted any further.
                    failures += reason
                    break
                }
                created.complete()
            }
            if (paths.isNotEmpty()) startCleanup(channel, closer)
        } catch (e: CancellationException) {
            // Cancelled: the part file and the complete files of this batch go, the cancellation continues.
            removeCreated(created, everything = true)
            throw e
        } finally {
            // Not waited for: the tab's guard is released when the job ends, not when a channel has finished closing.
            closer.close()
        }
        if (paths.isEmpty()) return ImageInsertOutcome.Failed(failures.firstOrNull() ?: ImageFailure.UNREADABLE)
        // From here the files are complete; only a cancel still removes them (nothing was inserted), a refused insert keeps them.
        try {
            currentCoroutineContext().ensureActive()
            if (!target.stillCurrent()) return ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST)
            val inserted = try {
                withContext(insertOn) { target.paste(joinImagePaths(paths), enter = false).also { if (it) created.done() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return ImageInsertOutcome.Failed(ImageFailure.INSERT_REFUSED)
            }
            return when {
                !inserted -> ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST)
                paths.size == count -> ImageInsertOutcome.Inserted
                else -> ImageInsertOutcome.InsertedPartly(paths.size, count - paths.size, failures.first())
            }
        } catch (e: CancellationException) {
            removeCreated(created, everything = true)
            throw e
        }
    }

    /**
     * What to tell the owner: a reason already known; the connection gone when it is (or the tab is no longer the one
     * asked); otherwise the device could not open its file transfer ([opened] false) or could not save the image.
     */
    private fun reasonOf(e: Throwable, opened: Boolean): ImageFailure = when {
        e is ImageFailureException -> e.reason
        !target.stillCurrent() -> ImageFailure.CONNECTION_LOST
        !opened -> ImageFailure.NOT_AVAILABLE
        else -> ImageFailure.SAVE_FAILED
    }

    /** The batch's one channel and its prepared folder; made by the first upload, used by the later ones. */
    private class Channel {
        @Volatile var store: RemoteImages? = null
        @Volatile var dir: String = ""
        @Volatile var lastName: String = ""
        val names = java.util.concurrent.CopyOnWriteArrayList<String>()
    }

    /** Everything that blocks for one image, on the batch's one channel; returns the absolute path of the new file. */
    private fun send(image: EncodedImage, number: Int, count: Int, closer: Closer, created: Created, channel: Channel): String {
        val store = channel.store ?: run {
            val opened = target.open(closer::track)
            closer.set(opened)
            channel.store = opened
            channel.dir = opened.prepare()
            opened
        }
        val dir = channel.dir
        val total = image.bytes.size.toLong()
        var name: String? = null
        for (attempt in 0 until ImageLimits.NAME_TRIES) {
            val candidate = imageName(now(), randomSuffix(random), image.format)
            try {
                store.create(dir, candidate, image.bytes, onCreated = { created.set(dir, candidate) }, onProgress = { onState(ImageInsertState.Uploading(it, total, number, count)) })
                name = candidate
                break
            } catch (e: NameTaken) {
                continue
            }
        }
        if (name == null) throw ImageFailureException(ImageFailure.SAVE_FAILED)
        channel.names += name
        channel.lastName = name
        return "$dir/$name"
    }

    /** Once per batch, when the last image has been written: the channel goes to the cleanup, which closes it. */
    private fun startCleanup(channel: Channel, closer: Closer) {
        val store = channel.store ?: return
        val dir = channel.dir
        val last = channel.lastName
        val keep = channel.names.toSet()
        // The channel goes to the cleanup, which runs on a thread of its own and closes it: the path does not wait for it.
        closer.release(store)
        cleanupRunner(Runnable { cleanUp(store, dir, last, keep) })
    }

    /**
     * Best effort, after the files are written, on a thread of its own, and always ends with [store] closed. The deadline of
     * [cleanupMs] is checked between the steps, and a watchdog closes the channel at the deadline if one request hangs
     * (the close itself runs on another thread, so the shared watchdog thread is never held). A request that hangs ends
     * by sshj's 10 s request timeout at the latest. A failure here never reaches the owner. [last] is the file whose own
     * modification time is the reference, [keep] every file of this batch.
     */
    private fun cleanUp(store: RemoteImages, dir: String, last: String, keep: Set<String>) {
        val deadline = System.nanoTime() + cleanupMs * 1_000_000
        fun left() = System.nanoTime() < deadline
        val watchdog = WATCHDOG.schedule({ closeWith(store) }, cleanupMs, TimeUnit.MILLISECONDS)
        try {
            if (!left()) return
            val reference = store.modified(dir, last)
            if (!left()) return
            val entries = store.list(dir)
            for (old in expiredImages(entries, reference, last).filter { it !in keep }) {
                if (!left()) break
                try {
                    store.remove(dir, old)
                } catch (e: Exception) {
                    // A refused delete is skipped; a lost connection ends the cleanup.
                    if (e is IOException && e !is NameTaken && e !is ImageFailureException) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "image cleanup failed: ${e.javaClass.simpleName}")
        } finally {
            watchdog.cancel(false)
            runCatching { store.close() }
        }
    }

    /**
     * Removes the files this job made, on a channel of its own (the first may be closed): the part file being written and,
     * when [everything] (a cancel: nothing was inserted), the complete files of the batch too. Best effort, and bounded by
     * [removalMs] for all of them together, including the opening of that channel. The work (open, remove each, close) runs
     * on a helper thread that the job abandons at the bound, so the bound holds whatever the device or sshj are waiting
     * for; the helper ends when its blocked call does (the opening's own limits, a request's 10 s timeout) and closes its
     * channel itself. Files not reached by the bound stay and go with the age cleanup of a later upload.
     */
    private suspend fun removeCreated(created: Created, everything: Boolean) {
        val victims = created.victims(everything)
        if (victims.isEmpty()) return
        withContext(NonCancellable + Dispatchers.IO) {
            val closer = Closer(closeWith)
            val finished = CountDownLatch(1)
            BackgroundClose.run(Runnable {
                try {
                    val store = target.open(closer::track)
                    closer.set(store)
                    for ((dir, name) in victims) {
                        try {
                            store.remove(dir, name)
                        } catch (e: Exception) {
                            Log.w(TAG, "image not removed: ${e.javaClass.simpleName}")
                            // A refused delete is skipped; a lost connection ends the removal.
                            if (e is IOException && e !is NameTaken && e !is ImageFailureException) break
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "images not removed: ${e.javaClass.simpleName}")
                } finally {
                    closer.close()
                    finished.countDown()
                }
            })
            if (!finished.await(removalMs, TimeUnit.MILLISECONDS)) closer.cancel()
        }
    }

    /** What a blocking call is on, closable from another thread even before it exists; every close is [closeWith]. */
    private class Closer(private val closeWith: (Closeable) -> Unit) {
        private val held = ArrayList<Closeable>()
        @Volatile private var cancelled = false

        fun track(c: Closeable) {
            val late = synchronized(held) { if (cancelled) true else { held += c; false } }
            if (late) closeWith(c)
        }

        fun set(opened: RemoteImages) = track(opened)

        /** [c] is somebody else's now (the cleanup's); this closer no longer closes it. */
        fun release(c: Closeable) {
            synchronized(held) { held.remove(c) }
        }

        fun cancel() = closeAll(cancelled = true)

        fun close() = closeAll(cancelled = false)

        private fun closeAll(cancelled: Boolean) {
            val all = synchronized(held) { if (cancelled) this.cancelled = true; held.toList().also { held.clear() } }
            all.forEach { closeWith(it) }
        }
    }

    /** The files this job created: the one being written ([pending]), and the complete ones until they are pasted. */
    private class Created {
        @Volatile private var pending: Pair<String, String>? = null
        private val complete = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

        fun set(dir: String, name: String) { pending = dir to name }

        /** The file being written is complete. */
        fun complete() { pending?.let { complete += it }; pending = null }

        /** Pasted: nothing is this job's to remove any more. */
        fun done() { pending = null; complete.clear() }

        fun victims(everything: Boolean): List<Pair<String, String>> = (if (everything) complete.toList() else emptyList()) + listOfNotNull(pending)
    }

    companion object {
        private const val TAG = "ImageInsert"
        const val REMOVAL_MS = 5_000L
        const val CLEANUP_MS = 15_000L
        private val WATCHDOG = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "image-cleanup").apply { isDaemon = true } }
    }
}
