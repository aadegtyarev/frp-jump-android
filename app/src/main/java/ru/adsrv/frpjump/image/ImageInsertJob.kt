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

/** What the progress bar shows: encoding has no figure, uploading has bytes sent of the total. */
sealed interface ImageInsertState {
    data object Encoding : ImageInsertState
    data class Uploading(val sent: Long, val total: Long) : ImageInsertState
}

sealed interface ImageInsertOutcome {
    /** The path went into the terminal. */
    data object Inserted : ImageInsertOutcome

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
 * Encodes an image, sends it to the device and pastes its path, without Enter. Cancelling the coroutine (leaving the
 * screen, Cancel, the tab going) ends it with the device's partial file removed when the connection allows; it throws
 * [CancellationException] then and inserts nothing. Closing what a blocking call is on (the picture's stream, the SFTP
 * channel, the channel being opened) is the only thing that interrupts it; the tab's connection and shell are never
 * touched. Every other throwable ends as a [ImageInsertOutcome.Failed], never as a crash.
 */
class ImageInsertJob(
    private val target: ImageTarget,
    /** Reads and encodes the picture; blocking. [track] takes what to close to end a blocked read. Throws [ImageFailureException]. */
    private val encode: (track: (Closeable) -> Unit) -> EncodedImage,
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
    suspend fun run(): ImageInsertOutcome = try {
        runChecked()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "image insert failed: ${e.javaClass.simpleName}")
        ImageInsertOutcome.Failed(ImageFailure.SAVE_FAILED)
    }

    private suspend fun runChecked(): ImageInsertOutcome {
        // The tab may have gone (closed with its Undo pending) between the owner's tap, the picker and now.
        if (!target.stillCurrent()) return ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST)
        onState(ImageInsertState.Encoding)
        val reading = Closer(closeWith)
        val image = try {
            blockingCancellable(onCancel = reading::cancel) { encode(reading::track) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageFailureException) {
            return ImageInsertOutcome.Failed(e.reason)
        } catch (e: OutOfMemoryError) {
            return ImageInsertOutcome.Failed(ImageFailure.TOO_LARGE)
        } catch (e: Exception) {
            return ImageInsertOutcome.Failed(ImageFailure.UNREADABLE)
        } finally {
            reading.close()
        }
        if (!target.stillCurrent()) return ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST)
        onState(ImageInsertState.Uploading(0, image.bytes.size.toLong()))
        val closer = Closer(closeWith)
        val created = Created()
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
        val path = try {
            blockingCancellable(onCancel = closer::cancel) { send(image, closer, created, opened) }
        } catch (e: CancellationException) {
            // Cancelled: the partial file goes, the cancellation continues.
            removeCreated(created)
            throw e
        } catch (e: Throwable) {
            removeCreated(created)
            return ImageInsertOutcome.Failed(reasonOf(e, opened.get()))
        } finally {
            // Not waited for: the tab's guard is released when the job ends, not when a channel has finished closing.
            closer.close()
        }
        // From here the file is complete; only a cancel still removes it (nothing was inserted), a refused insert keeps it.
        try {
            currentCoroutineContext().ensureActive()
            if (!target.stillCurrent()) return ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST)
            val inserted = try {
                withContext(insertOn) { target.paste(path, enter = false).also { if (it) created.done() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return ImageInsertOutcome.Failed(ImageFailure.INSERT_REFUSED)
            }
            return if (inserted) ImageInsertOutcome.Inserted else ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST)
        } catch (e: CancellationException) {
            removeCreated(created)
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

    /** Everything that blocks, on one channel; returns the absolute path of the new file. */
    private fun send(image: EncodedImage, closer: Closer, created: Created, opened: java.util.concurrent.atomic.AtomicBoolean): String {
        val store = target.open(closer::track)
        closer.set(store)
        opened.set(true)
        val dir = store.prepare()
        val total = image.bytes.size.toLong()
        var name: String? = null
        for (attempt in 0 until ImageLimits.NAME_TRIES) {
            val candidate = imageName(now(), randomSuffix(random), image.format)
            try {
                store.create(dir, candidate, image.bytes, onCreated = { created.set(dir, candidate) }, onProgress = { onState(ImageInsertState.Uploading(it, total)) })
                name = candidate
                break
            } catch (e: NameTaken) {
                continue
            }
        }
        if (name == null) throw ImageFailureException(ImageFailure.SAVE_FAILED)
        // The channel goes to the cleanup, which runs on a thread of its own and closes it: the path does not wait for it.
        closer.release(store)
        cleanupRunner(Runnable { cleanUp(store, dir, name) })
        return "$dir/$name"
    }

    /**
     * Best effort, after the file is written, on a thread of its own, and always ends with [store] closed. The deadline of
     * [cleanupMs] is checked between the steps, and a watchdog closes the channel at the deadline if one request hangs
     * (the close itself runs on another thread, so the shared watchdog thread is never held). A request that hangs ends
     * by sshj's 10 s request timeout at the latest. A failure here never reaches the owner.
     */
    private fun cleanUp(store: RemoteImages, dir: String, keep: String) {
        val deadline = System.nanoTime() + cleanupMs * 1_000_000
        fun left() = System.nanoTime() < deadline
        val watchdog = WATCHDOG.schedule({ closeWith(store) }, cleanupMs, TimeUnit.MILLISECONDS)
        try {
            if (!left()) return
            val reference = store.modified(dir, keep)
            if (!left()) return
            val entries = store.list(dir)
            for (old in expiredImages(entries, reference, keep)) {
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
     * Removes the file this job made, if any, on a channel of its own (the first may be closed): best effort, and bounded
     * by [removalMs] including the opening of that channel. The work (open, remove, close) runs on a helper thread that the
     * job abandons at the bound, so the bound holds whatever the device or sshj are waiting for; the helper ends when its
     * blocked call does (the opening's own limits, a request's 10 s timeout) and closes its channel itself.
     */
    private suspend fun removeCreated(created: Created) {
        val (dir, name) = created.pending() ?: return
        withContext(NonCancellable + Dispatchers.IO) {
            val closer = Closer(closeWith)
            val finished = CountDownLatch(1)
            BackgroundClose.run(Runnable {
                try {
                    val store = target.open(closer::track)
                    closer.set(store)
                    store.remove(dir, name)
                } catch (e: Throwable) {
                    Log.w(TAG, "partial image not removed: ${e.javaClass.simpleName}")
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

    /** The one file this job created, until it is complete. */
    private class Created {
        @Volatile private var pending: Pair<String, String>? = null

        fun set(dir: String, name: String) { pending = dir to name }
        fun done() { pending = null }
        fun pending() = pending
    }

    companion object {
        private const val TAG = "ImageInsert"
        const val REMOVAL_MS = 5_000L
        const val CLEANUP_MS = 15_000L
        private val WATCHDOG = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "image-cleanup").apply { isDaemon = true } }
    }
}
