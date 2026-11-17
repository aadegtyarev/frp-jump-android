package ru.adsrv.frpjump.ssh

import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.RemoteClipboardClient
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import ru.adsrv.frpjump.ssh.diagnostics.TerminalSessionReport
import ru.adsrv.frpjump.ssh.diagnostics.TerminalMetricsSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import java.io.IOException
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.notices.Notices
import ru.adsrv.frpjump.ports.profilePortKey
import ru.adsrv.frpjump.sessions.sessionHidden
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.SessionOn
import ru.adsrv.frpjump.tunnel.TunnelRef

/** What the terminal screen hears about the sessions it shows. */
interface TerminalScreenListener {
    fun onScreenChanged(session: TerminalSession)

    fun onCursorStateChanged(visible: Boolean)

    /** A multi-line paste into [session] with no bracketed paste: the screen asks before it goes. */
    fun onPasteAsk(session: SshSession, ask: PastePlan.Ask)

    /** Paste with a picture and no text on the clipboard: the screen offers to send it to [session]'s device. */
    fun onPasteImage(session: SshSession) = Unit
}

/**
 * The app's SSH sessions. They outlive the terminal screen; a session ends
 * when its tunnel goes away (Disconnect, Disconnect all, Forget), so the
 * foreground service that keeps the tunnels also keeps the sessions.
 */
