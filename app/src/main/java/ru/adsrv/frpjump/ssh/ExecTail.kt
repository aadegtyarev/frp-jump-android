package ru.adsrv.frpjump.ssh

import net.schmizz.sshj.SSHClient
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Runs [command] on a new exec channel of the already open, logged-in [ssh] (never a new connection) and returns the LAST [maxBytes]
 * bytes of its standard output as UTF-8: what a screen read wants is the newest text. Throws [IOException] when the command exits
 * with a non-zero status. Blocking and interruptible: the caller bounds the time by interrupting the thread; the channel is closed
 * either way.
 */
fun execOutputTail(ssh: SSHClient, command: String, maxBytes: Int): String =
    ssh.startSession().use { s ->
        val exec = s.exec(command)
        val out = readTail(exec.inputStream, maxBytes)
        exec.join(EXIT_WAIT_S, TimeUnit.SECONDS)
        val status = exec.exitStatus
        if (status != null && status != 0) throw IOException("exec failed")
        String(out, Charsets.UTF_8)
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
