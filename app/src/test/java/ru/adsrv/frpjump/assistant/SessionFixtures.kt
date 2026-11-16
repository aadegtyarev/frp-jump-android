package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.adsrv.frpjump.ssh.MemoryTextStorage
import ru.adsrv.frpjump.ssh.SessionKey
import ru.adsrv.frpjump.ssh.SessionOpener
import ru.adsrv.frpjump.ssh.NameSetter
import ru.adsrv.frpjump.ssh.SessionRecordStore
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ssh.TabName

/** A session in memory: what the assistant's sessions need of one, and how it comes up is the test's to say. */
internal class FakeHosted(
    override val id: Int, override val openedBy: SessionOpener, override val profile: String = "p1", override val user: String = "root",
    override val device: String = "pi", override val port: Int = 22, override val number: Int = 1, initial: SshState = SshState.Open,
    override val tmuxInUse: Boolean = true, override val key: SessionKey? = null, override var name: TabName? = null,
) : HostedSession {
    override var sessionGone = false
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<SshState> = flow
    override var shown = openedBy == SessionOpener.OWNER
    override fun restoreName(name: TabName) { if (this.name == null) this.name = name }
}

/** The app's sessions in memory. [comes] decides what a session opened now turns into; [log] records what was done. */
internal class FakeHost : SessionHost {
    val sessions = ArrayList<FakeHosted>()
    val log = ArrayList<String>()
    var comes: SshState = SshState.Open
    var tmux = true
    /** The tmux sessions that still run on the device: an attach-only session to another one finds it gone. */
    var running: Set<String>? = null
    private var next = 100
    override fun all(): List<HostedSession> = sessions.toList()
    override fun reserveId() = next++
    override fun open(spec: OpenSpec, id: Int?, tmuxName: String?, attachOnly: Boolean): HostedSession {
        val number = sessions.size + 1
        val gone = attachOnly && tmuxName != null && running?.let { tmuxName !in it } == true
        val s = FakeHosted(id ?: next++, SessionOpener.ASSISTANT, spec.profile, spec.user, spec.device, spec.port, number, if (gone) SshState.Ended("gone") else comes, tmux,
            SessionKey(spec.profile, spec.device, spec.port, spec.user, tmuxName ?: "frpjump-0a1b2c-$number"))
        s.sessionGone = gone
        sessions += s
        log += "open ${s.id}${if (tmuxName != null) " attach $tmuxName" else ""}${if (attachOnly) " only" else ""}"
        return s
    }
    override fun reconnect(id: Int): HostedSession? {
        val old = sessions.firstOrNull { it.id == id } ?: return null
        sessions.remove(old)
        val s = FakeHosted(next++, old.openedBy, old.profile, old.user, old.device, old.port, old.number, comes, tmux, old.key, old.name).also { it.shown = old.shown }
        sessions += s
        log += "reconnect $id -> ${s.id}"
        return s
    }
    override fun close(id: Int, endTmux: Boolean) { sessions.removeAll { it.id == id }; log += "close $id tmux=$endTmux" }
    override fun show(id: Int): HostedSession? = sessions.firstOrNull { it.id == id }?.also { it.shown = true; log += "show $id" }
}

/** Saved connections in memory: the tunnel of each [ready] (profile, connection) is on; [needsSetup] ones log in only with the dialog. */
internal class FakeTargets : SessionTargets {
    val ready = mutableSetOf("p1" to "c1")
    val needsSetup = mutableSetOf<Pair<String, String>>()
    override fun spec(profile: String, connection: String): SpecResult = when {
        profile to connection in needsSetup -> SpecResult.NeedsSetup
        profile to connection !in ready -> SpecResult.Unavailable
        else -> SpecResult.Ready(OpenSpec(profile, null, "pi", 22, 40001, user, null))
    }
    /** Saved connections that were removed: the tunnel is not merely off, the connection is gone. */
    val removed = mutableSetOf<String>()
    var user = "root"
    override fun connectionAt(profile: String, device: String, port: Int) = if (profile !in removed && profile to "c1" in ready && device == "pi") "c1" else null
    override fun connectionGone(profile: String, device: String, port: Int) = profile in removed
}

/** The assistant's sessions over fakes, with its records in memory. */
internal class SessionRig(
    val host: FakeHost = FakeHost(), val targets: FakeTargets = FakeTargets(), val records: SessionRecordStore = SessionRecordStore(MemoryTextStorage()),
    val asked: MutableList<OwnerAsk> = ArrayList(), val cancelled: MutableList<Int> = ArrayList(), val notices: MutableList<SessionNotice> = ArrayList(),
    var switchOn: Boolean = true,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val sessions = AssistantSessions(host, targets, records, scope, notify = { asked += it }, cancelNotify = { cancelled += it }, notice = { notices += it }, enabled = { switchOn }, main = Dispatchers.Unconfined, io = Dispatchers.Unconfined,
        connectWaitMs = 500, profileName = { if (it == "p1") "Home" else null })
    /** An assistant session already open, as if `terminal_open` had made it. */
    fun existing(id: Int, number: Int = 1, name: TabName? = null) = FakeHosted(id, SessionOpener.ASSISTANT, number = number, name = name,
        key = SessionKey("p1", "pi", 22, "root", "frpjump-0a1b2c-$id")).also {
        host.sessions += it
        // Its record: the records are what says that it exists.
        records.put(it.key!!, name?.text, NameSetter.ASSISTANT, SessionOpener.ASSISTANT)
    }
    /** An owner's tab. */
    fun tab(id: Int) = FakeHosted(id, SessionOpener.OWNER, number = id, key = SessionKey("p1", "pi", 22, "root", "frpjump-0a1b2c-$id")).also { host.sessions += it }
}