class SshSessions(
    private val context: Context,
    private val notices: Notices,
    private val env: SshEnvironment,
    /** Every tunnel up, in all profiles, as [profilePortKey]s. */
    tunnelsUp: Flow<Set<String>>,
    private val scope: CoroutineScope,
    /** The default network's identity, null while there is none: each new one makes reconnecting sessions try at once. */
    network: Flow<String?> = flowOf(null),
    /** [ru.adsrv.frpjump.undo.PendingUndo.hidden]: a tab closed, or its tunnel disconnected, with Undo on screen does not reconnect. */
    hidden: Flow<Set<String>> = flowOf(emptySet()),
) {
    /** Every session, the assistant's own included. */
    private val _all = MutableStateFlow<List<SshSession>>(emptyList())

    /** The sessions the owner has as tabs, as they change: the owner's own and the assistant's that the owner opened ([SshSession.shown]). */
    private val _sessions = MutableStateFlow<List<SshSession>>(emptyList())
    val sessions: StateFlow<List<SshSession>> = _sessions

    /** Every session including the assistant's own that the owner has not opened; as it changes, and [allNow]. */
    val all: StateFlow<List<SshSession>> = _all

    /** [all] as it is now. */
    fun allNow(): List<SshSession> = _all.value

    /** Applies [change] to the list of every session, and publishes the owner's tabs from it. Main thread. */
    private fun update(change: (List<SshSession>) -> List<SshSession>) {
        _all.update(change)
        publish()
    }

    private fun publish() {
        _sessions.value = _all.value.filter { it.shown.value }
    }
    private val _diagnostics = MutableStateFlow<Map<TunnelRef, List<TerminalSessionReport>>>(emptyMap())
    val diagnostics: StateFlow<Map<TunnelRef, List<TerminalSessionReport>>> = _diagnostics
    private var nextId = 1

    /** Session-bound text composers; never written to disk or saved Android state. */
    val drafts = TerminalDrafts()

    /** Sessions closed because their tunnel went away (ids are never reused); main thread. */
    private val closedByTunnel = mutableSetOf<Int>()

    /** Every session as the connection rows count them: its tunnel, and whether it is still live; as it changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val through: Flow<List<SessionOn>> = _sessions
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(list.map { s -> s.state.map { s.on(it) } }) { it.toList() }
            }
        }
        .distinctUntilChanged()

    /** [through] as it is now. Main thread. */
    fun throughNow(): List<SessionOn> = _sessions.value.map { it.on(it.state.value) }

    private fun SshSession.on(state: SshState) = SessionOn(TunnelRef(profile, device, port), state !is SshState.Ended, SessionKind.TERMINAL, id)

    /** Terminal text size in sp, shared by all sessions. */
    val fontSize = MutableStateFlow(DEFAULT_FONT_SP)

    /** The terminal screen while it is shown; set and cleared on the main thread. */
    var listener: TerminalScreenListener? = null

    // Main-thread authority, retained when the screen leaves or the app backgrounds.
    // Keep only an id, not a removed terminal's history; verify exact identity at delivery.
    private var clipboardSourceId: Int? = null

    /** Record actual tab selection immediately, before another terminal input callback can run. */
    fun selectClipboardSource(id: Int?) {
        clipboardSourceId = id?.takeIf { picked -> _all.value.any { it.id == picked } }
    }

    init {
        // Main-thread reads of Termux state; no StateFlow update per byte/frame.
        // A hidden/removed tab releases markers, and hidden time is not a rate baseline.
        scope.launch(Dispatchers.Main) {
            var previous = emptyList<SshSession>()
            var samples = emptyMap<Int, Pair<Long, TerminalMetricsSnapshot>>()
            try {
                combine(_all, env.visible, hidden) { list, shown, h ->
                    list.filterNot { sessionHidden(h, SessionKind.TERMINAL, it.id, TunnelRef(it.profile, it.device, it.port)) } to shown
                }.collectLatest { (list, shown) ->
                    previous.filterNot { it in list }.forEach { it.terminal.metrics.setObserving(false) }
                    previous = list
                    list.forEach { it.terminal.metrics.setObserving(shown) }
                    fun publish() {
                        val at = System.nanoTime()
                        val next = mutableMapOf<Int, Pair<Long, TerminalMetricsSnapshot>>()
                        _diagnostics.value = list.groupBy { TunnelRef(it.profile, it.device, it.port) }.mapValues { (_, tabs) ->
                            tabs.map { tab ->
                                val metrics = tab.terminal.metrics
                                val epoch = metrics.currentGeneration()
                                // Opening another tab must not accelerate this tab's numeric sampling.
                                val cached = samples[tab.id]?.takeIf {
                                    it.first == epoch && it.second.observed == shown && at - it.second.atNs < 1_000_000_000L
                                }
                                val snapshot = cached?.second ?: run {
                                    val emulator = tab.terminal.session.emulator
                                    metrics.bufferState(emulator.screen.activeTranscriptRows, emulator.isAlternateBufferActive)
                                    metrics.snapshot()
                                }
                                next[tab.id] = snapshot.connectionGeneration to snapshot
                                TerminalSessionReport(tab.id, snapshot)
                            }
                        }
                        samples = next
                    }
                    publish()
                    if (shown && list.isNotEmpty()) while (isActive) { delay(1000); publish() }
                }
            } finally {
                previous.forEach { it.terminal.metrics.setObserving(false) }
                _diagnostics.value = emptyMap()
            }
        }
        scope.launch(Dispatchers.Main) {
            tunnelsUp.distinctUntilChanged().collect { up ->
                _all.value.filter { profilePortKey(it.profile, it.device, it.port) !in up }
                    .forEach {
                        close(it.id, Texts.get(R.string.ssh_tunnel_closed))
                        closedByTunnel += it.id
                    }
            }
        }
        scope.launch {
            network.distinctUntilChanged().filterNotNull().collect { _ -> _all.value.forEach { it.networkReturned() } }
        }
        // A session that comes or goes while one is pending is held or let go as well.
        scope.launch(Dispatchers.Main) {
            combine(_all, hidden) { list, h -> list.map { it to sessionHidden(h, SessionKind.TERMINAL, it.id, TunnelRef(it.profile, it.device, it.port)) } }
                .collect { pairs -> pairs.forEach { (session, held) -> session.hold(held) } }
        }
    }

    /** An id for a session that does not exist yet (a remembered assistant session listed before it is attached); never given twice. Main thread. */
    fun reserveId(): Int = nextId++

    /** The owner opens the assistant's session [id] as a tab; it stays marked as the assistant's. Main thread. */
    fun show(id: Int): SshSession? = find(id)?.also { it.show(); publish() }

    /**
     * Opens a new session to [device]:[port] of [profile] and starts connecting. [openedBy] the assistant: a session of its own with no
     * tab until [show], that never picks up the owner's sessions; [id] one [reserveId] gave. Main thread.
     */
    fun open(profile: String, hostKeys: HostKeyStore, device: String, port: Int, localPort: Int, user: String, auth: SshAuth,
        openedBy: SessionOpener = SessionOpener.OWNER, id: Int? = null, tmuxName: String? = null, attachOnly: Boolean = false): SshSession {
        // A new password typed: the refusal of the old one says nothing about it.
        if (auth is SshAuth.Password && auth.typed != null) env.refusals.clear(profile, device, port, user)
        val target = SshTarget(user, device, port)
        val number = nextSessionNumber(_all.value.map { SshTarget(it.user, it.device, it.port) to it.number }, target)
        // The first tab of this connection in the app: its device may still run tabs of a killed process.
        val first = openedBy == SessionOpener.OWNER && hasNoTab(profile, device, port)
        val session = SshSession(
            id ?: nextId++, profile, hostKeys, device, port, localPort, user, auth, env, scope, ClientForScreen(), number,
            tmuxName = tmuxName, onReattached = ::sayReattached, pickUp = if (first) ::pickUp else null, openedBy = openedBy, attachOnly = attachOnly,
        )
        drafts.open(session.id)
        update { it + session }
        session.connect()
        return session
    }

    /**
     * A fresh session in place of an ended one, in its tab: the same login,
     * or as [user] with [auth] ("Change login"). Main thread.
     */
    fun reconnect(id: Int, user: String? = null, auth: SshAuth? = null): SshSession? {
        val old = _all.value.firstOrNull { it.id == id } ?: return null
        old.close()
        val login = user ?: old.user
        val others = _all.value.filter { it.id != id }.map { SshTarget(it.user, it.device, it.port) to it.number }
        val number = replacementNumber(others, SshTarget(old.user, old.device, old.port), old.number, SshTarget(login, old.device, old.port))
        if (auth is SshAuth.Password && auth.typed != null) env.refusals.clear(old.profile, old.device, old.port, login)
        // The same tab, so the same tmux session: it is attached to again when it still runs.
        // A first tab that never got as far as the pick-up (refused login, host key) still does it.
        val pickUpStill = old.openedBy == SessionOpener.OWNER && old.tmux.assigned == null &&
            _all.value.none { it.id != id && it.openedBy == SessionOpener.OWNER && it.profile == old.profile && it.device == old.device && it.port == old.port }
        // An assistant session keeps its id: the model's tab_id stays valid across a reconnect.
        val freshId = if (old.openedBy == SessionOpener.ASSISTANT) old.id else nextId++
        val fresh = SshSession(
            freshId, old.profile, old.hostKeys, old.device, old.port, old.localPort, login, auth ?: old.auth, env, scope, ClientForScreen(), number,
            tmuxName = old.tmux.assigned, noTmux = old.tmux.disabled, onReattached = ::sayReattached,
            pickUp = if (pickUpStill) ::pickUp else null, tabName = old.tabName.value, openedBy = old.openedBy, attachOnly = old.attachOnly,
        )
        if (old.openedBy == SessionOpener.ASSISTANT && old.shown.value) fresh.show()
        drafts.close(old.id)
        drafts.open(fresh.id)
        update { list -> list.map { if (it.id == id) fresh else it } }
        // After the tries of a drop ran out, the same login tries again the same way.
        fresh.connect(retrying = old.gaveUp && user == null && auth == null)
        return fresh
    }

    /**
     * Another session to [id]'s device and port, as [user] with [auth]
     * (by default the same login), after the others. Main thread.
     */
    fun openLike(id: Int, user: String? = null, auth: SshAuth? = null): SshSession? {
        val from = find(id) ?: return null
        return open(from.profile, from.hostKeys, from.device, from.port, from.localPort, user ?: from.user, auth ?: from.auth)
    }

    /** Whether a password is kept for [user] on [session]'s device and port; cheap, nothing is unsealed. */
    fun hasRememberedPassword(session: SshSession, user: String): Boolean =
        offersSavedPassword(env.refusals.get(session.profile, session.device, session.port, user.trim()), user.trim(), user) { env.passwords.has(session.profile, session.device, session.port, user.trim()) }

    /** An open, logged-in connection of a session as [user] to [device]:[port] of [profile]; any thread. */
    fun liveClient(profile: String, device: String, port: Int, user: String): SSHClient? =
        _all.value.asReversed()
            .filter { it.profile == profile && it.device == device && it.port == port && it.user == user }
            .firstNotNullOfOrNull { it.openClient() }

    /**
     * Closes session [id]. [endTmux]: the owner's Close, committed (Undo
     * gone), which also ends the tab's tmux session on the device; a tunnel
     * closing, Forget profile and the like keep it.
     */
    fun close(id: Int, reason: String = Texts.get(R.string.ssh_closed), endTmux: Boolean = false) {
        val session = _all.value.firstOrNull { it.id == id } ?: return
        session.close(reason, endTmux)
        drafts.close(id)
        update { list -> list.filter { it.id != id } }
    }

    fun closeAll() = _all.value.forEach { close(it.id) }

    /** Closes the sessions of [profile] ("Forget profile"). */
    fun closeProfile(profile: String) {
        _all.value.filter { it.profile == profile }.forEach { close(it.id) }
        // The profile is gone: what is remembered of its sessions (names, the assistant's own) goes with it.
        scope.launch(Dispatchers.IO) { env.tabNames.forgetProfile(profile) }
    }

    /** Whether session [id] went because its tunnel was closed: the terminal screen says so when it was the one shown. Main thread. */
    fun closedByTunnel(id: Int): Boolean = id in closedByTunnel

    fun find(id: Int): SshSession? = _all.value.firstOrNull { it.id == id }

    /** No tab of the owner's own to this connection: the assistant's sessions are not tabs, and never the first tab that picks up. */
    private fun hasNoTab(profile: String, device: String, port: Int) =
        _all.value.none { it.openedBy == SessionOpener.OWNER && it.profile == profile && it.device == device && it.port == port }

    /** Names [pickUp] handed out whose tabs do not exist yet. */
    private val claims = PickUpClaims(env.tmuxNames)

    /**
     * [from], the first tab of its connection, found this install's
     * sessions [found] on the device (any thread): it takes the first one
     * that no tab of the app holds, and the rest open as tabs after it
     * ([openPickedUp]). Tabs opened meanwhile have names of their own made
     * before their session exists on the device, so they are among the
     * held ones, never duplicated. Returns the name [from] took, or null:
     * nothing to pick up, a new session as today.
     */
    private fun pickUp(from: SshSession, listing: OwnSessions): String? {
        // Every session of the install is in the listing, the assistant's too: a record is forgotten only when its session is gone; only the owner's become tabs.
        forgetGoneTabNames(from, listing)
        val found = listing.owner
        // The counter moves past EVERY session of the install, the assistant's too: a new tab never takes (`-A`) one of them.
        env.tmuxNames.reserve(namesToReserve(listing))
        val free = claims.claim(found, held = { _all.value.mapNotNull { it.tmux.assigned }.toSet() }, adopt = from.tmux::adopt)
        if (free.isEmpty()) return null
        // The session is the tab's now: the name the owner gave it, if one was remembered.
        env.tabNames.get(SessionKey(from.profile, from.device, from.port, from.user, free.first()))?.let(from::restoreTabName)
        // The remembered names of the others are read here, on the caller's (IO) thread, so that the main thread never loads the file.
        val named = free.drop(1).associateWith { env.tabNames.get(SessionKey(from.profile, from.device, from.port, from.user, it)) }
        scope.launch(Dispatchers.Main) { openPickedUp(from, free.drop(1), named) }
        return free.first()
    }

    /**
     * A listing ([found], this install's sessions for [from]'s user) that succeeded: remembered tab names of that
     * login's sessions it does not have are forgotten, except those of tabs open now (their session may have started
     * after the listing was taken, so they are looked up when the work runs). Off the caller's thread.
     */
    private fun forgetGoneTabNames(from: SshSession, listing: OwnSessions) {
        scope.launch(Dispatchers.IO) {
            val held = _all.value.mapNotNull { it.tmux.assigned }.toSet()
            env.tabNames.afterListing(from.profile, from.device, from.port, from.user, listing.all, keep = held)
            // Marked as the assistant's and without a record: the record comes back.
            env.tabNames.adoptAssistant(from.profile, from.device, from.port, from.user, listing.assistant)
        }
    }

    /** The other picked-up sessions as tabs right after [from], in name order; then "Restored N sessions". Main thread. */
    private fun openPickedUp(from: SshSession, names: List<String>, named: Map<String, TabName?>) {
        try {
            openPickedUpTabs(from, names, named)
        } finally {
            // Tabs now (or not wanted): free for a later pick-up again.
            claims.release(names)
        }
    }

    private fun openPickedUpTabs(from: SshSession, names: List<String>, named: Map<String, TabName?>) {
        // Closed while logging in: the owner did not want them; they stay on the device.
        if (find(from.id) == null) return
        var after = from.id
        for (name in names) {
            val target = SshTarget(from.user, from.device, from.port)
            val number = nextSessionNumber(_all.value.map { SshTarget(it.user, it.device, it.port) to it.number }, target)
            val tab = SshSession(
                nextId++, from.profile, from.hostKeys, from.device, from.port, from.localPort, from.user, from.auth, env, scope, ClientForScreen(), number,
                tmuxName = name, onReattached = ::sayReattached, quietAttach = true,
                tabName = named[name],
            )
            drafts.open(tab.id)
            val anchor = after
            update { list ->
                val at = list.indexOfFirst { it.id == anchor }
                if (at < 0) list + tab else list.subList(0, at + 1) + tab + list.subList(at + 1, list.size)
            }
            after = tab.id
            tab.connect()
        }
        val restored = names.size + 1
        notices.showInApp(Texts.plural(R.plurals.ssh_sessions_restored, restored, restored), NoticeKind.DONE, key = REATTACHED_KEY)
    }

    /**
     * Runs [command] on [from]'s device as its user and returns its
     * output: on an open tab's connection when there is one, else on a
     * short connection of its own with the same login. Blocking: an IO
     * thread. Throws when the device cannot be reached or refuses.
     */
    fun runOnDevice(from: SshSession, command: String): String = runOnDeviceResult(from, command).stdout

    /** [runOnDevice] with the error output and the exit status ([ExecResult]). */
    fun runOnDeviceResult(from: SshSession, command: String): ExecResult {
        liveClient(from.profile, from.device, from.port, from.user)?.let { return execResult(it, command) }
        val connector = SshConnector(from.profile, from.hostKeys, from.device, from.port, from.localPort, from.user, from.auth, env)
        val ssh = connector.newClient()
        connector.connect(ssh)
        try {
            connector.authenticate(ssh)
            return execResult(ssh, command)
        } finally {
            runCatching { ssh.disconnect() }
        }
    }

    /** This install's tmux sessions on [from]'s device for its user, by number; see [runOnDevice]. */
    fun ownTmuxOn(from: SshSession): List<String> {
        val result = runOnDeviceResult(from, TMUX_LIST_COMMAND)
        val found = ownTmuxSessions(result.stdout, env.tmuxNames.install)
        // Only a listing that really answered ([tmuxListing]) forgets remembered names of this login's sessions that are not in it
        // (never those of open tabs); a failed command with empty output says nothing.
        val listing = tmuxListing(result)?.let { ownTmuxListing(it, env.tmuxNames.install) }
        env.tabNames.afterListing(from.profile, from.device, from.port, from.user, listing?.all, keep = _all.value.mapNotNull { it.tmux.assigned }.toSet())
        listing?.let { env.tabNames.adoptAssistant(from.profile, from.device, from.port, from.user, it.assistant) }
        // "Close all" closes every tab the owner sees, an assistant session he opened as a tab too (its tmux session ends with it); the ones he has no tab for stay.
        val shownAssistant = _all.value.filter { it.openedBy == SessionOpener.ASSISTANT && it.shown.value }.mapNotNull { it.tmux.assigned }
        return found + (listing?.assistant.orEmpty().filter { it in shownAssistant })
    }

    /**
     * Ends tmux sessions [names] on [from]'s device for its user; see
     * [runOnDevice]. Throws when it cannot, also when any of them still
     * runs afterwards ([stillRunning]).
     */
    fun endTmuxOn(from: SshSession, names: List<String>) {
        val result = runOnDeviceResult(from, tmuxKillAllAndListCommand(names))
        val left = stillRunning(result.stdout, names)
        // The ones that ended take their remembered names with them, when the listing after the kills really answered
        // ([tmuxListing]): an empty output of a failed command does not show that they ended.
        if (tmuxListing(result) != null) (names - left.toSet()).forEach { env.tabNames.forget(SessionKey(from.profile, from.device, from.port, from.user, it)) }
        if (left.isNotEmpty()) throw IOException(Texts.get(R.string.terminal_close_all_still_running, left.joinToString(", ")))
    }

    /** One notice for any number of tabs back on their tmux sessions at once; none in the background. */
    private fun sayReattached() {
        notices.showInApp(Texts.get(R.string.ssh_session_restored), NoticeKind.DONE, key = REATTACHED_KEY)
    }

    /** Callbacks from one session's emulator, forwarded to the screen when it shows that session. */
    private inner class ClientForScreen : TerminalSessionClient, RemoteClipboardClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            listener?.onScreenChanged(changedSession)
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit

        override fun onSessionFinished(finishedSession: TerminalSession) = Unit

        override fun onRemoteCopy(session: TerminalSession, text: String?) {
            val selected = _all.value.firstOrNull { it.id == clipboardSourceId }?.terminal?.session
            if (selected !== session) return
            onCopyTextToClipboard(session, text)
        }

        /** Explicit selection Copy remains unrestricted; accepted OSC copies use this same writer. */
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            val copied = text.take(MAX_COPY)
            try {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(Texts.get(R.string.terminal), copied))
            } catch (e: RuntimeException) {
                Log.w(TAG, "clipboard not set: ${e.javaClass.simpleName}")
                return
            }
            // One notice, replaced by the next: a host repeating OSC 52 must not queue them up.
            notices.show(Texts.get(R.string.terminal_copied, copyPreview(copied, COPY_PREVIEW)), NoticeKind.DONE, key = COPIED_KEY)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val manager = context.getSystemService(ClipboardManager::class.java)
            val target = _all.value.firstOrNull { it.terminal.session === session } ?: return
            // A picture's content URI is not text to paste: the screen offers to send the picture instead. Decided from the
            // description alone, so the clip's content is not read until the owner confirms.
            if (try { clipKind(manager.primaryClipDescription) } catch (e: RuntimeException) { ClipKind.NONE } == ClipKind.IMAGE) {
                return listener?.onPasteImage(target) ?: Unit
            }
            val clip = manager.primaryClip ?: return
            val text = (0 until clip.itemCount).joinToString("") { clip.getItemAt(it).coerceToText(context) }
            when (val plan = planPaste(text, pasteBracketed(target.terminal.bracketedPaste, target.tmux.inUse))) {
                // Nothing but spaces and line breaks: said, as the key's tap needs an answer.
                null -> notices.show(Texts.get(R.string.terminal_clipboard_empty_notice), NoticeKind.INFO, key = PASTE_EMPTY_KEY)
                is PastePlan.Paste -> target.paste(plan.text)
                // No screen to ask on: nothing goes rather than lines that would run.
                is PastePlan.Ask -> listener?.onPasteAsk(target, plan)
            }
        }

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            listener?.onScreenChanged(session)
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            listener?.onCursorStateChanged(state)
        }

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) {
            Log.e(tag ?: TAG, message.orEmpty())
        }

        override fun logWarn(tag: String?, message: String?) {
            Log.w(tag ?: TAG, message.orEmpty())
        }

        override fun logInfo(tag: String?, message: String?) = Unit

        override fun logDebug(tag: String?, message: String?) = Unit

        override fun logVerbose(tag: String?, message: String?) = Unit

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.w(tag ?: TAG, message.orEmpty(), e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.w(tag ?: TAG, e)
        }
    }

    companion object {
        private const val TAG = "SshSessions"

        /** Characters of copied text the notice shows. */
        private const val COPY_PREVIEW = 40

        /** A newer "copied" takes the place of the one shown. */
        private const val COPIED_KEY = "copied"
        private const val PASTE_EMPTY_KEY = "paste-empty"

        /** A newer "session restored" takes the place of the one shown. */
        private const val REATTACHED_KEY = "ssh-reattached"

        /** The most a terminal puts on the clipboard: an OSC 52 of megabytes would not fit through Binder. */
        private const val MAX_COPY = 64 * 1024
        const val DEFAULT_FONT_SP = 14
    }
}

