package ru.adsrv.frpjump.assistant

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.RemoteTerminal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.adsrv.frpjump.agent.admitSideEffect
import ru.adsrv.frpjump.assistant.chat.receiptText
import ru.adsrv.frpjump.sessions.sessionHidden
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.ssh.ExecLease
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ssh.execOutputTail
import ru.adsrv.frpjump.ssh.tmuxCapturePaneCommand
import ru.adsrv.frpjump.ssh.tmuxPaneInModeCommand
import ru.adsrv.frpjump.ssh.tmuxPaneProgramCommand
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.TunnelRef

/** How `terminal_tabs` words a tab's state. */
internal enum class TabState(val wire: String) {
    CONNECTED("connected"),
    /** Connecting, reconnecting, or waiting for the owner's host-key answer. */
    RECONNECTING("reconnecting"),
    CLOSED("closed"),
}

internal fun tabState(state: SshState): TabState = when (state) {
    SshState.Open -> TabState.CONNECTED
    is SshState.Ended -> TabState.CLOSED
    SshState.Connecting, is SshState.Reconnecting, is SshState.HostKeyUnknown, is SshState.HostKeyChanged -> TabState.RECONNECTING
}

/** One open tab as the assistant is told of it. Display text only: nothing here is a credential. */
internal data class TerminalTab(
    val id: Int, val profileId: String, val profile: String, val device: String, val user: String, val number: Int,
    val connection: String?, val state: TabState,
    /** The tab's tmux session is in use (the app started it on this connection). A tmux started by hand inside a tab is not known. */
    val tmux: Boolean,
)

/**
 * A tab's connection as it was at one moment, without credentials: an opaque handle for the typing tool. What was described to the owner
 * and what is typed into must be the same connection; a handle taken before a reconnect never works after it.
 */
internal interface InputPin

/** What the assistant needs from one terminal tab; [SessionTab] is the app's, tests pass their own. */
internal interface TabHandle {
    val id: Int
    val profileId: String
    val device: String
    val port: Int
    val user: String
    val number: Int
    val state: SshState
    /** The tab's tmux session name while tmux is in use on its connection, else null. */
    val tmuxName: String?
    /**
     * Runs [command] on the tab's EXISTING connection and returns the last [maxBytes] bytes of its output; null when the tab has no open
     * connection. Never opens one. Blocking and never interrupted (see [ru.adsrv.frpjump.ssh.execOutputTail]): the caller that stops
     * waiting calls [lease]'s abandon, which closes the channel. Throws when the command fails.
     */
    fun exec(command: String, maxBytes: Int, lease: ExecLease): String?
    /** The terminal buffer's text: the last [lines] history rows and the visible screen. Called on the main thread. */
    fun bufferText(lines: Int): String

    // What typing needs. The defaults fail closed: a tab that does not say how to type cannot be typed into.

    /** [System.nanoTime] of the latest output the remote side sent to the tab. */
    val lastOutputNanos: Long get() = 0L
    /** The tab's connection as it is now (see [InputPin]); null while none is open. Reads state only. */
    fun capturePin(): InputPin? = null
    /** Whether [pin] is still the tab's connection. */
    fun pinCurrent(pin: InputPin): Boolean = false
    /** How the emulator sends cursor and keypad keys now. Called on the main thread. */
    fun keyModes(): KeyModes = KeyModes(cursorApplication = false, keypadApplication = false)
    /** Queues [bytes] as typed input in one transaction on the connection [pin] names; never on another one. */
    fun type(bytes: ByteArray, pin: InputPin): RemoteTerminal.TypeOutcome = RemoteTerminal.TypeOutcome.REJECTED
}

private class SessionPin(val target: SshSession.PasteTarget) : InputPin

/** The app's [TabHandle] over one [SshSession]. */
internal class SessionTab(private val session: SshSession) : TabHandle {
    override val id get() = session.id
    override val profileId get() = session.profile
    override val device get() = session.device
    override val port get() = session.port
    override val user get() = session.user
    override val number get() = session.number
    override val state get() = session.state.value
    override val tmuxName get() = session.tmux.assigned?.takeIf { session.tmux.inUse }
    override fun exec(command: String, maxBytes: Int, lease: ExecLease): String? = session.openClient()?.let { execOutputTail(it, command, maxBytes, lease) }
    override fun bufferText(lines: Int): String = terminalBufferText(session.terminal.session.emulator, lines)
    override val lastOutputNanos get() = session.terminal.lastOutputNanos
    override fun capturePin(): InputPin? = session.capturePasteTarget()?.let(::SessionPin)
    override fun pinCurrent(pin: InputPin) = pin is SessionPin && session.isCurrent(pin.target)
    override fun keyModes(): KeyModes = session.terminal.session.emulator.let { KeyModes(it.isCursorKeysApplicationMode, it.isKeypadApplicationMode) }
    override fun type(bytes: ByteArray, pin: InputPin) =
        if (pin is SessionPin) session.typeBytes(bytes, pin.target) else RemoteTerminal.TypeOutcome.REJECTED
}

