package ru.adsrv.frpjump.assistant

import com.termux.terminal.RemoteTerminal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds
import ru.adsrv.frpjump.ssh.ExecLease
import ru.adsrv.frpjump.ssh.HostKeyStore
import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SessionKey
import ru.adsrv.frpjump.ssh.SessionOpener
import ru.adsrv.frpjump.ssh.SessionRecordStore
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ssh.SshTargetResolution
import ru.adsrv.frpjump.ssh.SshTargets
import ru.adsrv.frpjump.ssh.TabName
import ru.adsrv.frpjump.ssh.tmuxNumber

/** How `terminal_open` ended. */
internal sealed interface OpenResult {
    /** The session is open and runs tmux: its tab id and where it is. */
    class Opened(val id: Int, val user: String, val device: String) : OpenResult
    /** [atOnce] sessions of the assistant exist already, the most the owner allows. */
    class TooMany(val atOnce: Int) : OpenResult
    /** The target is not a saved SSH tunnel that is on and ready (no tunnel was started). */
    data object Unavailable : OpenResult
    /** The login or the host's key needs the ordinary SSH UI first. */
    data object NeedsSetup : OpenResult
    /** The connection did not come up. */
    data object ConnectFailed : OpenResult
    /** The device has no tmux that works, so a session cannot be kept. */
    data object NoTmux : OpenResult
}

/** How `terminal_close` ended. */
internal sealed interface CloseResult {
    data object Closed : CloseResult
    /** Not a session of the assistant's: an owner's tab, which only the owner closes. */
    data object NotYours : CloseResult
    data object Unknown : CloseResult
    /** The session could not be reached to end its tmux session; nothing was closed. */
    data object Unreachable : CloseResult
}

/** What the owner is asked for: act in the assistant's session [id] ([reason] is the model's text, shown as it is after cleaning). */
class OwnerAsk(val id: Int, val reason: String, val profile: String, val user: String, val device: String, val number: Int, val name: String?) {
    override fun toString() = "OwnerAsk(id=$id)"
}

/** The card of a close the owner is asked about: the session, as a tab's identity. */
class CloseCard(val tabId: Int, val profile: String?, val user: String?, val device: String?, val number: Int?, val name: String?) {
    override fun toString() = "CloseCard(tab=$tabId)"
}

/** One session the app has, as the assistant's sessions need it: the same object as an owner's tab ([SshSession] in the app; a test passes its own). */
internal interface HostedSession {
    val id: Int
    val profile: String
    val user: String
    val device: String
    val port: Int
    val number: Int
    val openedBy: SessionOpener
    val shown: Boolean
    val state: StateFlow<SshState>
    /** The connection now open runs tmux (the session survives the connection). */
    val tmuxInUse: Boolean
    /** Where it is remembered (its login and tmux session), once it has a tmux session. */
    val key: SessionKey?
    val name: TabName?
    /** Takes [name], remembered for this session, unless it was named meanwhile. */
    fun restoreName(name: TabName)
}

/** Where a session logs in: what [SessionHost.open] needs of a saved SSH connection whose tunnel is on. */
internal class OpenSpec(val profile: String, val hostKeys: HostKeyStore?, val device: String, val port: Int, val localPort: Int, val user: String, val auth: SshAuth?)

/** The app's sessions as the assistant's sessions use them ([SshSessions] in the app). Main thread. */
internal interface SessionHost {
    fun all(): List<HostedSession>
    fun find(id: Int): HostedSession? = all().firstOrNull { it.id == id }
    /** An id for a session that does not exist yet; never given twice. */
    fun reserveId(): Int
    /** Opens (and starts connecting) a session for the assistant, as [spec] says, on [tmuxName] when it is the one remembered; [id] one [reserveId] gave. */
    fun open(spec: OpenSpec, id: Int? = null, tmuxName: String? = null): HostedSession
    fun close(id: Int, endTmux: Boolean)
    /** The owner has the assistant's session [id] as a tab now. */
    fun show(id: Int): HostedSession?
}

/** How a saved SSH connection may be opened without a dialog, or why not. */
internal sealed interface SpecResult {
    class Ready(val spec: OpenSpec) : SpecResult
    /** Not a saved SSH tunnel that is on and ready (no tunnel is started). */
    data object Unavailable : SpecResult
    /** The login or the host's key needs the ordinary SSH UI first. */
    data object NeedsSetup : SpecResult
}

