package ru.adsrv.frpjump.assistant

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.RemoteTerminal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
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
import ru.adsrv.frpjump.ssh.tmuxReadScreenCommand
import ru.adsrv.frpjump.ssh.tmuxReadWindowCommand
import ru.adsrv.frpjump.ssh.tmuxPaneProgramCommand
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.TunnelRef

/** How `terminal_tabs` words a tab's state. */
internal enum class TabState(val wire: String) {
    CONNECTED("connected"),
    /** Connecting, reconnecting, or waiting for the owner's host-key answer. */
    RECONNECTING("reconnecting"),
    CLOSED("closed"),
    /** An assistant session remembered from before a restart that is not attached yet: its first use attaches it over its tunnel. */
    DETACHED("detached"),
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
    /** The name the owner or the assistant gave the tab, null while it shows the automatic label. */
    val name: String? = null,
    /** Who opened it: the assistant's own sessions are listed as such, with whether the owner has them as a tab. */
    val openedBy: ru.adsrv.frpjump.ssh.SessionOpener = ru.adsrv.frpjump.ssh.SessionOpener.OWNER,
    val shown: Boolean = true,
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
    /** Who opened the session (see [SshSession.openedBy]); the owner's tabs say OWNER. */
    val openedBy: ru.adsrv.frpjump.ssh.SessionOpener get() = ru.adsrv.frpjump.ssh.SessionOpener.OWNER
    /** Whether the owner has the session as a tab. */
    val shown: Boolean get() = true
    /** A session remembered from before a restart, not attached yet: no connection, no buffer until [AssistantTerminalTabs] attaches it. */
    val detached: Boolean get() = false
    /** The tab's own name (owner- or assistant-set), null when it has none. */
    val customName: String? get() = null
    /** Sets the tab's name ([ru.adsrv.frpjump.ssh.checkTabName]'s valid one; null clears it): the same as the owner's Rename…. Main thread. */
    fun rename(name: String?) = Unit
    /** The tab's tmux session name while tmux is in use on its connection, else null. */
    val tmuxName: String?
    /**
     * Runs [command] on the tab's EXISTING connection and returns the last [maxBytes] bytes of its output; null when the tab has no open
     * connection. Never opens one. Blocking and never interrupted (see [ru.adsrv.frpjump.ssh.execOutputTail]): the caller that stops
     * waiting calls [lease]'s abandon, which closes the channel. Throws when the command fails.
     */
    fun exec(command: String, maxBytes: Int, lease: ExecLease): String?
    /** The app's terminal buffer, which is the output source of a tab without tmux, read as [mode] asks. A bounded copy: no more rows than the mode needs. Called on the main thread. */
    fun bufferSource(mode: ReadMode): SourceText

    // What typing needs. The defaults fail closed: a tab that does not say how to type cannot be typed into.

    /** [System.nanoTime] of the latest output the remote side sent to the tab: the only thing the app's output tap tells (that something was printed). */
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
    override val openedBy get() = session.openedBy
    override val shown get() = session.shown.value
    override val customName get() = session.tabName.value?.text
    // Named by the assistant: the tab's chip shows it together with the automatic label until the owner saves it.
    override fun rename(name: String?) = session.rename(name, ru.adsrv.frpjump.ssh.NameSetter.ASSISTANT)
    override val tmuxName get() = session.tmux.assigned?.takeIf { session.tmux.inUse }
    override fun exec(command: String, maxBytes: Int, lease: ExecLease): String? = session.openClient()?.let { execOutputTail(it, command, maxBytes, lease) }
    override fun bufferSource(mode: ReadMode): SourceText = terminalBufferSource(session.terminal.session.emulator, mode)
    override val lastOutputNanos get() = session.terminal.lastOutputNanos
    override fun capturePin(): InputPin? = session.capturePasteTarget()?.let(::SessionPin)
    override fun pinCurrent(pin: InputPin) = pin is SessionPin && session.isCurrent(pin.target)
    override fun keyModes(): KeyModes = session.terminal.session.emulator.let { KeyModes(it.isCursorKeysApplicationMode, it.isKeypadApplicationMode) }
    override fun type(bytes: ByteArray, pin: InputPin) =
        if (pin is SessionPin) session.typeBytes(bytes, pin.target) else RemoteTerminal.TypeOutcome.REJECTED
}

