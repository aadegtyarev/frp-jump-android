package ru.adsrv.frpjump.assistant

import com.termux.terminal.RemoteTerminal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import ru.adsrv.frpjump.ssh.NameSetter
import ru.adsrv.frpjump.ssh.SessionKey
import ru.adsrv.frpjump.ssh.SessionOpener
import ru.adsrv.frpjump.ssh.SessionRecordStore
import ru.adsrv.frpjump.ssh.SshAuth
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

/** What attaching a session over its tunnel came to. */
internal enum class AttachResult {
    /** It is open (also when it already was). */
    ATTACHED,
    /** Its tmux session no longer runs on the device (or its saved tunnel is gone): its record is forgotten. */
    GONE,
    /** Its saved login is another user's than the record's, or needs the ordinary SSH UI: nothing is offered. */
    NEEDS_SETUP,
    /** The tunnel is off, the switch is off, or the connection did not come up. */
    UNREACHABLE,
}

/** What the owner is told in a notice (the app words it). */
enum class SessionNotice { TUNNEL_GONE, CLOSE_TUNNEL_OFF }

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
    /** An attach-only session found its tmux session gone. */
    val sessionGone: Boolean
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
    /**
     * Opens (and starts connecting) a session for the assistant, as [spec] says; [id] one [reserveId] gave. With [tmuxName] it is that remembered tmux
     * session; with [attachOnly] it only attaches to it (a session that is gone is not created again).
     */
    fun open(spec: OpenSpec, id: Int? = null, tmuxName: String? = null, attachOnly: Boolean = false): HostedSession
    /** A fresh session in place of the ended one [id] (the same login and tmux session); null when there is none. */
    fun reconnect(id: Int): HostedSession?
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
    /** The saved connection is removed (not merely its tunnel off); never while the profiles are loading. */
    fun connectionGone(profile: String, device: String, port: Int): Boolean
}

/**
 * The assistant's own terminal sessions: ONE kind of session as the owner's tabs ([SshSession], its buffer and the cursor reads), opened by the assistant and not
 * shown as a tab until the owner opens one.
 *
 * The session records ([SessionRecordStore]) are the ONLY owner of which assistant sessions exist: [entries], the listing, [count] and the limit derive from them,
 * and a live session is attached to its record by its key and only supplies the state. A record goes away only when the tmux session really ended (killed by a
 * close, or a successful listing without it, or found gone when attaching) or when its saved connection was removed (then the session stays on the device and the
 * owner is told). After a restart the sessions are detached and attached over their tunnel at their first use ([attach]); nothing here starts a tunnel, creates a
 * session when attaching, or offers a login for another user.
 */