/** What the assistant's sessions ask of the saved connections ([SshTargets] in the app). Main thread. */
internal interface SessionTargets {
    fun spec(profile: String, connection: String): SpecResult
    /** The saved connection of [profile] to [device]:[port] (a remembered session finds its tunnel again by it); null when there is none. */
    fun connectionAt(profile: String, device: String, port: Int): String?
}

/**
 * The assistant's own terminal sessions: ONE kind of session as the owner's tabs ([SshSession], its buffer and the cursor reads), opened by the
 * assistant and not shown as a tab until the owner opens one. Remembered in [records] ([SessionRecordStore]) so that after a restart they are
 * listed again as detached, and attached over their tunnel at their first use ([attach]). Nothing here starts a tunnel or types a password.
 */
class AssistantSessions internal constructor(
    private val ssh: SessionHost,
    private val targets: SessionTargets,
    private val records: SessionRecordStore,
    private val scope: CoroutineScope,
    /** Tells the owner outside the chat that the assistant needs him ([ask]); the chat shows the card from [asks]. */
    private val notify: (OwnerAsk) -> Unit = {},
    private val main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The longest a session may take to come up or to be reached. */
    private val connectWaitMs: Long = CONNECT_WAIT_MS,
    /** A profile's name for the cards (the id when it has none). */
    private val profileName: (String) -> String? = { null },
) {
    /** A remembered assistant session that is not attached: [id] is the tab id it keeps when it is. */
    class Remembered(val id: Int, val key: SessionKey, val name: TabName?)

    private val remembered = MutableStateFlow<List<Remembered>>(emptyList())

    /** The remembered sessions that are not attached, as they change (Sessions lists them as detached). */
    val detached: StateFlow<List<Remembered>> = remembered
    private val ids = HashMap<SessionKey, Int>()
    private val attaching = Mutex()
    private val _asks = MutableStateFlow<List<OwnerAsk>>(emptyList())

    /** The asks the owner has not answered yet, oldest first. */
    val asks: StateFlow<List<OwnerAsk>> = _asks

    /** Reads the records: at start, and after anything that changes them. Any thread. */
    fun refresh() {
        scope.launch(io) {
            val stored = records.assistantSessions()
            withContext(main) {
                val live = ssh.all().mapNotNull { s -> s.key }.toSet()
                remembered.value = stored.filter { (key, _) -> key !in live }.map { (key, record) -> Remembered(ids.getOrPut(key) { ssh.reserveId() }, key, record.name) }
            }
        }
    }

    /** The sessions remembered and not attached, as tab handles for the listing. Main thread. */
    internal fun detachedHandles(): List<TabHandle> = remembered.value.map { DetachedTab(it, this) }

    /** A remembered session's name is set or cleared ([name] valid, see `checkTabName`; null clears it) by the assistant: written to its record. Main thread. */
    internal fun rename(entry: Remembered, name: String?) {
        remembered.update { list -> list.map { if (it.id == entry.id) Remembered(it.id, it.key, name?.let { n -> TabName(n, ru.adsrv.frpjump.ssh.NameSetter.ASSISTANT) }) else it } }
        scope.launch(io) {
            if (name == null) records.clearName(entry.key) else records.put(entry.key, name, ru.adsrv.frpjump.ssh.NameSetter.ASSISTANT, SessionOpener.ASSISTANT)
        }
    }

    /** The assistant's live sessions and the remembered ones: what counts against the owner's limit. Main thread. */
    fun count(): Int = ssh.all().count { it.openedBy == SessionOpener.ASSISTANT && it.state.value !is SshState.Ended } + remembered.value.size

    /** Whether tab [id] is any session the app has (the owner's tab or the assistant's, live or remembered). Main thread. */
    fun exists(id: Int): Boolean = ssh.find(id) != null || remembered.value.any { it.id == id }

    /** Whether tab [id] is a session of the assistant's, live or remembered. Main thread. */
    fun isAssistant(id: Int): Boolean = ssh.find(id)?.openedBy == SessionOpener.ASSISTANT || remembered.value.any { it.id == id }

    /**
     * Opens a session on the saved SSH connection [ids] for the assistant, if fewer than [limit] exist: it logs in with the tunnel's stored login
     * (a tunnel that is not on is not started, a login that needs the dialog is not asked for), starts tmux on the device and is not shown as a tab.
     */
    internal suspend fun open(ids: SavedTunnelIds, limit: Int): OpenResult = withContext(main) {
        if (count() >= limit) return@withContext OpenResult.TooMany(count())
        val spec = when (val r = targets.spec(ids.profileId, ids.connectionId)) {
            is SpecResult.Ready -> r.spec
            SpecResult.Unavailable -> return@withContext OpenResult.Unavailable
            SpecResult.NeedsSetup -> return@withContext OpenResult.NeedsSetup
        }
        val session = ssh.open(spec)
        val state = withTimeoutOrNull(connectWaitMs) { session.state.first { it is SshState.Open || it is SshState.Ended || it is SshState.HostKeyUnknown || it is SshState.HostKeyChanged } }
        when {
            state is SshState.Open && session.tmuxInUse -> OpenResult.Opened(session.id, session.user, session.device)
            state is SshState.Open -> { ssh.close(session.id, endTmux = false); OpenResult.NoTmux }
            state is SshState.HostKeyUnknown || state is SshState.HostKeyChanged -> { ssh.close(session.id, endTmux = false); OpenResult.NeedsSetup }
            else -> { ssh.close(session.id, endTmux = false); OpenResult.ConnectFailed }
        }
    }

    /**
     * Attaches the remembered session [id] over its tunnel (which must be on; none is started): it becomes a live session with the same id and the
     * same tmux session, and stays not shown. True when it is open afterwards (also when it already was). Never throws.
     */
    internal suspend fun attach(id: Int): Boolean = attaching.withLock {
        withContext(main) {
            ssh.find(id)?.let { return@withContext it.state.value is SshState.Open }
            val entry = remembered.value.firstOrNull { it.id == id } ?: return@withContext false
            val key = entry.key
            val connection = targets.connectionAt(key.profile, key.device, key.port) ?: return@withContext false
            val spec = (targets.spec(key.profile, connection) as? SpecResult.Ready)?.spec ?: return@withContext false
            val session = ssh.open(OpenSpec(spec.profile, spec.hostKeys, key.device, key.port, spec.localPort, key.user.ifEmpty { spec.user }, spec.auth), id = id, tmuxName = key.session)
            entry.name?.let(session::restoreName)
            val state = withTimeoutOrNull(connectWaitMs) { session.state.first { it is SshState.Open || it is SshState.Ended || it is SshState.HostKeyUnknown || it is SshState.HostKeyChanged } }
            if (state is SshState.Open) {
                remembered.update { list -> list.filter { it.id != id } }
                true
            } else { ssh.close(id, endTmux = false); false }
        }
    }

    /** The owner opens the assistant's session [id] as a tab (attaching it first when it is remembered); the live session, or null when it cannot be. */
    internal suspend fun show(id: Int): HostedSession? {
        if (!attach(id)) return null
        _asks.update { list -> list.filter { it.id != id } }
        return withContext(main) { ssh.show(id) }
    }

    /**
     * Ends the assistant's session [id] with its tmux session and anything in it ([CloseResult]). A remembered session is attached first, which needs
     * its tunnel.
     */
    internal suspend fun close(id: Int): CloseResult {
        val live = withContext(main) { ssh.find(id) }
        if (live != null && live.openedBy != SessionOpener.ASSISTANT) return CloseResult.NotYours
        if (live == null && withContext(main) { remembered.value.none { it.id == id } }) return CloseResult.Unknown
        if (!attach(id)) return CloseResult.Unreachable
        withContext(main) { ssh.close(id, endTmux = true) }
        _asks.update { list -> list.filter { it.id != id } }
        remembered.update { list -> list.filter { it.id != id } }
        return CloseResult.Closed
    }

    /** The owner's Close of the assistant's session [id] (the shared Undo has run out): the same, no confirmation. */
    suspend fun closeByOwner(id: Int) { close(id) }

    /** What the card of a close shows of session [id]; null when it is not open or not the assistant's. Pure lookup of app state, main thread. */
    internal fun describe(id: Int): CloseCard? {
        ssh.find(id)?.takeIf { it.openedBy == SessionOpener.ASSISTANT }?.let { return CloseCard(it.id, profileName(it.profile) ?: it.profile, it.user, it.device, it.number, it.name?.text) }
        return remembered.value.firstOrNull { it.id == id }?.let { CloseCard(it.id, profileName(it.key.profile) ?: it.key.profile, it.key.user, it.key.device, tmuxNumber(it.key.session).toInt(), it.name?.text) }
    }

    /** What session [id] is now, to compare when the call runs with what the owner was asked about; null when it is not a session of the assistant's. */
    internal fun pin(id: Int): Any? = ssh.find(id)?.takeIf { it.openedBy == SessionOpener.ASSISTANT }?.let { it.id to it.key } ?: remembered.value.firstOrNull { it.id == id }?.let { it.id to it.key }

    /** Asks the owner to act in session [id] (the chat shows a card, a notification says so when the chat is not on screen); false when it is not the assistant's. */
    internal fun ask(id: Int, reason: String): Boolean {
        val card = describe(id) ?: return false
        val ask = OwnerAsk(id, reason, card.profile.orEmpty(), card.user.orEmpty(), card.device.orEmpty(), card.number ?: 0, card.name)
        _asks.update { list -> list.filter { it.id != id } + ask }
        notify(ask)
        return true
    }

    /** The owner has seen the ask of session [id] (opened it or dismissed it). */
    fun dismissAsk(id: Int) { _asks.update { list -> list.filter { it.id != id } } }

    companion object {
        const val CONNECT_WAIT_MS = 20_000L
    }
}

