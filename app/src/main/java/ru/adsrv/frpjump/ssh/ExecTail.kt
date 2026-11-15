package ru.adsrv.frpjump.ssh

import net.schmizz.sshj.SSHClient
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Runs [command] on a new exec channel of the already open, logged-in [ssh] (never a new connection) and returns the LAST [maxBytes]
 * bytes of its standard output as UTF-8: what a screen read wants is the newest text. Throws [IOException] when the command exits
 * with a non-zero status. Blocking, and never to be interrupted: opening a channel interrupted half way leaves it open on the
 * connection. The caller bounds the time with [lease] instead ([ExecLease.abandon] closes whatever channel exists, now or later).
 */
fun execOutputTail(ssh: SSHClient, command: String, maxBytes: Int, lease: ExecLease): String =
    runLeased(lease, { ssh.startSession() }) { s ->
        val exec = s.exec(command)
        val out = readTail(exec.inputStream, maxBytes)
        exec.join(EXIT_WAIT_S, TimeUnit.SECONDS)
        val status = exec.exitStatus
        if (status != null && status != 0) throw IOException("exec failed")
        String(out, Charsets.UTF_8)
    }

/**
 * Opens a resource with [open] and runs [block] on it, which closes it at the end. The resource is registered with [lease] at once:
 * when the caller gave up meanwhile ([ExecLease.abandon]) the resource is closed in the background as soon as it exists and
 * [IOException] is thrown instead of running [block]. Nothing here is ever interrupted.
 */
fun <S : Closeable, T> runLeased(lease: ExecLease, open: () -> S, block: (S) -> T): T {
    val resource = open()
    if (!lease.attach(resource)) throw IOException("abandoned")
    try {
        return block(resource)
    } finally {
        lease.detach(resource)
        runCatching { resource.close() }
    }
}

/**
 * What a caller that stops waiting for a worker still owns: the worker's channel. [abandon] closes it, or, when the worker has not
 * got one yet, makes [attach] close it on arrival. Every close by the lease runs off the calling thread ([closer]): closing a channel
 * can wait for the connection's timeout, and the caller must return at its own deadline.
 */
class ExecLease(private val closer: (Closeable) -> Unit = ::closeInBackground) {
    private var abandoned = false
    private var held: Closeable? = null

    /** The worker's resource; false (it is being closed) when the caller already gave up. */
    fun attach(resource: Closeable): Boolean {
        synchronized(this) {
            if (!abandoned) { held = resource; return true }
        }
        closer(resource)
        return false
    }

    fun detach(resource: Closeable) = synchronized(this) { if (held === resource) held = null }

    /** The caller stops waiting. Safe to call more than once and after the worker finished. */
    fun abandon() {
        val resource = synchronized(this) { abandoned = true; held.also { held = null } }
        if (resource != null) closer(resource)
    }
}

/** Closes [resource] on a thread of its own and returns at once. */
fun closeInBackground(resource: Closeable) {
    Thread({ runCatching { resource.close() } }, "exec-close").apply { isDaemon = true }.start()
}

/** The last [max] bytes of [input] up to its end; the rest is read and dropped, never kept. */
fun readTail(input: InputStream, max: Int): ByteArray {
    require(max > 0)
    val ring = ByteArray(max)
    var total = 0L
    val chunk = ByteArray(minOf(max, 8192))
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        for (i in 0 until n) ring[((total + i) % max).toInt()] = chunk[i]
        total += n
    }
    if (total <= max) return ring.copyOf(total.toInt())
    val start = (total % max).toInt()
    return ring.copyOfRange(start, max) + ring.copyOfRange(0, start)
}

private const val EXIT_WAIT_S = 2L
