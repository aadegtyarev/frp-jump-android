package ru.adsrv.frpjump.files

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import ru.adsrv.frpjump.ssh.SshConnector
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * The file screen's own SSH connection with an SFTP channel, apart from
 * any terminal: [open] logs in through [connector] (the terminal's pinning
 * and login); [fs] is the device's file system over it, for
 * [readFolder], [downloadInto] and [uploadInto] on the IO dispatcher.
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

    val fs: RemoteFs = SftpFs { sftp ?: throw IOException("not connected") }

    /**
     * [command] on an exec channel of this connection, next to SFTP.
     * Throws [CommandsRefused] when the device allows no commands here.
     * Blocking: an IO thread.
     */
    fun exec(command: String): RemoteCommand {
        val client = ssh ?: throw IOException("not connected")
        val session = try {
            client.startSession()
        } catch (e: ConnectionException) {
            throw if (client.isConnected) CommandsRefused(e) else e
        }
        val running = try {
            session.exec(command)
        } catch (e: Exception) {
            runCatching { session.close() }
            throw if (e is ConnectionException && client.isConnected) CommandsRefused(e) else e
        }
        return object : RemoteCommand {
            override val output = running.inputStream
            override val errors = running.errorStream

            override fun closeInput() {
                runCatching { running.outputStream.close() }
            }

            override fun exitStatus(ms: Long): Int? {
                runCatching { running.join(ms, TimeUnit.MILLISECONDS) }
                return running.exitStatus
            }

            override fun close() {
                runCatching { session.close() }
            }
        }
    }

    override fun close() {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        sftp = null
        ssh = null
    }
}

/** [RemoteFs] over sshj's SFTP client; SFTP status codes become [RemoteRefusal]s. */
private class SftpFs(private val client: () -> SFTPClient) : RemoteFs {
    override fun canonicalize(path: String): String = call { client().canonicalize(path) }

    override fun list(path: String): List<Pair<String, RemoteAttrs>> = call {
        client().ls(path).filter { it.name != "." && it.name != ".." }.map { it.name to attrs(it.attributes) }
    }

    override fun stat(path: String): RemoteAttrs = call { attrs(client().stat(path)) }

    override fun lstat(path: String): RemoteAttrs = call { attrs(client().lstat(path)) }

    override fun readlink(path: String): String = call { client().readlink(path) }

    override fun openRead(path: String): RemoteRead = call {
        val file = client().open(path, EnumSet.of(OpenMode.READ))
        val size = runCatching { file.length() }.getOrDefault(0L)
        val stream = file.ReadAheadRemoteFileInputStream(READ_AHEAD)
        RemoteRead(
            object : FilterInputStream(stream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = call { super.read(b, off, len) }

                override fun close() = closeBoth(file) { super.close() }
            },
            size,
        )
    }

    override fun createExclusive(path: String): OutputStream = call {
        val file = client().open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL))
        val stream = file.RemoteFileOutputStream(0, WRITE_AHEAD)
        object : FilterOutputStream(stream) {
            override fun write(b: ByteArray, off: Int, len: Int) = call { stream.write(b, off, len) }

            override fun flush() = call { stream.flush() }

            override fun close() = closeBoth(file) { stream.close() }
        }
    }

    override fun rename(from: String, to: String) = call { client().rename(from, to) }

    override fun remove(path: String) = call { client().rm(path) }

    private fun closeBoth(file: RemoteFile, stream: () -> Unit) {
        try {
            call(stream)
        } finally {
            runCatching { file.close() }
        }
    }

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: SFTPException) {
        throw refusalOf(e) ?: e
    }

    private fun refusalOf(e: SFTPException): RemoteRefusal? {
        val reason = when (e.statusCode) {
            Response.StatusCode.NO_CONNECTION, Response.StatusCode.CONNECITON_LOST -> return null
            Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH -> Refusal.NO_SUCH
            Response.StatusCode.PERMISSION_DENIED, Response.StatusCode.WRITE_PROTECT -> Refusal.PERMISSION
            Response.StatusCode.NO_SPACE_ON_FILESYSTEM, Response.StatusCode.QUOTA_EXCEEDED -> Refusal.NO_SPACE
            Response.StatusCode.FILE_ALREADY_EXISTS -> Refusal.EXISTS
            Response.StatusCode.LINK_LOOP -> Refusal.LOOP
            else -> Refusal.OTHER
        }
        return RemoteRefusal(reason, e.message?.takeIf { it.isNotBlank() })
    }

    private fun attrs(a: FileAttributes) = RemoteAttrs(
        kind = when (a.type) {
            FileMode.Type.DIRECTORY -> NodeKind.DIR
            FileMode.Type.REGULAR -> NodeKind.FILE
            FileMode.Type.SYMLINK -> NodeKind.LINK
            else -> NodeKind.OTHER
        },
        size = a.size,
        modified = a.mtime,
        mode = a.mode?.takeIf { a.has(FileAttributes.Flag.MODE) }?.let { it.mask and PERMISSION_BITS },
        // SFTP sends them as unsigned 32-bit numbers: nobody (4294967294) must not show as -2.
        uid = if (a.has(FileAttributes.Flag.UIDGID)) a.uid.toLong() and UNSIGNED else null,
        gid = if (a.has(FileAttributes.Flag.UIDGID)) a.gid.toLong() and UNSIGNED else null,
    )

    private companion object {
        const val READ_AHEAD = 16
        const val WRITE_AHEAD = 16

        /** rwx for all three, setuid, setgid and sticky. */
        const val PERMISSION_BITS = 0xFFF
        const val UNSIGNED = 0xFFFFFFFFL
    }
}