/**
 * [emulator]'s buffer as [mode] asks (see [SourceText]), only the rows that mode needs. Only the buffer in use is read (a full-screen program's own
 * screen has no history). Main thread, where the emulator takes input.
 */
internal fun terminalBufferSource(emulator: TerminalEmulator, mode: ReadMode): SourceText {
    val screen = emulator.screen
    val history = screen.activeTranscriptRows
    val rows = emulator.mRows
    val last = emulator.mColumns - 1
    val cursorRow = history + emulator.cursorRow
    val noDrop = history < com.termux.terminal.transcriptCapacity(emulator)
    // The rows just above the cursor row, as physical rows: what the checksum of a cursor is made of.
    val aboveFrom = maxOf(-history, emulator.cursorRow - ANCHOR_ROWS)
    val above = if (aboveFrom >= emulator.cursorRow) emptyList() else screen.getSelectedText(0, aboveFrom, last, emulator.cursorRow - 1, false, false).split('\n')
    return when (mode) {
        is ReadMode.Screen -> SourceText(screen.getSelectedText(0, -minOf(mode.lines, history), last, rows - 1), -1, cursorRow, above, history, noDrop)
        is ReadMode.From -> {
            // From three rows above the cursor when its row number places it; when rows may have dropped, from one trim of the history higher (the
            // buffer drops a row at a time, so a tenth of its capacity is a window, not a promise: the caller widens it with [ReadMode.From.full]).
            val exact = noDrop && history >= mode.cursor.history
            val above3 = maxOf(0, mode.cursor.position - ANCHOR_ROWS)
            val start = when {
                mode.full -> 0
                exact -> above3
                else -> maxOf(0, above3 - trimRows(com.termux.terminal.transcriptCapacity(emulator)))
            }
            val top = minOf(start - history, rows - 1)
            SourceText(screen.getSelectedText(0, top, last, rows - 1, false, false), top + history, cursorRow, above, history, noDrop)
        }
    }
}

private const val ANCHOR_ROWS = 3

/**
 * What a tab read gave: its [text] (already clean) and where it came from, and the [cursor] of the end of the tab's output at the time of
 * the read. [cursorGone]: a read from a cursor whose rows are no longer kept, so [text] is the screen and the remaining history.
 */
internal class TabRead(val tab: TerminalTab, val text: String, val cursor: TabCursor, val cursorGone: Boolean = false)