class AssistantSessions internal constructor(
    private val ssh: SessionHost,
    private val targets: SessionTargets,
    private val records: SessionRecordStore,
    private val scope: CoroutineScope,
    /** Tells the owner outside the chat that the assistant needs him ([ask]); the chat shows the card from [asks]. */
    private val notify: (OwnerAsk) -> Unit = {},
    /** Takes that notification back when the ask is answered or its session is gone. */
    private val cancelNotify: (Int) -> Unit = {},
    private val notice: (SessionNotice) -> Unit = {},
    /** Whether the owner's «Свои сессии» switch is on: the assistant attaches over a tunnel only while it is. */
    private val enabled: () -> Boolean = { true },
    private val main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The longest a session may take to come up or to be reached. */
    private val connectWaitMs: Long = CONNECT_WAIT_MS,
    /** The owner's limit of one tool call, read at use: the connect wait is cut to fit inside it ([withinCall]). */
    private val toolTimeoutMs: () -> Long = { ru.adsrv.frpjump.settings.AssistantLimits().toolTimeoutSeconds * 1000L },
    /** A profile's name for the cards (the id when it has none). */
    private val profileName: (String) -> String? = { null },
) {
    /** One assistant session, from its record: [id] is its tab id, the same live or not. */
    class Entry(val id: Int, val key: SessionKey, val name: TabName?)

    private val ids = HashMap<SessionKey, Int>()
    private val attaching = Mutex()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    private val _asks = MutableStateFlow<List<OwnerAsk>>(emptyList())

    /** The assistant's sessions as the records say, as they change: what Sessions, `terminal_tabs` and the limit use. */
    val entries: StateFlow<List<Entry>> = _entries

    /** The asks the owner has not answered yet, oldest first. */
    val asks: StateFlow<List<OwnerAsk>> = _asks

    init {
        scope.launch(io) { records.changed.collect { reload() } }
    }

    /** Reads the records again (at start and whenever they change). Any thread. */
    fun refresh() { scope.launch(io) { reload() } }

    private suspend fun reload() {
        val stored = records.assistantSessions()
        withContext(main) {
            val list = stored.map { (key, record) ->
                val live = ssh.all().firstOrNull { it.key == key }
                Entry(live?.id ?: ids.getOrPut(key) { ssh.reserveId() }, key, record.name ?: live?.name)
            }
            list.forEach { ids[it.key] = it.id }
            _entries.value = list
            // An ask whose session is gone goes with it, and so does its notification.
            val present = list.map { it.id }.toSet()
            _asks.value.filter { it.id !in present }.forEach { cancelNotify(it.id) }
            _asks.update { asks -> asks.filter { it.id in present } }
        }
    }

    /** The entry of tab [id]: by its id, or by the key of the live session that has it. Main thread. */
    private fun entryFor(id: Int): Entry? =
        _entries.value.firstOrNull { it.id == id } ?: ssh.find(id)?.key?.let { key -> _entries.value.firstOrNull { it.key == key } }

    /** The live session of [entry], if it is attached. Main thread. */
    private fun live(entry: Entry): HostedSession? = ssh.all().firstOrNull { it.key == entry.key }

    /**
     * The assistant's sessions as tab handles for the listing: one per record, whether it is attached ([liveTab] gives the tab of the live session with that id) or
     * detached. A session is never listed twice: the records are the list. Main thread.
     */
    internal fun handles(liveTab: (Int) -> TabHandle?): List<TabHandle> = _entries.value.map { entry ->
        live(entry)?.let { liveTab(it.id) } ?: DetachedTab(entry, this)
    }

    /** A remembered session's name is set or cleared ([name] valid, see `checkTabName`; null clears it) by the assistant: written to its record. Main thread. */
    internal fun rename(entry: Entry, name: String?) {
        scope.launch(io) {
            if (name == null) records.clearName(entry.key) else records.put(entry.key, name, NameSetter.ASSISTANT, SessionOpener.ASSISTANT)
        }
    }

    /** How many sessions the assistant has: its records. Main thread. */
    fun count(): Int = _entries.value.size

    /** Whether tab [id] is any session the app has (the owner's tab or the assistant's). Main thread. */
    fun exists(id: Int): Boolean = ssh.find(id) != null || _entries.value.any { it.id == id }

    /** Whether tab [id] is a session of the assistant's. Main thread. */
    fun isAssistant(id: Int): Boolean = entryFor(id) != null

    /**
     * Opens a session on the saved SSH connection [ids] for the assistant, if fewer than [limit] exist: it logs in with the tunnel's stored login
     * (a tunnel that is not on is not started, a login that needs the dialog is not asked for), starts tmux on the device, is remembered at once and is not shown
     * as a tab. A Stop in the middle closes the half-open session.
     */
    internal suspend fun open(target: SavedTunnelIds, limit: Int): OpenResult = withContext(main) {
        if (count() >= limit) return@withContext OpenResult.TooMany(count())
        val spec = when (val r = targets.spec(target.profileId, target.connectionId)) {
            is SpecResult.Ready -> r.spec
            SpecResult.Unavailable -> return@withContext OpenResult.Unavailable
            SpecResult.NeedsSetup -> return@withContext OpenResult.NeedsSetup
        }
        val session = ssh.open(spec)
        var done = false
        try {
            val state = withTimeoutOrNull(withinCall(connectWaitMs, toolTimeoutMs())) { session.state.first { it is SshState.Open || it is SshState.Ended || it is SshState.HostKeyUnknown || it is SshState.HostKeyChanged } }
            val key = session.key
            val result = when {
                state is SshState.Open && session.tmuxInUse && key != null -> {
                    // Remembered at once (the session's own write comes a moment later): the limit and the listing see it now.
                    withContext(io) { records.put(key, null, openedBy = SessionOpener.ASSISTANT) }
                    ids[key] = session.id
                    _entries.update { list -> list.filter { it.key != key } + Entry(session.id, key, null) }
                    OpenResult.Opened(session.id, session.user, session.device)
                }
                state is SshState.Open -> OpenResult.NoTmux
                state is SshState.HostKeyUnknown || state is SshState.HostKeyChanged -> OpenResult.NeedsSetup
                else -> OpenResult.ConnectFailed
            }
            if (result !is OpenResult.Opened) ssh.close(session.id, endTmux = false)
            done = true
            result
        } finally {
            // Stopped while waiting: the half-open session goes (its tmux session with it when the connection is up), never left behind unlisted.
            if (!done) withContext(NonCancellable + main) { ssh.close(session.id, endTmux = true) }
        }
    }

    /**
     * Attaches the remembered session [id] over its tunnel, for [byOwner] (he opened or closes it) or for the assistant's own use (only while the switch is on). It
     * ONLY attaches: a tmux session that is gone is not created again (its record is forgotten), a login of another user than the record's is not offered, a tunnel
     * is not started. A session that is live but ended is reconnected. Never throws.
     */
    internal suspend fun attach(id: Int, byOwner: Boolean = false): AttachResult = attaching.withLock {
        withContext(main) {
            if (!byOwner && !enabled()) return@withContext AttachResult.UNREACHABLE
            val entry = entryFor(id) ?: return@withContext AttachResult.UNREACHABLE
            val key = entry.key
            val existing = live(entry)
            if (existing != null && existing.state.value is SshState.Open) return@withContext AttachResult.ATTACHED
            val connection = targets.connectionAt(key.profile, key.device, key.port)
            if (connection == null) {
                if (targets.connectionGone(key.profile, key.device, key.port)) {
                    // Its saved tunnel was removed: the session stays on the device, and the owner is told.
                    withContext(io) { records.forget(key) }
                    existing?.let { ssh.close(it.id, endTmux = false) }
                    _entries.update { list -> list.filter { it.key != key } }
                    notice(SessionNotice.TUNNEL_GONE)
                    return@withContext AttachResult.GONE
                }
                return@withContext AttachResult.UNREACHABLE
            }
            val spec = when (val r = targets.spec(key.profile, connection)) {
                is SpecResult.Ready -> r.spec
                SpecResult.Unavailable -> return@withContext AttachResult.UNREACHABLE
                SpecResult.NeedsSetup -> return@withContext AttachResult.NEEDS_SETUP
            }
            // Never one user's credentials for another's session.
            if (spec.user != key.user) return@withContext AttachResult.NEEDS_SETUP
            val session = when {
                existing != null -> ssh.reconnect(existing.id) ?: return@withContext AttachResult.UNREACHABLE
                else -> ssh.open(OpenSpec(spec.profile, spec.hostKeys, key.device, key.port, spec.localPort, key.user, spec.auth), id = entry.id, tmuxName = key.session, attachOnly = true)
            }
            entry.name?.let(session::restoreName)
            val state = withTimeoutOrNull(withinCall(connectWaitMs, toolTimeoutMs())) { session.state.first { it is SshState.Open || it is SshState.Ended || it is SshState.HostKeyUnknown || it is SshState.HostKeyChanged } }
            when {
                state is SshState.Open -> AttachResult.ATTACHED
                session.sessionGone -> {
                    ssh.close(session.id, endTmux = false)
                    withContext(io) { records.forget(key) }
                    _entries.update { list -> list.filter { it.key != key } }
                    AttachResult.GONE
                }
                else -> { ssh.close(session.id, endTmux = false); AttachResult.UNREACHABLE }
            }
        }
    }

    /** The owner opens the assistant's session [id] as a tab (attaching it first when it is detached); the live session, or null when it cannot be. */
    internal suspend fun show(id: Int): HostedSession? {
        if (attach(id, byOwner = true) != AttachResult.ATTACHED) return null
        dismissAsk(id)
        return withContext(main) { entryFor(id)?.let { live(it) }?.let { ssh.show(it.id) } }
    }

    /**
     * Ends the assistant's session [id] with its tmux session and anything in it ([CloseResult]). A detached session is attached first, which needs its tunnel; one
     * found gone is closed as well (nothing left to end).
     */
    internal suspend fun close(id: Int): CloseResult {
        val entry = withContext(main) { entryFor(id) }
        if (entry == null) return if (withContext(main) { ssh.find(id) } != null) CloseResult.NotYours else CloseResult.Unknown
        return when (attach(id, byOwner = true)) {
            AttachResult.ATTACHED -> {
                withContext(main) { live(entry)?.let { ssh.close(it.id, endTmux = true) } }
                withContext(main) { _entries.update { list -> list.filter { it.key != entry.key } } }
                dismissAsk(id)
                CloseResult.Closed
            }
            AttachResult.GONE -> { dismissAsk(id); CloseResult.Closed }
            AttachResult.NEEDS_SETUP, AttachResult.UNREACHABLE -> CloseResult.Unreachable
        }
    }

    /**
     * The owner's Close of the assistant's session [id] (the shared Undo has run out): the same, no confirmation. A session that cannot be reached stays listed
     * and the owner is told; never silent.
     */
    suspend fun closeByOwner(id: Int) {
        if (close(id) == CloseResult.Unreachable) notice(SessionNotice.CLOSE_TUNNEL_OFF)
    }

    /** What the card of a close shows of session [id]; null when it is not the assistant's. Pure lookup of app state, main thread. */
    internal fun describe(id: Int): CloseCard? {
        val entry = entryFor(id) ?: return null
        val live = live(entry)
        return CloseCard(entry.id, profileName(entry.key.profile) ?: entry.key.profile, entry.key.user, entry.key.device,
            live?.number ?: tmuxNumber(entry.key.session).toInt().coerceAtLeast(1), entry.name?.text ?: live?.name?.text)
    }

    /** What session [id] is now, to compare when the call runs with what the owner was asked about; null when it is not a session of the assistant's. */
    internal fun pin(id: Int): Any? = entryFor(id)?.let { entry -> id to (ssh.find(id)?.key ?: entry.key) }

    /** Asks the owner to act in session [id] (the chat shows a card, a notification says so when the chat is not on screen); false when it is not the assistant's. */
    internal fun ask(id: Int, reason: String): Boolean {
        val card = describe(id) ?: return false
        val ask = OwnerAsk(id, reason, card.profile.orEmpty(), card.user.orEmpty(), card.device.orEmpty(), card.number ?: 0, card.name)
        _asks.update { list -> list.filter { it.id != id } + ask }
        notify(ask)
        return true
    }

    /** The owner has seen the ask of session [id] (opened it or dismissed it): its card and its notification go. */
    fun dismissAsk(id: Int) {
        if (_asks.value.any { it.id == id }) cancelNotify(id)
        _asks.update { list -> list.filter { it.id != id } }
    }

    companion object {
        const val CONNECT_WAIT_MS = 20_000L
    }
}

/** A remembered assistant session as a tab handle: listed, with no connection and no buffer until it is attached. */
internal class DetachedTab(private val entry: AssistantSessions.Entry, private val owner: AssistantSessions) : TabHandle {
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
    override val sessionGone get() = session.sessionGone
    override fun restoreName(name: TabName) = session.restoreTabName(name)
}

/** The app's [SshSessions] as the host of the assistant's sessions. */
internal class SshSessionHost(private val ssh: SshSessions) : SessionHost {
    override fun all() = ssh.allNow().map(::SshHosted)
    override fun reserveId() = ssh.reserveId()
    override fun open(spec: OpenSpec, id: Int?, tmuxName: String?, attachOnly: Boolean): HostedSession = SshHosted(
        ssh.open(spec.profile, checkNotNull(spec.hostKeys), spec.device, spec.port, spec.localPort, spec.user, checkNotNull(spec.auth), SessionOpener.ASSISTANT, id, tmuxName, attachOnly))
    override fun reconnect(id: Int): HostedSession? = ssh.reconnect(id)?.let(::SshHosted)
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
    override fun connectionGone(profile: String, device: String, port: Int) = targets.connectionGone(profile, device, port)
}
