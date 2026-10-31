package ru.adsrv.frpjump.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import ru.adsrv.frpjump.ssh.SshConnector
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/** Reading or writing the phone's copy failed, not the device's. */
class PhoneSideException(cause: IOException) : IOException(cause.message, cause)

/**
 * The file screen's own SSH connection with an SFTP channel, apart from
 * any terminal: [open] logs in through [connector] (the terminal's pinning
 * and login), the rest are blocking SFTP calls for the IO dispatcher.
 */
class SftpBrowser(val connector: SshConnector) : Closeable {
    @Volatile
    private var ssh: SSHClient? = null

    @Volatile
    private var sftp: SFTPClient? = null

    /**
     * Connects and logs in. Throws [ru.adsrv.frpjump.ssh.HostKeyRejected],
     * [net.schmizz.sshj.userauth.UserAuthException] or another exception;
     * nothing is left open then.
     */
    fun open() {
        val client = connector.newClient()
        ssh = client
        connector.connect(client)
        try {
            connector.authenticate(client)
            sftp = client.newSFTPClient()
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw e
        }
    }

    /** Whether the connection still stands (a dropped one is opened again). */
    val isOpen: Boolean get() = sftp != null && ssh?.isConnected == true && ssh?.isAuthenticated == true

    private fun client(): SFTPClient = sftp ?: throw IOException("not connected")

    /** The user's home folder: where `.` is. */
    fun home(): String = client().canonicalize(".")

    /** [path]'s entries, links with their targets; `.` and `..` left out. */
    fun list(path: String): List<RemoteEntry> {
        val c = client()
        return c.ls(path).filter { it.name != "." && it.name != ".." }.map { info ->
            val at = childOf(path, info.name)
            val attrs = info.attributes
            if (attrs.type == FileMode.Type.SYMLINK) {
                val target = runCatching { c.statExistence(at) }.getOrNull()
                val targetType = target?.let(::typeOf)
                val raw = runCatching { c.readlink(at) }.getOrDefault("")
                RemoteEntry(
                    name = info.name,
                    path = at,
                    type = linkRowType(targetType),
                    size = target?.size ?: 0,
                    modified = target?.mtime ?: attrs.mtime,
                    link = if (raw.isEmpty()) LinkTarget("", at, broken = targetType == null) else linkTarget(at, raw, targetType),
                )
            } else {
                RemoteEntry(info.name, at, typeOf(attrs), attrs.size, attrs.mtime)
            }
        }
    }

    /** Whether anything (a broken link too) has [path]. */
    fun exists(path: String): Boolean = try {
        client().lstat(path)
        true
    } catch (e: SFTPException) {
        if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) false else throw e
    }

    /** Copies the file at [path] to [out]; [onProgress] gets bytes done and the file's size (0 when unknown). */
    suspend fun download(path: String, out: OutputStream, onProgress: (Long, Long) -> Unit) {
        client().open(path, EnumSet.of(OpenMode.READ)).use { file ->
            val total = runCatching { file.length() }.getOrDefault(0L)
            file.ReadAheadRemoteFileInputStream(READ_AHEAD).use { input -> copy(input, out, total, phoneIsInput = false, onProgress) }
        }
    }

    /**
     * Uploads [input] into folder [dir] as [name], or `name (1).ext` when
     * that is taken. It is written to a hidden part file next to it and
     * renamed at the end, so a half upload never replaces anything; a failed
     * one removes its part file. Returns the name it got.
     */
    suspend fun upload(input: InputStream, total: Long, dir: String, name: String, onProgress: (Long, Long) -> Unit): String {
        val c = client()
        val final = keepBothName(name) { exists(childOf(dir, it)) }
        val part = childOf(dir, uploadPartName(final))
        try {
            c.open(part, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
                file.RemoteFileOutputStream(0, WRITE_AHEAD).use { out -> copy(input, out, total, phoneIsInput = true, onProgress) }
            }
            c.rename(part, childOf(dir, final))
        } catch (e: Throwable) {
            runCatching { c.rm(part) }
            throw e
        }
        return final
    }

    override fun close() {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        sftp = null
        ssh = null
    }

    /** Copies with progress; a failure on the phone's side ([phoneIsInput] says which) comes as [PhoneSideException]. */
    private suspend fun copy(input: InputStream, out: OutputStream, total: Long, phoneIsInput: Boolean, onProgress: (Long, Long) -> Unit) {
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
        throw if (phone) PhoneSideException(e) else e
    }

    private fun typeOf(attrs: FileAttributes): EntryType = when (attrs.type) {
        FileMode.Type.DIRECTORY -> EntryType.DIR
        FileMode.Type.REGULAR -> EntryType.FILE
        else -> EntryType.OTHER
    }

    private companion object {
        const val BUFFER = 32 * 1024
        const val READ_AHEAD = 16
        const val WRITE_AHEAD = 16
        const val PROGRESS_STEP = 64 * 1024L
    }
}