/**
 * The visible screen of [emulator] with the last [lines] rows of history before it; wrapped rows joined, trailing spaces of a row
 * dropped. Only the buffer in use is read (a full-screen program's own screen has no history). Main thread, where the emulator takes input.
 */
internal fun terminalBufferText(emulator: TerminalEmulator, lines: Int): String {
    val screen = emulator.screen
    val history = minOf(lines, screen.activeTranscriptRows)
    return screen.getSelectedText(0, -history, emulator.mColumns - 1, emulator.mRows - 1)
}

/** What a tab read gave: its text and where it came from. */
internal class TabRead(val tab: TerminalTab, val text: String)

internal class TabNotOpen : Exception()
internal class TabNotConnected(val state: TabState) : Exception()
/** The tab's tmux pane is in copy mode: the owner may be scrolling. Nothing was typed. */
internal class TabInCopyMode : Exception()
/** The tab's connection is not the one the owner was asked about. Nothing was typed. */
internal class TabReconnected : Exception()
/** The bytes went to the connection (or may have), and what came of them cannot be seen. */
internal class TypedUnknown : Exception()

/** What the confirmation card and the pin need of a tab, from app state only: no I/O. [pin] is null while the tab has no open connection. */
internal class TypingView(val tab: TerminalTab, val program: String?, val target: GrantTarget?, val pin: InputPin?)

/** A finished typing: the screen as `terminal_read` shows it, and whether it had stopped changing before the time limit. */
internal class TabTyped(val read: TabRead, val settled: Boolean)

/**
 * The owner's open terminal tabs for `terminal_tabs`, `terminal_read` and `terminal_input`: the tabs the owner has not hidden (a closed tab
 * with its Undo on screen is gone from the lists as well), read only through the tab's own existing connection or its terminal buffer, and
 * typed into only through that tab's own terminal input ([type]). There is no connector here: no new login, no new tunnel.
 */
