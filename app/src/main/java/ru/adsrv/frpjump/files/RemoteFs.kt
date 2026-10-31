package ru.adsrv.frpjump.files

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** What a file system entry is, as `lstat` says (a link is a link, not its target). */
enum class NodeKind { DIR, FILE, LINK, OTHER }

/** [mode] the permission bits (`07777`), [uid] and [gid] the owner's ids; null when the server leaves them out. */
data class RemoteAttrs(
    val kind: NodeKind,
    val size: Long = 0,
    val modified: Long = 0,
    val mode: Int? = null,
    val uid: Int? = null,
    val gid: Int? = null,
)

/** Why the device refused one operation; the connection is still usable after it. */
enum class Refusal { NO_SUCH, PERMISSION, NO_SPACE, EXISTS, LOOP, OTHER }

/** The device refused one operation for [reason]; any other [IOException] means the connection is gone. */
class RemoteRefusal(val reason: Refusal, message: String? = null) : IOException(message ?: reason.name)

/** A remote file open for reading and its size (0 when unknown). */
class RemoteRead(val input: InputStream, val size: Long)

/**
 * The few operations listings and transfers need of the device's file
 * system: SFTP in the app ([SftpBrowser]), a fake in tests. Calls block;
 * failures are [RemoteRefusal] or another [IOException].
 */
interface RemoteFs {
    /** The physical absolute path of [path], links resolved. */
    fun canonicalize(path: String): String

    /** A folder's entries by name, `lstat`-like; `.` and `..` left out. */
    fun list(path: String): List<Pair<String, RemoteAttrs>>

    /** Follows links. */
    fun stat(path: String): RemoteAttrs

    fun lstat(path: String): RemoteAttrs

    fun readlink(path: String): String

    fun openRead(path: String): RemoteRead

    /** A new file at [path]; refused when anything is there already. */
    fun createExclusive(path: String): OutputStream

    /** Refused when [to] exists (SFTP v3, as OpenSSH does it). */
    fun rename(from: String, to: String)

    fun remove(path: String)
}

/** Whether anything, a broken link too, is at [path]. */
fun RemoteFs.exists(path: String): Boolean = try {
    lstat(path)
    true
} catch (e: RemoteRefusal) {
    if (e.reason == Refusal.NO_SUCH) false else throw e
}
