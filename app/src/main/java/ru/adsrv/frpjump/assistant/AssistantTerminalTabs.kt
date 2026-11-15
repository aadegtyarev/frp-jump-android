package ru.adsrv.frpjump.assistant

import com.termux.terminal.TerminalEmulator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.adsrv.frpjump.agent.admitSideEffect
import ru.adsrv.frpjump.assistant.chat.receiptText
import ru.adsrv.frpjump.sessions.sessionHidden
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ssh.execOutputTail
import ru.adsrv.frpjump.ssh.tmuxCapturePaneCommand
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
     * connection. Never opens one. Blocking and interruptible; throws when the command fails.
     */
    fun exec(command: String, maxBytes: Int): String?
    /** The terminal buffer's text: the last [lines] history rows and the visible screen. Called on the main thread. */
    fun bufferText(lines: Int): String
}

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
    override fun exec(command: String, maxBytes: Int): String? = session.openClient()?.let { execOutputTail(it, command, maxBytes) }
    override fun bufferText(lines: Int): String = terminalBufferText(session.terminal.session.emulator, lines)
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

/**
 * The owner's open terminal tabs for `terminal_tabs` and `terminal_read`: the tabs the owner has not hidden (a closed tab with its Undo
 * on screen is gone from the lists as well), read only through the tab's own existing connection or its terminal buffer. There is no
 * connector here: no new login, no new tunnel, nothing typed into a tab.
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
) {
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
        return coroutineScope {
            probed.map { tab ->
                async {
                    val name = tab.tmuxName ?: return@async null
                    val answer = try {
                        withTimeoutOrNull(probeTimeoutMs) { runInterruptible(io) { tab.exec(tmuxPaneProgramCommand(name), PROBE_MAX_BYTES) } }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
                    program(answer.orEmpty())?.let { tab.id to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }
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
        val text = withTimeoutOrNull(readTimeoutMs) {
            runInterruptible(io) { handle.exec(tmuxCapturePaneCommand(name, lines), READ_MAX_BYTES) }
        } ?: throw java.io.IOException("tab read failed")
        return TabRead(tab, text)
    }

    companion object {
        /** The admission category of a read over a tab's connection (see [admitSideEffect]). */
        const val DISPATCH = "tmux-dispatch"
        const val PROBE_TIMEOUT_MS = 3_000L
        const val READ_TIMEOUT_MS = 10_000L
        private const val PROBE_MAX_BYTES = 1_024
        private const val READ_MAX_BYTES = 1 shl 20
        const val PROGRAM_CHARS = 64

        /** A program name from tmux's answer: its first line, printable characters only, at most [PROGRAM_CHARS]; null when nothing is left. */
        fun program(raw: String): String? = receiptText(raw.lineSequence().firstOrNull().orEmpty(), PROGRAM_CHARS).takeIf { it.isNotEmpty() }
    }
}