/** A remembered assistant session as a tab handle: listed, with no connection and no buffer until it is attached. */
internal class DetachedTab(private val entry: AssistantSessions.Remembered, private val owner: AssistantSessions) : TabHandle {
    override val id get() = entry.id
    override val profileId get() = entry.key.profile
    override val device get() = entry.key.device
    override val port get() = entry.key.port
    override val user get() = entry.key.user
    override val number get() = tmuxNumber(entry.key.session).toInt().coerceAtLeast(1)
    override val state: SshState get() = SshState.Connecting
    override val tmuxName get() = entry.key.session
    override val openedBy get() = SessionOpener.ASSISTANT
    override val shown get() = false
    override val detached get() = true
    override val customName get() = entry.name?.text
    override fun rename(name: String?) = owner.rename(entry, name)
    override fun exec(command: String, maxBytes: Int, lease: ExecLease): String? = null
    override fun bufferSource(mode: ReadMode): SourceText = throw IllegalStateException("not attached")
    override fun type(bytes: ByteArray, pin: InputPin) = RemoteTerminal.TypeOutcome.REJECTED
}

/** [SshSession] as a [HostedSession]. */
internal class SshHosted(private val session: SshSession) : HostedSession {
    override val id get() = session.id
    override val profile get() = session.profile
    override val user get() = session.user
    override val device get() = session.device
    override val port get() = session.port
    override val number get() = session.number
    override val openedBy get() = session.openedBy
    override val shown get() = session.shown.value
    override val state get() = session.state
    override val tmuxInUse get() = session.tmux.inUse
    override val key get() = session.tabNameKey()
    override val name get() = session.tabName.value
    override fun restoreName(name: TabName) = session.restoreTabName(name)
}

