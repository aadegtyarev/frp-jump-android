package com.termux.terminal

import java.io.IOException
import ru.adsrv.frpjump.settings.DEFAULT_TERMINAL_HISTORY_ROWS
import ru.adsrv.frpjump.ssh.diagnostics.TerminalMetrics
import ru.adsrv.frpjump.ssh.diagnostics.ObservationToken
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Drives a Termux [TerminalSession] from a remote byte stream (an SSH shell)
 * instead of a local pty.
 *
 * [TerminalSession] is final and starts a local process on its first resize
 * unless it already has an emulator, so this gives it one up front and feeds
 * the session's own queues. It lives in `com.termux.terminal` for the
 * package-private members of terminal-emulator v0.118.3 (pinned in
 * `gradle/libs.versions.toml`); a version bump must re-check them.
 *
 * Construct on the main thread: the session's handler binds to its looper.
 */
class RemoteTerminal(client: TerminalSessionClient, columns: Int = 80, rows: Int = 24, val historyRows: Int = DEFAULT_TERMINAL_HISTORY_ROWS) {
    val metrics = TerminalMetrics(historyRows)
    val session = TerminalSession("/system/bin/false", "/", arrayOf(), arrayOf(), historyRows, client)
    /**
     * Writes local notes in order. Its one thread is a daemon and ends after
     * a second idle, so an ended (or replaced, on Reconnect) session leaves
     * no thread behind, and a note after [finish] still shows.
     */
    private val localWriter = ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS, LinkedBlockingQueue()) {
        Thread(it, "terminal-local").apply { isDaemon = true }
    }

    init {
        session.mEmulator = TerminalEmulator(session, columns, rows, CELL_WIDTH, CELL_HEIGHT, historyRows, client)
        // write() only queues keys while the "process" runs. No pid is ever
        // signalled: finishIfRunning() is not called on a remote session.
        session.mShellPid = Int.MAX_VALUE
    }

    val columns: Int get() = session.mEmulator.mColumns
    val rows: Int get() = session.mEmulator.mRows

    /** Where typed keys go: the open shell's stream, or null while there is none (keys are dropped). */
    @Volatile
    private var output: OutputStream? = null
    private val inputHandover = Any()
    @Volatile private var inputGeneration = 0L

    /**
     * Called on the writer thread with each chunk of keys before it goes to
     * [output]; it may block. Set once, before the first [pump].
     */
    @Volatile
    var beforeWrite: ((ByteArray, Int) -> Unit)? = null

    /** Copies typed keys to [output]; one thread for the terminal's life, so a reconnect does not race an old one. */
    private val writer by lazy {
        thread(name = "terminal-to-ssh", isDaemon = true) {
            val buffer = ByteArray(BUFFER)
            while (true) {
                val count: Int
                val out: OutputStream?
                val owner: Long
                val epoch: Long
                val token: ObservationToken?
                val queue = session.mTerminalToProcessIOQueue
                synchronized(queue) {
                    // Read and bind to an output under the same monitor as pump's
                    // queue drain/handover. Waiting releases it, with no polling.
                    var read = queue.read(buffer, false)
                    while (read == 0) {
                        try {
                            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                            (queue as java.lang.Object).wait()
                        } catch (_: InterruptedException) { /* Same policy as ByteQueue.read(block=true). */ }
                        read = queue.read(buffer, false)
                    }
                    count = read
                    out = output
                    owner = inputGeneration
                    epoch = metrics.currentGeneration()
                    token = metrics.token()
                }
                if (count < 0) break
                if (out == null || inputGeneration != owner || output !== out) continue
                val started = if (token != null) System.nanoTime() else 0L
                // May hold the bytes for a round trip to the device (tmux copy mode); keys typed meanwhile wait in the queue.
                try {
                    beforeWrite?.invoke(buffer, count)
                } finally {
                    if (token != null) metrics.beforeWriteFinished(token, System.nanoTime() - started)
                }
                if (inputGeneration != owner || output !== out) continue
                try {
                    out.write(buffer, 0, count)
                    out.flush()
                    metrics.written(epoch, count)
                } catch (_: IOException) {
                    // The reader sees the same failure and reports it.
                }
            }
        }
    }

    /**
     * Pumps [input] into the emulator and typed keys into [output] until
     * either side ends; then calls [onEnd] once, on a background thread,
     * with the read error if there was one. Called again after [pause]
     * with a new connection's streams.
     */
    fun pump(input: InputStream, output: OutputStream, onEnd: (IOException?) -> Unit) {
        val epoch: Long
        synchronized(inputHandover) {
            if (closed) return
            synchronized(session.mTerminalToProcessIOQueue) {
                // Never replay the preceding shell's queued input into this one.
                val discarded = ByteArray(BUFFER)
                while (session.mTerminalToProcessIOQueue.read(discarded, false) > 0) { }
                inputGeneration++
                epoch = metrics.connectionOpened()
                this.output = output
            }
            synchronized(session) { session.mShellPid = Int.MAX_VALUE }
        }
        writer
        thread(name = "ssh-to-terminal", isDaemon = true) {
            val buffer = ByteArray(BUFFER)
            var error: IOException? = null
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        metrics.received(epoch, read)
                        show(buffer, read, epoch)
                    }
                }
            } catch (e: IOException) {
                error = e
            }
            onEnd(error)
        }
    }

    /**
     * Between connections (reconnecting): typed keys are dropped, not kept
     * for the next shell; the screen stays. [pump] takes keys again.
     */
    fun pause() = synchronized(inputHandover) {
        synchronized(session.mTerminalToProcessIOQueue) {
            output = null
            inputGeneration++
            metrics.connectionClosed()
        }
        synchronized(session) { session.mShellPid = -1 }
    }

    /**
     * Back to the normal screen for a fresh shell: leaves the alternate
     * screen (an old `vim` or `htop`), resets colours, scroll region,
     * cursor and keypad modes and turns mouse reporting and bracketed paste
     * off; then prints [line]. The text above stays in the scrollback.
     * Waits until it is queued, so the new shell's first output comes after.
     */
    fun resetForNewShell(line: String) {
        val bytes = (FRESH_SCREEN + line).toByteArray(Charsets.UTF_8)
        localWriter.submit { show(bytes, bytes.size) }.get()
    }

    /** Prints a local note (connecting, closed, …) into the terminal; safe from any thread. */
    fun note(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        localWriter.execute { show(bytes, bytes.size) }
    }

    /** Stops taking keys; the screen and its scrollback stay readable. */
    fun finish() = synchronized(inputHandover) {
        synchronized(session.mTerminalToProcessIOQueue) {
            output = null
            inputGeneration++
            metrics.connectionClosed()
        }
        synchronized(session) {
            closed = true
            session.mShellPid = -1
        }
        session.mTerminalToProcessIOQueue.close()
    }

    /** [finish]ed: no shell will come again. */
    private var closed = false

    /** Pastes [text] as typed input (bracketed if the remote side asked for it). */
    fun paste(text: String): Boolean = synchronized(inputHandover) {
        if (output == null || !session.isRunning) return false
        // ByteQueue.write may wait for space. Keep connection handover out of
        // this whole paste, while the writer remains free to drain the queue.
        session.mEmulator.paste(text)
        true
    }

    /** Opaque connection generation; retaining it never permits retargeting after reconnect. */
    class InputTarget internal constructor(internal val owner: Any, internal val stream: OutputStream, internal val generation: Long)

    fun captureInputTarget(): InputTarget? = synchronized(inputHandover) {
        if (!session.isRunning) null else output?.let { InputTarget(inputHandover, it, inputGeneration) }
    }

    /** Literal paste plus optional explicit CR are enqueued in one handover-protected transaction. */
    fun paste(text: String, target: InputTarget, enter: Boolean): Boolean = synchronized(inputHandover) {
        if (target.owner !== inputHandover || !session.isRunning || output !== target.stream || inputGeneration != target.generation) return false
        // Never turn an empty/sanitized-away or control-only payload into a bare Enter.
        if (enter && text.none { !it.isWhitespace() && !it.isISOControl() }) return false
        session.mEmulator.paste(text)
        if (enter) session.write("\r")
        true
    }

    /** Whether the remote side turned bracketed paste on (see [bracketedPasteOn]). */
    val bracketedPaste: Boolean get() = bracketedPasteOn(session.mEmulator)

    // The queue is never closed: once closed it drops what is still in it.
    private fun show(bytes: ByteArray, count: Int, epoch: Long? = null) {
        var offset = 0
        while (offset < count) {
            val chunk = minOf(QUEUE_CHUNK, count - offset)
            // Local notes have no epoch: never traffic or a remote-input timing sample.
            // At most one rate-limited marker includes queue backpressure and emulator handling.
            val marker = epoch?.let(metrics::inputMarker)
            session.mProcessToTerminalIOQueue.write(bytes, offset, chunk)
            session.mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT)
            if (marker != null) session.mMainThreadHandler.post { metrics.inputProcessed(marker) }
            offset += chunk
        }
    }

    private companion object {
        const val CELL_WIDTH = 12
        const val CELL_HEIGHT = 24
        const val BUFFER = 4096

        /** The session's handler reads at most 4 KiB per message. */
        const val QUEUE_CHUNK = 4096

        /** See [resetForNewShell]. */
        const val FRESH_SCREEN =
            // Main screen, colours, whole-screen scroll region, cursor shown, autowrap, normal cursor keys and keypad.
            "\u001b[?1049l\u001b[0m\u001b[r\u001b[?25h\u001b[?7h\u001b[?1l\u001b>" +
                // G0 is ASCII and selected (no line drawing left on), insert, origin and reverse-video modes off.
                "\u001b(B\u000f\u001b[4l\u001b[?6l\u001b[?5l" +
                // No mouse reporting, focus events or bracketed paste.
                "\u001b[?1000l\u001b[?1002l\u001b[?1003l\u001b[?1006l\u001b[?1004l\u001b[?2004l"

        /** TerminalSession.MSG_NEW_INPUT. */
        const val MSG_NEW_INPUT = 1
    }
}

/** v0.118.3 keeps DECSET flags private: `mCurrentDecSetFlags`, bracketed paste (2004) is bit 1024. */
private const val BRACKETED_PASTE_BIT = 1024

private val decSetFlags = try {
    TerminalEmulator::class.java.getDeclaredField("mCurrentDecSetFlags").apply { isAccessible = true }
} catch (e: ReflectiveOperationException) {
    null
}

/**
 * Whether [emulator]'s remote side turned bracketed paste on (DECSET 2004).
 * Read on the main thread, where the emulator takes its input. Should the
 * private flag ever be out of reach, it says off: a multi-line paste is then
 * asked about rather than run line by line.
 */
fun bracketedPasteOn(emulator: TerminalEmulator?): Boolean = try {
    emulator != null && decSetFlags != null && (decSetFlags.getInt(emulator) and BRACKETED_PASTE_BIT) != 0
} catch (e: ReflectiveOperationException) {
    false
}
