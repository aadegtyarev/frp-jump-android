package com.termux.terminal

import java.io.IOException
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
class RemoteTerminal(client: TerminalSessionClient, columns: Int = 80, rows: Int = 24) {
    val session = TerminalSession("/system/bin/false", "/", arrayOf(), arrayOf(), TRANSCRIPT_ROWS, client)
    /**
     * Writes local notes in order. Its one thread is a daemon and ends after
     * a second idle, so an ended (or replaced, on Reconnect) session leaves
     * no thread behind, and a note after [finish] still shows.
     */
    private val localWriter = ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS, LinkedBlockingQueue()) {
        Thread(it, "terminal-local").apply { isDaemon = true }
    }

    init {
        session.mEmulator = TerminalEmulator(session, columns, rows, CELL_WIDTH, CELL_HEIGHT, TRANSCRIPT_ROWS, client)
        // write() only queues keys while the "process" runs. No pid is ever
        // signalled: finishIfRunning() is not called on a remote session.
        session.mShellPid = Int.MAX_VALUE
    }

    val columns: Int get() = session.mEmulator.mColumns
    val rows: Int get() = session.mEmulator.mRows

    /** Where typed keys go: the open shell's stream, or null while there is none (keys are dropped). */
    @Volatile
    private var output: OutputStream? = null

    /** Copies typed keys to [output]; one thread for the terminal's life, so a reconnect does not race an old one. */
    private val writer by lazy {
        thread(name = "terminal-to-ssh", isDaemon = true) {
            val buffer = ByteArray(BUFFER)
            while (true) {
                val count = session.mTerminalToProcessIOQueue.read(buffer, true)
                if (count < 0) break
                val out = output ?: continue
                try {
                    out.write(buffer, 0, count)
                    out.flush()
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
        this.output = output
        synchronized(session) { if (!closed) session.mShellPid = Int.MAX_VALUE }
        writer
        thread(name = "ssh-to-terminal", isDaemon = true) {
            val buffer = ByteArray(BUFFER)
            var error: IOException? = null
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) show(buffer, read)
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
    fun pause() {
        output = null
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
    fun finish() {
        output = null
        synchronized(session) {
            closed = true
            session.mShellPid = -1
        }
        session.mTerminalToProcessIOQueue.close()
    }

    /** [finish]ed: no shell will come again. */
    private var closed = false

    /** Pastes [text] as typed input (bracketed if the remote side asked for it). */
    fun paste(text: String) {
        if (session.isRunning) session.mEmulator.paste(text)
    }

    // The queue is never closed: once closed it drops what is still in it.
    private fun show(bytes: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val chunk = minOf(QUEUE_CHUNK, count - offset)
            session.mProcessToTerminalIOQueue.write(bytes, offset, chunk)
            session.mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT)
            offset += chunk
        }
    }

    private companion object {
        const val TRANSCRIPT_ROWS = 3000
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