class AssistantTerminalTabs internal constructor(
    private val tabs: () -> List<TabHandle>,
    /** [ru.adsrv.frpjump.undo.PendingUndo.hidden], the source the Sessions list uses. */
    private val hidden: () -> Set<String>,
    private val profileName: (String) -> String?,
    /** The saved SSH connection's own name for profile, device and port, when there is one. */
    private val connectionName: (String, String, Int) -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val main: CoroutineDispatcher = Dispatchers.Main,
    private val probeTimeoutMs: Long = PROBE_TIMEOUT_MS,
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
    /** The saved SSH connection's id for profile, device and port: the grant target of typing into a tab of it. Null when none matches. */
    private val connectionId: (String, String, Int) -> String? = { _, _, _ -> null },
    private val copyProbeTimeoutMs: Long = COPY_PROBE_TIMEOUT_MS,
    private val quietMs: Long = QUIET_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    /** Milliseconds on a clock that only moves forward; the age of a program probe. */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** The program the last `terminal_tabs` probe found in each tab and when (tab id -> program, time). Display only, for the typing card. */
    private val probedPrograms = java.util.concurrent.ConcurrentHashMap<Int, Pair<String, Long>>()

    /** The workers' own scope: a worker is never cancelled with its caller, because a channel is never opened or read half way. */
    private val workers = CoroutineScope(io + SupervisorJob())

    /**
     * [work] on a worker thread, awaited for at most [timeoutMs]; null on timeout. When the caller stops waiting (timeout, Stop, an error)
     * the lease is abandoned: the worker's channel is closed in the background, now or when it arrives. The worker itself is not
     * interrupted and may linger until its connection call returns or the connection ends; it holds no result anyone waits for.
     */
    private suspend fun <T> bounded(timeoutMs: Long, work: (ExecLease) -> T): T? {
        val lease = ExecLease()
        val job = workers.async { work(lease) }
        try {
            return withTimeoutOrNull(timeoutMs) { job.await() }
        } finally {
            if (!job.isCompleted) lease.abandon()
        }
    }

    private fun visible(): List<TabHandle> {
        val h = hidden()
        return tabs().filterNot { sessionHidden(h, SessionKind.TERMINAL, it.id, TunnelRef(it.profileId, it.device, it.port)) }
    }

    private fun describe(tab: TabHandle) = TerminalTab(
        tab.id, tab.profileId, profileName(tab.profileId) ?: tab.profileId, tab.device, tab.user, tab.number,
        connectionName(tab.profileId, tab.device, tab.port), tabState(tab.state), tab.tmuxName != null,
    )

    /** The open tabs in the Sessions list's order. */
    internal fun list(): List<TerminalTab> = visible().map(::describe)

    /**
     * The program in the foreground of each connected tmux tab ([TerminalTab.tmux]) by tab id: one probe per tab over the tab's existing
     * connection, in parallel, each bounded by [probeTimeoutMs]. A failed or late probe leaves the tab out.
     */
    internal suspend fun programs(): Map<Int, String> {
        val probed = visible().filter { it.tmuxName != null && tabState(it.state) == TabState.CONNECTED }
        if (probed.isEmpty()) return emptyMap()
        admitSideEffect(DISPATCH)
        val found = coroutineScope {
            probed.map { tab ->
                async {
                    val name = tab.tmuxName ?: return@async null
                    val answer = try {
                        bounded(probeTimeoutMs) { lease -> tab.exec(tmuxPaneProgramCommand(name), PROBE_MAX_BYTES, lease) }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
                    program(answer.orEmpty())?.let { tab.id to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        // What this probe found is what the typing card may show for the next minute; a probe that found nothing drops the old answer.
        val at = clockMs()
        for (tab in probed) {
            val program = found[tab.id]
            if (program != null) probedPrograms[tab.id] = program to at else probedPrograms.remove(tab.id)
        }
        return found
    }

    /**
     * What a call that would type into tab [id] shows the owner, and the pin that ties the call to the tab's connection of now. Reads app
     * state only (no I/O, nothing typed, no probe): the program is the one the last `terminal_tabs` probe found, if that is at most
     * [PROGRAM_FRESH_MS] old. Null when the tab is unknown or hidden.
     */
    internal fun typingView(id: Int): TypingView? {
        val handle = visible().firstOrNull { it.id == id } ?: return null
        val tab = describe(handle)
        val program = probedPrograms[id]?.takeIf { clockMs() - it.second <= PROGRAM_FRESH_MS }?.first
        val target = connectionId(handle.profileId, handle.device, handle.port)?.let { GrantTarget(handle.profileId, it) }
        return TypingView(tab, program, target, if (tab.state == TabState.CONNECTED) handle.capturePin() else null)
    }

    /**
     * Reads tab [id]: [TabNotOpen] when it is unknown, hidden or closed, [TabNotConnected] while it is not connected (no I/O in both);
     * otherwise a tmux tab is captured over its existing connection and any other tab from its terminal buffer. Any other exception is a
     * failed read.
     */
    internal suspend fun read(id: Int, lines: Int): TabRead {
        val handle = visible().firstOrNull { it.id == id } ?: throw TabNotOpen()
        val tab = describe(handle)
        when (tab.state) {
            TabState.CLOSED -> throw TabNotOpen()
            TabState.RECONNECTING -> throw TabNotConnected(tab.state)
            TabState.CONNECTED -> Unit
        }
        val name = handle.tmuxName
        if (name == null) return TabRead(tab, withContext(main) { handle.bufferText(lines) })
        admitSideEffect(DISPATCH)
        val text = bounded(readTimeoutMs) { lease -> handle.exec(tmuxCapturePaneCommand(name, lines), READ_MAX_BYTES, lease) }
            ?: throw java.io.IOException("tab read failed")
        return TabRead(tab, text)
    }

    /**
     * Types [input] into its tab, as the owner would, on the connection [pin] was taken on, and returns the screen as [read] with no
     * history. Checks before anything is typed (each throws and types nothing): [TabNotOpen], [TabNotConnected], [TabReconnected] (no pin,
     * or the connection is no longer the pinned one), [TabInCopyMode] (a tmux tab's pane is in copy mode), and any other exception when
     * the copy-mode question could not be answered. The bytes then go in one transaction; from there on a failure is [TypedUnknown].
     * Then it waits until the tab's output has been quiet for [quietMs] counted from the later of the write and the last output (so never
     * less than [quietMs] after the write: output from before it is not "quiet"), at most [maxWaitMs]; [TabTyped.settled] is false when it
     * ran out of time.
     */
    internal suspend fun type(input: TypedInput, pin: InputPin?): TabTyped {
        val handle = visible().firstOrNull { it.id == input.tabId } ?: throw TabNotOpen()
        val state = tabState(handle.state)
        when (state) {
            TabState.CLOSED -> throw TabNotOpen()
            TabState.RECONNECTING -> throw TabNotConnected(state)
            TabState.CONNECTED -> Unit
        }
        if (pin == null || !handle.pinCurrent(pin)) throw TabReconnected()
        val name = handle.tmuxName
        if (name != null) {
            admitSideEffect(DISPATCH)
            val answer = bounded(copyProbeTimeoutMs) { lease -> handle.exec(tmuxPaneInModeCommand(name), PROBE_MAX_BYTES, lease) }
                ?: throw java.io.IOException("copy mode probe failed")
            when (answer.trim()) {
                "1" -> throw TabInCopyMode()
                "0" -> Unit
                else -> throw java.io.IOException("copy mode probe unreadable")
            }
        }
        val modes = withContext(main) { handle.keyModes() }
        val bytes = input.bytes(modes) ?: throw IllegalStateException("a key has no bytes")
        admitSideEffect(TYPE)
        // Once started it is finished: a Stop in the middle must not leave half of the text queued without the caller knowing.
        // An exception from the write itself says nothing about how much went out: it is "may be partly sent", never "nothing was typed".
        val outcome = try { withContext(NonCancellable + io) { handle.type(bytes, pin) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw TypedUnknown() }
        when (outcome) {
            RemoteTerminal.TypeOutcome.REJECTED -> throw TabReconnected()
            RemoteTerminal.TypeOutcome.PARTIAL -> throw TypedUnknown()
            RemoteTerminal.TypeOutcome.QUEUED -> Unit
        }
        val wroteAt = System.nanoTime()
        try {
            val settled = waitQuiet(handle, wroteAt)
            // The tab left Open meanwhile, or is another connection now: what was typed may have gone nowhere or half way.
            if (tabState(handle.state) != TabState.CONNECTED || !handle.pinCurrent(pin)) throw TypedUnknown()
            return TabTyped(read(input.tabId, 0), settled)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw TypedUnknown() }
    }

    /** True once [quietMs] have passed since the later of [wroteAt] and the tab's last output; false at [maxWaitMs]. Ends early when the tab leaves Open. */
    private suspend fun waitQuiet(handle: TabHandle, wroteAt: Long): Boolean {
        while (true) {
            val now = System.nanoTime()
            if (tabState(handle.state) != TabState.CONNECTED) return true
            if (now - maxOf(wroteAt, handle.lastOutputNanos) >= quietMs * NANOS_PER_MS) return true
            if (now - wroteAt >= maxWaitMs * NANOS_PER_MS) return false
            delay(POLL_MS)
        }
    }

    companion object {
        /** The admission category of a read over a tab's connection (see [admitSideEffect]). */
        const val DISPATCH = "tmux-dispatch"
        /** The admission category of the write into a tab. */
        const val TYPE = "terminal-input"
        const val COPY_PROBE_TIMEOUT_MS = 3_000L
        const val QUIET_MS = 700L
        const val MAX_WAIT_MS = 10_000L
        /** How old a `terminal_tabs` probe may be for the typing card to show its program. */
        const val PROGRAM_FRESH_MS = 60_000L
        private const val POLL_MS = 25L
        private const val NANOS_PER_MS = 1_000_000L
        const val PROBE_TIMEOUT_MS = 3_000L
        const val READ_TIMEOUT_MS = 10_000L
        private const val PROBE_MAX_BYTES = 1_024
        private const val READ_MAX_BYTES = 1 shl 20
        const val PROGRAM_CHARS = 64

        /** A program name from tmux's answer: its first line, printable characters only, no record separator, at most [PROGRAM_CHARS]; null when nothing is left. */
        fun program(raw: String): String? =
            receiptText(raw.lineSequence().firstOrNull().orEmpty(), PROGRAM_CHARS).replace('|', '¦').takeIf { it.isNotEmpty() }
    }
}