/**
 * Whether the terminal says "The tunnel was closed." when the session it
 * showed ([before]) is no longer the one shown: only when that session is
 * gone ([exists] false) and it went with its tunnel ([closedByTunnel]);
 * not when the owner closed it, reconnected it or picked another tab.
 */
fun saysTunnelClosed(before: Int?, now: Int?, exists: (Int) -> Boolean, closedByTunnel: (Int) -> Boolean): Boolean =
    before != null && before != now && !exists(before) && closedByTunnel(before)

/** Who a session logs in as and where. */
data class SshTarget(val user: String, val device: String, val port: Int)

/**
 * A new session's number among the [open] ones (target, number) to the
 * same [target]: one past the highest, so closing one renumbers nothing;
 * 1 when none is open.
 */
fun nextSessionNumber(open: List<Pair<SshTarget, Int>>, target: SshTarget): Int =
    (open.filter { it.first == target }.maxOfOrNull { it.second } ?: 0) + 1

/**
 * The number of a session that replaces one numbered [oldNumber] to [old]
 * in its tab, now to [now], among the [others] open: the same number for
 * the same target, else the next free one for the new target.
 */
fun replacementNumber(others: List<Pair<SshTarget, Int>>, old: SshTarget, oldNumber: Int, now: SshTarget): Int =
    if (now == old) oldNumber else nextSessionNumber(others, now)

/**
 * What a notice shows of [text] put on the clipboard: its first non-blank
 * line without control or bidi-override characters (a remote host chooses
 * the text), cut to [max] code points, "…" when anything was left out.
 */
fun copyPreview(text: String, max: Int): String {
    val clean = text.filterNot { it.isISOControl() && it != '\n' && it != '\r' || it in BIDI_CONTROLS }
    val lines = clean.lines().map { it.trim() }
    val first = lines.firstOrNull { it.isNotEmpty() } ?: return "…"
    val cut = if (first.codePointCount(0, first.length) > max) first.substring(0, first.offsetByCodePoints(0, max)) else first
    return if (cut.length < first.length || lines.count { it.isNotEmpty() } > 1) "$cut…" else cut
}

private val BIDI_CONTROLS = ('\u202A'..'\u202E').toSet() + ('\u2066'..'\u2069').toSet() + setOf('\u200E', '\u200F', '\u061C')