/** The assistant's session ended on the device (its tmux session is gone): it is not listed any more. */
internal class TabEnded : Exception()
/** The saved login of a session needs the ordinary SSH UI (or is another user's): nothing was attached. */
internal class TabNeedsSetup : Exception()
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
    /** The owner's limit of one tool call, read at use: no wait of a read is longer than [withinCall] of it. */
    private val toolTimeoutMs: () -> Long = { ru.adsrv.frpjump.settings.AssistantLimits().toolTimeoutSeconds * 1000L },
    /** The saved SSH connection's id for profile, device and port: the grant target of typing into a tab of it. Null when none matches. */
    private val connectionId: (String, String, Int) -> String? = { _, _, _ -> null },
    /** The most the cursor read before a typing may take (it also answers whether a tmux pane is in copy mode). */
    private val cursorReadTimeoutMs: Long = CURSOR_READ_TIMEOUT_MS,
    /** Where rows are cleaned and searched: never the main thread. */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    /** Attaches the assistant's session [id] over its tunnel (a remembered one, or one that ended: it then is a live tab with that id). */
    private val attach: suspend (Int) -> AttachResult = { AttachResult.UNREACHABLE },
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
        connectionName(tab.profileId, tab.device, tab.port), stateOf(tab), tab.tmuxName != null, tab.customName, tab.openedBy, tab.shown,
    )

    /** The tab's state as the model is told: [TabState.DETACHED] while a remembered assistant session is not attached. */
    private fun stateOf(tab: TabHandle) = if (tab.detached) TabState.DETACHED else tabState(tab.state)

    /** The open (not hidden) tab [id] as the waits watch it; null once it is gone. Reads app state only. */
    internal fun handle(id: Int): TabHandle? = visible().firstOrNull { it.id == id }

    /** The tab in the owner's words: its own name, else its automatic label. */
    internal fun words(tab: TabHandle): String =
        tab.customName ?: ru.adsrv.frpjump.ui.ssh.tabLabel(tab.user, tab.device, tab.port, tab.number)

    /** The open tabs in the Sessions list's order. */
    internal fun list(): List<TerminalTab> = visible().map(::describe)

    /**
     * Renames tab [id] as the owner's Rename… does (the same store, so a tmux tab's name is remembered the same way); [name] is already
     * valid ([ru.adsrv.frpjump.ssh.checkTabName]), null for the automatic label. [TabNotOpen] when the tab is unknown, hidden or closed:
     * nothing changes then. Only the tab's label changes; its connection is not touched.
     */
    internal suspend fun rename(id: Int, name: String?): TerminalTab = withContext(main) {
        // Looked up again where the rename happens: a tab closed, hidden or ended since the call began is not renamed.
        val handle = visible().firstOrNull { it.id == id } ?: throw TabNotOpen()
        if (tabState(handle.state) == TabState.CLOSED) throw TabNotOpen()
        handle.rename(name)
        describe(handle)
    }

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
     * The state checks of a read of tab [id] and its [TabText] as [mode] asks: [TabNotOpen] when it is unknown, hidden or closed,
     * [TabNotConnected] while it is not connected (no I/O in both); otherwise the tab's one output source is read: a tmux tab over its existing
     * connection, any other tab from its terminal buffer. Any other exception is a failed read.
     */
    internal suspend fun look(id: Int, mode: ReadMode): Pair<TerminalTab, TabText> {
        val handle = attached(visible().firstOrNull { it.id == id } ?: throw TabNotOpen())
        val tab = describe(handle)
        when (tab.state) {
            TabState.CLOSED -> throw TabNotOpen()
            TabState.RECONNECTING, TabState.DETACHED -> throw TabNotConnected(tab.state)
            TabState.CONNECTED -> Unit
        }
        return tab to source(handle, mode, withinCall(readTimeoutMs, toolTimeoutMs())).first
    }

    /**
     * The one place a tab's output is read from: the device's tmux for a tmux tab, the app's terminal buffer for any other. [timeoutMs] bounds a
     * read over a connection. The second of the pair says whether a tmux pane was in copy mode at the end of the read (false for any other tab).
     */
    private suspend fun source(handle: TabHandle, mode: ReadMode, timeoutMs: Long): Pair<TabText, Boolean> {
        val name = handle.tmuxName
        if (name == null) {
            val source = withContext(main) { handle.bufferSource(mode) }
            return withContext(compute) { TabText(source) } to false
        }
        admitSideEffect(DISPATCH)
        val command = when (mode) {
            is ReadMode.Screen -> tmuxReadScreenCommand(name, mode.lines)
            // The window is relative to the bottom of the pane, so it needs no question first: one trim of the history above the screen, as tall as the
            // screen was last seen (a guess before the first read: a wrong guess only makes the window too small, and the whole history is the fallback).
            is ReadMode.From -> tmuxReadWindowCommand(name, if (mode.full) null else geometry[handle.id].let { (it?.first ?: DEFAULT_LIMIT) / 10 + (it?.second ?: DEFAULT_HEIGHT) + ANCHOR_ROWS })
        }
        val output = bounded(timeoutMs) { lease -> handle.exec(command, READ_MAX_BYTES, lease) } ?: throw java.io.IOException("tab read failed")
        val read = withContext(compute) { tmuxSource(output, mode) }
        geometry[handle.id] = read.limit to read.height
        return read.text to read.inCopyMode
    }

    /** The history limit and the screen height each tmux tab had when last read (tab id -> limit, height): what the next window is sized by. */
    private val geometry = java.util.concurrent.ConcurrentHashMap<Int, Pair<Int, Int>>()

    private class TmuxRead(val text: TabText, val inCopyMode: Boolean, val limit: Int, val height: Int)

    /**
     * What [tmuxReadScreenCommand] and [tmuxReadWindowCommand] print, read from the end (only the newest bytes are kept): a last line of numbers
     * (`history_size history_limit cursor_y pane_height pane_in_mode`), taken by the same tmux invocation as the rows, so they belong together; for a read
     * without a cursor before it the physical rows from three rows above the screen (the rows above the cursor are cut out of them) and before those the
     * rows to show; for a read to place a cursor the rows alone, which are the physical rows to the bottom of the screen.
     */
    private fun tmuxSource(output: String, mode: ReadMode): TmuxRead {
        val lines = output.removeSuffix("\n").split('\n')
        val n = lines.last().trim().split(' ').map { it.toIntOrNull() ?: throw java.io.IOException("tab read unreadable") }
        if (n.size != 5 || n.any { it < 0 } || n[3] == 0) throw java.io.IOException("tab read unreadable")
        val (history, limit, cursorY, height, inMode) = n
        val cursorRow = history + cursorY
        var body = lines.subList(0, lines.size - 1)
        val above: List<String>
        val text: List<String>
        val first: Int
        if (mode is ReadMode.Screen) {
            // The physical block: rows from three above the screen (fewer when the history is shorter) down to its bottom.
            val start = history - minOf(ANCHOR_ROWS, history)
            val block = minOf(ANCHOR_ROWS, history) + height
            if (body.size < block) throw java.io.IOException("tab read unreadable")
            val physical = body.subList(body.size - block, body.size)
            above = physical.subList(maxOf(0, cursorRow - ANCHOR_ROWS) - start, cursorRow - start)
            text = body.subList(0, body.size - block)
            first = -1
        } else {
            // Only the newest bytes are kept: when the cut fell in a row, that row is partial and goes.
            if (output.length >= READ_MAX_BYTES / 4 && output.toByteArray(Charsets.UTF_8).size >= READ_MAX_BYTES - CUT_MARGIN) body = body.drop(1)
            first = history + height - body.size
            if (first < 0) throw java.io.IOException("tab read unreadable")
            val from = maxOf(0, cursorRow - ANCHOR_ROWS) - first
            above = if (from < 0) emptyList() else body.subList(from, cursorRow - first)
            text = body
        }
        val noDrop = history.toLong() * 10 < limit.toLong() * 9
        val trim = if (mode is ReadMode.From && !mode.full) limit / 10 else -1
        val source = SourceText(text.joinToString("\n"), first, cursorRow, above, history, noDrop, trim)
        return TmuxRead(TabText(source), inMode == 1, limit, height)
    }

    /**
     * [handle] itself, or, for a remembered assistant session that is not attached yet ([TabHandle.detached]), the session it became by being attached
     * over its tunnel (the first read, typing or wait does it): [TabNotConnected] when it cannot be.
     */
    private suspend fun attached(handle: TabHandle): TabHandle {
        // A detached session, or one of the assistant's whose connection ended: attached again over its tunnel (never created anew).
        if (!handle.detached && !(handle.openedBy == ru.adsrv.frpjump.ssh.SessionOpener.ASSISTANT && tabState(handle.state) == TabState.CLOSED)) return handle
        when (attach(handle.id)) {
            AttachResult.ATTACHED -> Unit
            AttachResult.GONE -> throw TabEnded()
            AttachResult.NEEDS_SETUP -> throw TabNeedsSetup()
            AttachResult.UNREACHABLE, AttachResult.RECORD_SAVE_FAILED -> throw TabNotConnected(TabState.DETACHED)
        }
        return visible().firstOrNull { it.id == handle.id } ?: throw TabNotOpen()
    }

    /** Runs [block] off the main thread: rows are cleaned and searched where the app's UI is not. */
    internal suspend fun <T> offMain(block: () -> T): T = withContext(compute) { block() }

    /**
     * Reads tab [id] ([look]): without [from] the visible screen with up to [lines] rows of history; with it only the rows printed from that
     * cursor on, or, when they cannot be told, the screen and history as without it ([TabRead.cursorGone]). Types nothing.
     */
    internal suspend fun read(id: Int, lines: Int, from: TabCursor? = null): TabRead {
        if (from != null) {
            val found = rowsSince(id, from)
            if (found != null) return TabRead(found.tab, found.rows, found.cursor)
        }
        // Without a cursor, or when it cannot be placed: the screen and its history, and a fresh cursor.
        val (tab, text) = look(id, ReadMode.Screen(lines))
        return TabRead(tab, text.screen(), text.cursor, cursorGone = from != null)
    }

    /** The rows read from a cursor, and the cursor of their end. */
    internal class Since(val tab: TerminalTab, val rows: String, val cursor: TabCursor)

    /**
     * The rows from [from] on: read as little as places it (from three rows above it; when rows may have been dropped, from one trim of the history
     * higher), then, only when that did not, the whole history; null when it is placed by neither.
     */
    internal suspend fun rowsSince(id: Int, from: TabCursor): Since? {
        for (full in listOf(false, true)) {
            val (tab, text) = look(id, ReadMode.From(from, full))
            offMain { text.since(from) }?.let { return Since(tab, it, text.cursor) }
            // Rows that were read and do not match its checksum where its row number says they should are a real change: more rows would not help.
            if (full || (text.placedByRow(from) && text.covers(from))) return null
        }
        return null
    }

    /**
     * Types [input] into its tab, as the owner would, on the connection [pin] was taken on, and returns the cursor of the tab's output as it was
     * just BEFORE typing: what the input causes is printed after it. It does not wait for anything. Checks before anything is typed (each throws and types nothing): [TabNotOpen],
     * [TabNotConnected], [TabReconnected] (no pin, or the connection is no longer the pinned one), [TabInCopyMode] (a tmux tab's pane is in copy mode), and any other exception when
     * the copy-mode question could not be answered. The cursor is read next (any exception there types nothing). The bytes then go in one
     * transaction; from there on a failure, or a tab that is no longer that connection, is [TypedUnknown].
     */
    internal suspend fun type(input: TypedInput, requestedPin: InputPin?): TabCursor {
        val found = visible().firstOrNull { it.id == input.tabId } ?: throw TabNotOpen()
        val handle = attached(found)
        // A remembered session had no connection when the owner was asked: the one it has by being attached now is the one typed into.
        val pin = if (found.detached) requestedPin ?: handle.capturePin() else requestedPin
        val state = tabState(handle.state)
        when (state) {
            TabState.CLOSED -> throw TabNotOpen()
            TabState.RECONNECTING, TabState.DETACHED -> throw TabNotConnected(state)
            TabState.CONNECTED -> Unit
        }
        if (pin == null || !handle.pinCurrent(pin)) throw TabReconnected()
        // The cursor read comes last before the bytes go: for a tmux tab it also asks, in the same exec and after the capture, whether the pane is in
        // copy mode, so the gap between the answer and the keystroke is milliseconds. It has its own time limit.
        val (text, inCopyMode) = source(handle, ReadMode.Screen(0), cursorReadTimeoutMs)
        if (inCopyMode) throw TabInCopyMode()
        val before = text.cursor
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
        // The tab left Open meanwhile, or is another connection now: what was typed may have gone nowhere or half way.
        if (tabState(handle.state) != TabState.CONNECTED || !handle.pinCurrent(pin)) throw TypedUnknown()
        return before
    }

    companion object {
        /** The admission category of a read over a tab's connection (see [admitSideEffect]). */
        const val DISPATCH = "tmux-dispatch"
        /** The admission category of the write into a tab. */
        const val TYPE = "terminal-input"
        const val CURSOR_READ_TIMEOUT_MS = 3_000L
        /** How old a `terminal_tabs` probe may be for the typing card to show its program. */
        const val PROGRAM_FRESH_MS = 60_000L
        const val PROBE_TIMEOUT_MS = 3_000L
        const val READ_TIMEOUT_MS = 10_000L
        private const val PROBE_MAX_BYTES = 1_024
        private const val READ_MAX_BYTES = 1 shl 20
        /** Room for a character that the byte limit cut in two. */
        private const val CUT_MARGIN = 8
        /** What a window is sized by before the first read of a tab says what its history limit and screen are. */
        private const val DEFAULT_LIMIT = 2000
        private const val DEFAULT_HEIGHT = 50
        const val PROGRAM_CHARS = 64

        /** A program name from tmux's answer: its first line, printable characters only, no record separator, at most [PROGRAM_CHARS]; null when nothing is left. */
        fun program(raw: String): String? =
            receiptText(raw.lineSequence().firstOrNull().orEmpty(), PROGRAM_CHARS).replace('|', '¦').takeIf { it.isNotEmpty() }
    }
}
