package ru.adsrv.frpjump.image

import com.hierynomus.sshj.sftp.RemoteResourceSelector
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionFactory
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPEngine
import net.schmizz.sshj.sftp.SFTPException
import java.io.Closeable
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** That name is taken: nothing was written; try another. */
class NameTaken : IOException("name taken")

/**
 * The device's images folder over one SFTP channel (see [SftpRemoteImages]); a fake in tests. Calls block. Failures are
 * [ImageFailureException] with the reason known, [NameTaken], or another [IOException] for a lost connection.
 */
interface RemoteImages : Closeable {
    /** Makes sure `~/.cache/frp-jump/images` exists and is safe; returns its absolute path. */
    fun prepare(): String

    /**
     * Creates [name] in [dir], never over anything, for the owner only, and writes [bytes]. [onCreated] is called once the
     * file exists (before any byte is written); [onProgress] with the bytes sent so far.
     */
    fun create(dir: String, name: String, bytes: ByteArray, onCreated: () -> Unit, onProgress: (Long) -> Unit)

    /** The device's own modification time of [name] in [dir], in seconds; 0 when the device gives none. */
    fun modified(dir: String, name: String): Long

    /** What [dir] holds, `lstat`-like (a link is a link); reading stops after [limit] entries. */
    fun list(dir: String, limit: Int = ImageLimits.CLEANUP_ENTRIES): List<ImageEntry>

    /** Deletes [name] from [dir]; only the app's own names. */
    fun remove(dir: String, name: String)
}

/**
 * [RemoteImages] on a new SFTP channel of an already logged-in [SSHClient]: no new login, and closing it closes only
 * that channel. [requestTimeoutMs] is sshj's wait for each request's answer, not a deadline for the whole upload.
 */
class SftpRemoteImages private constructor(private val sftp: SFTPClient) : RemoteImages {
    companion object {
        const val REQUEST_TIMEOUT_MS = 10_000
        private const val CHUNK = 32 * 1024
        private const val WRITE_AHEAD = 16
        private const val PROGRESS_MS = 100L
        private const val DIR_MODE = 0b111_000_000
        private const val FILE_MODE = 0b110_000_000
        private const val GROUP_OTHER = 0b000_111_111
        private const val PERMISSION_BITS = 0xFFF

        /**
         * What the device's own SFTP status means for the owner: permission denied only for PERMISSION_DENIED (write
         * protect and everything else is "could not save"), no space only when it says so, a clash as [NameTaken], a lost
         * connection as the exception itself.
         */
        internal fun mapStatus(code: Response.StatusCode, e: SFTPException): Exception = when (code) {
            Response.StatusCode.NO_CONNECTION, Response.StatusCode.CONNECITON_LOST -> e
            Response.StatusCode.NO_SPACE_ON_FILESYSTEM, Response.StatusCode.QUOTA_EXCEEDED -> ImageFailureException(ImageFailure.NO_SPACE, e)
            Response.StatusCode.FILE_ALREADY_EXISTS -> NameTaken()
            Response.StatusCode.PERMISSION_DENIED -> ImageFailureException(ImageFailure.PERMISSION_DENIED, e)
            else -> ImageFailureException(ImageFailure.SAVE_FAILED, e)
        }

        /**
         * The threads that open channels, at most [MAX_HELPERS] at a time, live and abandoned together. A helper abandoned
         * by the bound or by Cancel ends when its blocked call does: its thread is interrupted once its channel exists,
         * and sshj's own limits (30 s for a channel open or a subsystem request) end the rest.
         */
        private val HELPERS = ThreadPoolExecutor(0, MAX_HELPERS, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
            Thread(r, "image-sftp-open").apply { isDaemon = true }
        }
        private const val MAX_HELPERS = 6

        /** Abandoned helpers still running, per connection: a stalled tab cannot use up the global cap for the others. */
        private const val MAX_ABANDONED_PER_CLIENT = 2
        private val ABANDONED = java.util.WeakHashMap<Any, AtomicInteger>()

        private fun abandonedOf(factory: Any): AtomicInteger = synchronized(ABANDONED) { ABANDONED.getOrPut(factory) { AtomicInteger() } }

        /** Helper threads running now (live or abandoned); for tests. */
        internal fun helpersRunning(): Int = HELPERS.activeCount

        /** Helpers ever submitted; for tests. */
        internal val helpersSubmitted = AtomicInteger()

        /**
         * Opens the channel and starts SFTP on it, waiting at most [requestTimeoutMs] in all, and ending at once on Cancel.
         * sshj does not time the SFTP version exchange at all (it reads the answer without a timeout), waits for a channel
         * open and for the subsystem request up to the connection's own 30 s, and only times the SFTP requests that
         * follow. So the work runs on a helper thread and the caller only waits for it: on the bound or on Cancel the
         * helper is abandoned, and whatever it makes later (a channel, an engine) is closed as soon as it exists. Nothing
         * ever marks a channel's input stream finished from our side (a reply arriving after that would make sshj drop
         * the whole connection, the shell included): a channel is closed by sending CLOSE, and the server's CLOSE ends
         * reads blocked on it. [track] is given what to call on Cancel. Throws [ImageFailureException] NOT_AVAILABLE when
         * the connection is up but SFTP could not be started on it (refused, no answer in time, garbage in the channel, too
         * many abandoned openings on this connection), an [IOException] when the connection is gone or the opening was
         * cancelled. A channel sshj itself confirms after its own 30 s open timeout can stay open (rare).
         */
        fun open(client: SSHClient, requestTimeoutMs: Int = REQUEST_TIMEOUT_MS, track: (Closeable) -> Unit = {}): SftpRemoteImages =
            open(client, { client.isConnected }, requestTimeoutMs, track)

        /** [open] on any [factory] of session channels; [connected] says whether the connection is up. [beforeSettle] is a test seam. */
        internal fun open(
            factory: SessionFactory,
            connected: () -> Boolean,
            requestTimeoutMs: Int,
            track: (Closeable) -> Unit,
            beforeSettle: () -> Unit = {},
        ): SftpRemoteImages {
            val opening = Opening(factory, connected, requestTimeoutMs, beforeSettle)
            track(opening)
            return opening.run()
        }
    }