/** The app's [SshSessions] as the host of the assistant's sessions. */
internal class SshSessionHost(private val ssh: SshSessions) : SessionHost {
    override fun all() = ssh.allNow().map(::SshHosted)
    override fun reserveId() = ssh.reserveId()
    override fun open(spec: OpenSpec, id: Int?, tmuxName: String?): HostedSession = SshHosted(
        ssh.open(spec.profile, checkNotNull(spec.hostKeys), spec.device, spec.port, spec.localPort, spec.user, checkNotNull(spec.auth), SessionOpener.ASSISTANT, id, tmuxName))
    override fun close(id: Int, endTmux: Boolean) = ssh.close(id, endTmux = endTmux)
    override fun show(id: Int): HostedSession? = ssh.show(id)?.let(::SshHosted)
}

/** The app's [SshTargets] as the targets of the assistant's sessions: a saved SSH tunnel that is on and logs in without a dialog. */
internal class SshSessionTargets(private val targets: SshTargets) : SessionTargets {
    override fun spec(profile: String, connection: String): SpecResult {
        val target = (targets.resolveSaved(profile, connection) as? SshTargetResolution.Ready)?.target ?: return SpecResult.Unavailable
        val (user, auth) = targets.directSession(target) ?: return SpecResult.NeedsSetup
        val saved = target.connection
        return SpecResult.Ready(OpenSpec(target.profile, target.runtime.hostKeys, saved.device, saved.port, target.tunnel.localPort, user, auth))
    }
    override fun connectionAt(profile: String, device: String, port: Int) = targets.savedConnectionAt(profile, device, port)
}