    /** One opening: the helper's work, the caller's wait, and who closes what when it is abandoned. */
    private class Opening(
        private val factory: SessionFactory,
        private val connected: () -> Boolean,
        private val timeoutMs: Int,
        private val beforeSettle: () -> Unit,
    ) : Closeable {
        private val lock = Any()
        private val sessions = ArrayList<Session>()
        private var registered = false
        private var engine: SFTPEngine? = null
        private var helper: Thread? = null
        private var submitted = false
        private var helperEnded = false
        private var counted = false
        private var abandoned = false
        private var cancelled = false
        private var settled = false
        private var finished = false
        private var result: SftpRemoteImages? = null
        private var failure: Throwable? = null
        private val done = CountDownLatch(1)

        /** Under [lock]: nobody will use this opening any more; if its helper still runs it counts against its connection. */
        private fun markAbandoned() {
            abandoned = true
            if (submitted && !helperEnded && !counted) {
                counted = true
                abandonedOf(factory).incrementAndGet()
            }
        }

        fun run(): SftpRemoteImages {
            // Cancelled before it began: nothing is submitted.
            synchronized(lock) { if (abandoned) throw IOException("cancelled") }
            if (abandonedOf(factory).get() >= MAX_ABANDONED_PER_CLIENT) throw ImageFailureException(ImageFailure.NOT_AVAILABLE)
            synchronized(lock) { submitted = true }
            helpersSubmitted.incrementAndGet()
            try {
                HELPERS.execute { work() }
            } catch (e: RejectedExecutionException) {
                throw ImageFailureException(ImageFailure.NOT_AVAILABLE, e)
            }
            done.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            beforeSettle()
            val problem: Throwable? = synchronized(lock) {
                when {
                    // A Cancel that came first wins: what the helper made was closed by it, and is not handed over.
                    cancelled -> { markAbandoned(); null }
                    finished && failure == null -> {
                        // Handed over: from here the owner closes the store, and Cancel no longer reaches this opening.
                        settled = true
                        return result!!
                    }
                    finished -> failure
                    else -> { markAbandoned(); null }
                }
            }
            closeResources()
            val byOwner = synchronized(lock) { cancelled }
            if (problem is ImageFailureException) throw problem
            if (byOwner || !connected()) throw if (problem is IOException) problem else IOException(if (byOwner) "cancelled" else "not connected")
            throw ImageFailureException(ImageFailure.NOT_AVAILABLE, problem)
        }

        /** Cancel: ends the caller's wait at once and abandons the helper; no effect once the store was handed over. */
        override fun close() {
            synchronized(lock) {
                if (settled) return
                cancelled = true
                markAbandoned()
            }
            done.countDown()
            closeResources()
        }

        private fun register(session: Session): Session {
            val late = synchronized(lock) { if (abandoned) true else { sessions += session; registered = true; false } }
            if (late) {
                BackgroundClose.close(session)
                throw IOException("abandoned")
            }
            return session
        }

        private fun stopIfAbandoned() {
            synchronized(lock) { if (abandoned) throw IOException("abandoned") }
        }

        private fun work() {
            synchronized(lock) { helper = Thread.currentThread() }
            try {
                stopIfAbandoned()
                val wrapped = object : SessionFactory by factory {
                    override fun startSession(): Session = register(factory.startSession())
                }
                val engine = SFTPEngine(wrapped)
                synchronized(lock) { this.engine = engine }
                stopIfAbandoned()
                engine.timeoutMs = timeoutMs
                engine.init()
                val store = SftpRemoteImages(SFTPClient(engine))
                val late = synchronized(lock) { if (abandoned) true else { result = store; finished = true; false } }
                if (late) closeResources()
            } catch (t: Throwable) {
                // Any throwable, an Error too (sshj allocates what a garbage packet length says): the channels still close.
                synchronized(lock) { if (!abandoned) { failure = t; finished = true } }
                closeResources()
            } finally {
                synchronized(lock) {
                    helperEnded = true
                    helper = null
                    if (counted) { counted = false; abandonedOf(factory).decrementAndGet() }
                }
                // An interrupt that arrived as the work ended must not reach the next task of this pooled thread.
                Thread.interrupted()
                done.countDown()
            }
        }

        /**
         * Closes what the opening made, by sending CLOSE on a thread of its own: never by ending a channel's input
         * stream here, because a reply that arrives after that kills the whole connection. A helper blocked inside sshj is
         * interrupted, but only our own helper, only once its channel exists, and never from itself.
         */
        private fun closeResources() {
            val (all, eng) = synchronized(lock) {
                val s = sessions.toList().also { sessions.clear() }
                val e = engine.also { engine = null }
                val h = helper?.takeIf { registered && !helperEnded && it !== Thread.currentThread() }
                // Interrupted under the lock, so the helper cannot finish and take another task in between.
                h?.interrupt()
                s to e
            }
            all.forEach { BackgroundClose.close(it) }
            eng?.let { BackgroundClose.close(it) }
        }
    }

    override fun prepare(): String = mapped {
        val real = sftp.canonicalize(".")
        // A home of "/" gives "/.cache", not "//.cache".
        val home = real.trimEnd('/')
        val dir = "$home/.cache/frp-jump/images"
        if (!isSafeRemotePath(dir)) throw ImageFailureException(ImageFailure.PATH_UNSAFE)
        val owner = uidOf(sftp.stat(real)) ?: throw ImageFailureException(ImageFailure.FOLDER_UNSAFE)
        // ~/.cache is the owner's and may be a link to somewhere else; what is the app's own is not allowed to be one.
        val cache = "$home/.cache"
        if (!exists(cache)) makeDir(cache) else if (sftp.stat(cache).type != FileMode.Type.DIRECTORY) throw ImageFailureException(ImageFailure.FOLDER_UNSAFE)
        ownDir("$cache/frp-jump", owner)
        ownDir(dir, owner)
        val attrs = sftp.lstat(dir)
        if (permissionsOf(attrs) and GROUP_OTHER != 0) sftp.setattr(dir, FileAttributes.Builder().withPermissions(DIR_MODE).build())
        dir
    }

    override fun create(dir: String, name: String, bytes: ByteArray, onCreated: () -> Unit, onProgress: (Long) -> Unit) {
        require(OWN_IMAGE_NAME.matches(name))
        val path = "$dir/$name"
        val file = try {
            mapped {
                sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), FileAttributes.Builder().withPermissions(FILE_MODE).build())
            }
        } catch (e: ImageFailureException) {
            // OpenSSH answers a clash with a plain failure, not "already exists".
            if (e.reason == ImageFailure.SAVE_FAILED && exists(path)) throw NameTaken()
            throw e
        }
        try {
            onCreated()
            mapped {
                val out = file.RemoteFileOutputStream(0, WRITE_AHEAD)
                var sent = 0
                var reported = 0L
                while (sent < bytes.size) {
                    val n = minOf(CHUNK, bytes.size - sent)
                    out.write(bytes, sent, n)
                    sent += n
                    val now = System.nanoTime() / 1_000_000
                    if (sent == bytes.size || now - reported >= PROGRESS_MS) {
                        reported = now
                        onProgress(sent.toLong())
                    }
                }
                out.close()
            }
        } finally {
            runCatching { file.close() }
        }
        // A server that ignored the mode asked at create time: tighten it before the path goes anywhere.
        if (permissionsOf(sftp.lstat(path)) and GROUP_OTHER != 0) sftp.setattr(path, FileAttributes.Builder().withPermissions(FILE_MODE).build())
    }

    override fun modified(dir: String, name: String): Long = mapped { mtimeOf(sftp.lstat("$dir/$name")) }

    override fun list(dir: String, limit: Int): List<ImageEntry> = mapped {
        var seen = 0
        // READDIR answers in batches: BREAK stops asking for more once enough entries are in.
        val selector = RemoteResourceSelector { info ->
            when {
                info.name == "." || info.name == ".." -> RemoteResourceSelector.Result.CONTINUE
                seen++ >= limit -> RemoteResourceSelector.Result.BREAK
                else -> RemoteResourceSelector.Result.ACCEPT
            }
        }
        sftp.ls(dir, selector).map { ImageEntry(it.name, it.attributes.type == FileMode.Type.REGULAR, mtimeOf(it.attributes)) }
    }

    override fun remove(dir: String, name: String) {
        require(OWN_IMAGE_NAME.matches(name))
        mapped { sftp.rm("$dir/$name") }
    }

    override fun close() {
        // CLOSE only: ending the channel's input stream ourselves would make a reply that is still on its way (a write
        // answer, the VERSION) kill the whole connection. The server's CLOSE ends requests blocked on the channel.
        runCatching { sftp.close() }
    }

    private fun exists(path: String): Boolean = try {
        sftp.lstat(path)
        true
    } catch (e: SFTPException) {
        if (e.statusCode == Response.StatusCode.NO_SUCH_FILE || e.statusCode == Response.StatusCode.NO_SUCH_PATH) false else throw e
    }

    private fun makeDir(path: String) {
        try {
            sftp.sftpEngine.makeDir(path, FileAttributes.Builder().withPermissions(DIR_MODE).build())
        } catch (e: SFTPException) {
            // Made meanwhile (another tab, another phone): fine if it is what it should be; checked by the caller's next look.
            if (!exists(path)) throw e
        }
    }

    /** [path] is a real folder of [owner]: made if absent; a link, a file or another owner's folder is refused. */
    private fun ownDir(path: String, owner: Long) {
        if (!exists(path)) makeDir(path)
        val attrs = sftp.lstat(path)
        if (!isOwnFolder(attrs.type == FileMode.Type.DIRECTORY, uidOf(attrs), owner)) throw ImageFailureException(ImageFailure.FOLDER_UNSAFE)
    }

    /** The modification time, or 0 when the device sent none (then the file is never expired). */
    private fun mtimeOf(a: FileAttributes): Long = if (a.has(FileAttributes.Flag.ACMODTIME)) a.mtime else 0

    private fun uidOf(a: FileAttributes): Long? = if (a.has(FileAttributes.Flag.UIDGID)) a.uid.toLong() and 0xFFFFFFFFL else null

    private fun permissionsOf(a: FileAttributes): Int = a.mode?.takeIf { a.has(FileAttributes.Flag.MODE) }?.let { it.mask and PERMISSION_BITS } ?: 0

    /** sshj's SFTP refusals as the reasons the owner is told; anything else stays an [IOException] (the connection). */
    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: SFTPException) {
        // An answer that never came (sshj wraps its timeout) is not the device saying no: it stays an IOException, and the job
        // says "connection lost" only if the connection is gone, otherwise "could not save" (or "not available" while opening).
        if (e.cause != null) throw e
        throw mapStatus(e.statusCode, e)
    }
}
