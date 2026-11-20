package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.agent.AgentTool
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.assistant.chat.AssistantToolFactory
import ru.adsrv.frpjump.assistant.chat.ConfirmationDecision
import ru.adsrv.frpjump.assistant.chat.ConfirmationSupport
import ru.adsrv.frpjump.assistant.chat.NOT_AUTHORIZED
import ru.adsrv.frpjump.assistant.chat.authorizeCall
import ru.adsrv.frpjump.assistant.tools.ChangeCheck
import ru.adsrv.frpjump.assistant.tools.ChangeRequest
import ru.adsrv.frpjump.settings.ConfirmationMode
import ru.adsrv.frpjump.settings.ConfirmationPolicy
import ru.adsrv.frpjump.ssh.ExecLease
import ru.adsrv.frpjump.ssh.AttachVerdict
import ru.adsrv.frpjump.ssh.TmuxProbe
import ru.adsrv.frpjump.ssh.attachOnlyVerdict
import ru.adsrv.frpjump.ssh.SessionKey
import ru.adsrv.frpjump.ssh.SessionOpener
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ssh.TabName
import ru.adsrv.frpjump.ssh.NameSetter

/** The assistant's own sessions and their three tools over fakes: no device, no connection, no emulator. */
class AssistantSessionsTest {
    private fun ids(profile: String = "p1", connection: String = "c1") = buildJsonObject { put("profile_id", profile); put("connection_id", connection) }
    private fun tab(id: Int) = buildJsonObject { put("tab_id", id) }
    private fun run(tool: AgentTool, args: JsonObject) = runBlocking { tool.execute(args) }
    private fun open(rig: SessionRig, limit: Int = 3, args: JsonObject = ids()) = run(TerminalOpenTool(rig.sessions, limit), args)

    // ---- terminal_open ----

    @Test fun `terminal_open opens a session of the assistant that is not a tab and returns its tab_id`() {
        val rig = SessionRig()
        val out = open(rig)
        assertEquals(ToolOutcome.SUCCESS, out.outcome)
        assertEquals("Session opened: tab_id 100 (root@pi). Type with terminal_input and read with terminal_read.", out.text)
        val s = rig.host.sessions.single()
        assertEquals(SessionOpener.ASSISTANT, s.openedBy); assertFalse("no tab for the owner", s.shown)
        assertEquals(listOf("open 100"), rig.host.log)
        assertEquals(1, rig.sessions.count()); assertTrue(rig.sessions.isAssistant(100))
        // It needs no question: the catalog says so.
        assertFalse(AssistantToolId.TERMINAL_OPEN.confirms)
        assertSame(ChangeCheck.ReadOnly, TerminalOpenTool(rig.sessions, 3).changeCheck(ids()))
    }

    @Test fun `terminal_open refuses past the owner's limit and opens nothing`() {
        val rig = SessionRig(); rig.existing(1); rig.existing(2)
        val out = open(rig, limit = 2)
        assertEquals(ToolOutput("Already 2 sessions of yours, the most at once. Close one with terminal_close first.", ToolOutcome.FAILED), out)
        assertTrue(rig.host.log.isEmpty())
        // The limit is the owner's number: one more is fine when it is raised, and sessions remembered from before a restart count too.
        assertEquals(ToolOutcome.SUCCESS, open(rig, limit = 3).outcome)
        val after = SessionRig(); after.records.put(SessionKey("p1", "pi", 22, "root", "frpjump-0a1b2c-1"), null, openedBy = SessionOpener.ASSISTANT)
        after.sessions.refresh()
        assertEquals(1, after.sessions.count())
        assertEquals("Already 1 sessions of yours, the most at once. Close one with terminal_close first.", open(after, limit = 1).text)
    }

    @Test fun `terminal_open reuses the refusals of ssh_command and starts nothing`() {
        val gone = SessionRig(); gone.targets.ready.clear()
        assertEquals(ToolOutput("SSH target unavailable. Use a saved SSH tunnel that is on and ready; no tunnel was started.", ToolOutcome.FAILED), open(gone))
        val setup = SessionRig(); setup.targets.needsSetup += "p1" to "c1"
        assertEquals(ToolOutput("SSH login or host trust needs setup. Open this tunnel in the ordinary SSH UI first. No command was executed.", ToolOutcome.FAILED), open(setup))
        // A host key that needs the owner's decision is the same: the session is closed, not left waiting.
        val key = ru.adsrv.frpjump.ssh.PinnedHostKey("ssh-ed25519", "AAAA")
        val unknown = SessionRig(); unknown.host.comes = SshState.HostKeyUnknown(key)
        assertEquals(setup.let { open(setup).text }, open(unknown).text)
        assertTrue(unknown.host.sessions.isEmpty())
        val failed = SessionRig(); failed.host.comes = SshState.Ended("refused")
        assertEquals(ToolOutput("SSH unavailable. Check the tunnel in the ordinary SSH UI. No command was executed.", ToolOutcome.FAILED), open(failed))
        assertTrue(failed.host.sessions.isEmpty())
        // No tmux on the device: a session could not be kept, so none is.
        val plain = SessionRig(); plain.host.tmux = false
        assertEquals(ToolOutput("tmux is not available on the device, so a session cannot be kept. Nothing was opened.", ToolOutcome.FAILED), open(plain))
        assertTrue(plain.host.sessions.isEmpty())
        assertEquals(ToolOutcome.FAILED, open(SessionRig(), args = buildJsonObject { put("profile_id", "p1") }).outcome)
    }

    @Test fun `the three tools are offered only while their switch, the tabs and typing are all on, and the tab tools never lack`() {
        val rig = SessionRig()
        fun names(t: ru.adsrv.frpjump.settings.AssistantTools, sessions: AssistantSessions? = rig.sessions) = AssistantToolFactory.create(t, AssistantChatRuntime(),
            ru.adsrv.frpjump.agent.AgentProvider { ru.adsrv.frpjump.agent.AgentReply("x") }, null, null, { }, terminalTabs = AssistantTerminalTabs({ emptyList() }, { emptySet() }, { null }, { _, _, _ -> null }),
            sessions = sessions).map { it.spec.name }
        val session = listOf("terminal_open", "terminal_show", "terminal_close")
        fun tools(tabs: Boolean, input: Boolean, sessions: Boolean) = ru.adsrv.frpjump.settings.AssistantTools(terminalTabs = tabs, terminalInput = input, sessions = sessions)
        assertEquals(session, names(tools(true, true, true)).filter { it in session })
        for ((tabs, input, on) in listOf(Triple(true, true, false), Triple(true, false, true), Triple(false, true, true), Triple(false, false, true)))
            assertTrue("$tabs $input $on", names(tools(tabs, input, on)).none { it in session })
        // Without the owner of the sessions (a test, an old build) there are none either; in catalog order: after typing.
        assertTrue(names(tools(true, true, true), sessions = null).none { it in session })
        assertEquals(listOf("terminal_tabs", "terminal_read", "terminal_rename", "terminal_input", "terminal_open", "terminal_show", "terminal_close"), names(tools(true, true, true)).filter { it.startsWith("terminal_") })
    }

    // ---- listing after a restart, and attaching on first use ----

    private class HostedTab(private val s: FakeHosted, private val term: FakeTerminal) : TabHandle {
        override val id get() = s.id
        override val profileId get() = s.profile
        override val device get() = s.device
        override val port get() = s.port
        override val user get() = s.user
        override val number get() = s.number
        override val state get() = s.flow.value
        override val tmuxName get() = s.key?.session
        override val openedBy get() = s.openedBy
        override val shown get() = s.shown
        override fun exec(command: String, maxBytes: Int, lease: ExecLease): String? = term.exec(command)
        override fun bufferSource(mode: ReadMode) = term.source(mode)
    }

    private fun tabs(rig: SessionRig, term: FakeTerminal) = AssistantTerminalTabs(
        tabs = { rig.host.sessions.filter { it.openedBy == SessionOpener.OWNER }.map { HostedTab(it, term) } +
            rig.sessions.handles { id -> rig.host.sessions.firstOrNull { it.id == id }?.let { HostedTab(it, term) } } },
        hidden = { emptySet() }, profileName = { "Home" }, connectionName = { _, _, _ -> null }, io = Dispatchers.IO, main = Dispatchers.Unconfined, attach = { rig.sessions.attach(it) })

    private fun remembered(vararg numbers: Int): SessionRig {
        val rig = SessionRig()
        for (n in numbers) rig.records.put(SessionKey("p1", "pi", 22, "root", "frpjump-0a1b2c-$n"), if (n == 1) "deploy" else null, NameSetter.ASSISTANT, SessionOpener.ASSISTANT)
        rig.sessions.refresh()
        return rig
    }

    @Test fun `after a restart the remembered sessions are listed as detached with a stable id, and nothing is attached`() {
        val rig = remembered(1, 2)
        val list = tabs(rig, FakeTerminal()).list()
        assertEquals(listOf(TabState.DETACHED, TabState.DETACHED), list.map { it.state })
        assertEquals(listOf("deploy", null), list.map { it.name })
        assertTrue(list.all { it.openedBy == SessionOpener.ASSISTANT && !it.shown })
        val line = tabsText(list, emptyMap()).lines().first()
        assertTrue(line, line.contains("| state: detached | tmux: yes | opened by: you | shown: no"))
        assertEquals("the ids stay the same", list.map { it.id }, tabs(rig, FakeTerminal()).list().map { t -> t.id })
        assertTrue("no connection was made", rig.host.log.isEmpty())
        // An owner's own session without a name is no record, so nothing of the owner's is listed this way.
        val owner = SessionRig(); owner.records.put(SessionKey("p1", "pi", 22, "root", "frpjump-0a1b2c-3"), "mine"); owner.sessions.refresh()
        assertTrue(tabs(owner, FakeTerminal()).list().isEmpty())
    }

    @Test fun `the first read attaches a detached session over its tunnel, under the same id and tmux session, and the owner has no tab for it`() {
        val rig = remembered(1)
        val term = FakeTerminal().set("\$ make\nbuilding")
        val owner = tabs(rig, term)
        val id = owner.list().single().id
        val read = runBlocking { owner.read(id, 10) }
        assertTrue(read.text, read.text.contains("building"))
        assertEquals(listOf("open $id attach frpjump-0a1b2c-1 only"), rig.host.log)
        val live = rig.host.sessions.single()
        assertEquals(id, live.id); assertEquals(SessionOpener.ASSISTANT, live.openedBy); assertFalse(live.shown)
        assertEquals("deploy", live.name?.text)
        assertEquals(TabState.CONNECTED, owner.list().single().state)
        // Read again: nothing more is opened.
        runBlocking { owner.read(id, 10) }
        assertEquals(1, rig.host.log.size)
    }

    @Test fun `a detached session is not attached while its tunnel is off, and says so`() {
        val rig = remembered(1); rig.targets.ready.clear()
        val owner = tabs(rig, FakeTerminal().set("x"))
        val id = owner.list().single().id
        val out = runBlocking { AssistantTerminalReadTool(owner).execute(tab(id)) }
        assertEquals("Tab $id is not connected now (detached). Nothing was read. Call terminal_tabs again later.", out.text)
        assertTrue(rig.host.log.isEmpty())
    }

    // ---- the records are the one list ----

    @Test fun `the limit and the listing follow the records, so a tunnel that went down and up does not free the slots`() {
        val rig = SessionRig()
        repeat(3) { assertEquals(ToolOutcome.SUCCESS, open(rig, limit = 3).outcome) }
        assertEquals(3, rig.sessions.count())
        // The tunnel goes down: the app closes every live session of it. The sessions still run on the device and their records stay.
        rig.host.sessions.clear()
        assertEquals(3, rig.sessions.count())
        val owner = tabs(rig, FakeTerminal())
        assertEquals(List(3) { TabState.DETACHED }, owner.list().map { it.state })
        // The tunnel is back: three more cannot be opened.
        assertEquals(ToolOutput("Already 3 sessions of yours, the most at once. Close one with terminal_close first.", ToolOutcome.FAILED), open(rig, limit = 3))
        assertEquals(3, rig.sessions.count())
    }

    @Test fun `a session that is attaching while the list is built is listed once, with one id`() {
        val rig = remembered(1, 2)
        val owner = tabs(rig, FakeTerminal())
        val before = owner.list().map { it.id }
        // Attached: the live session and its record are ONE entry, under the same id; it is never in two lists.
        runBlocking { assertEquals(AttachResult.ATTACHED, rig.sessions.attach(before.first())) }
        val after = owner.list()
        assertEquals(before, after.map { it.id })
        assertEquals(before.distinct(), after.map { it.id })
        assertEquals(listOf(TabState.CONNECTED, TabState.DETACHED), after.map { it.state })
        assertEquals(2, rig.sessions.entries.value.map { it.id }.toSet().size)
        // An owner's session shown as a tab is not an assistant session at all.
        rig.tab(9)
        assertEquals(3, owner.list().size); assertEquals(1, owner.list().count { it.openedBy == SessionOpener.OWNER })
    }

    @Test fun `the owner's Close of a session that cannot be reached keeps it and says so`() {
        val rig = remembered(1); rig.targets.ready.clear()
        runBlocking { rig.sessions.closeByOwner(rig.sessions.entries.value.single().id) }
        assertEquals(OwnerSessionProblem(OwnerSessionAction.CLOSE, OwnerSessionFailure.UNREACHABLE), rig.sessions.ownerProblems.value.values.single())
        assertEquals(1, rig.sessions.count()); assertTrue(rig.host.log.isEmpty())
        // Reachable: attached, reconnected when it ended, and killed.
        val ok = remembered(1)
        runBlocking { ok.sessions.closeByOwner(ok.sessions.entries.value.single().id) }
        assertEquals(listOf("open ${100} attach frpjump-0a1b2c-1 only", "confirm 100", "close 100 tmux=false"), ok.host.log.map { it.replace(Regex("open \\d+"), "open 100").replace(Regex("close \\d+"), "close 100") })
        assertTrue(ok.notices.isEmpty()); assertEquals(0, ok.sessions.count())
        // A live session that ended is reconnected first, then ended.
        val ended = SessionRig(); ended.existing(4).flow.value = SshState.Ended("drop")
        runBlocking { ended.sessions.closeByOwner(4) }
        assertTrue(ended.host.log.toString(), ended.host.log.first().startsWith("reconnect 4") && ended.host.log.last().endsWith("tmux=false"))
    }

    @Test fun `attaching only attaches, a session that is gone is forgotten, never created again`() {
        val rig = remembered(1); rig.host.running = emptySet()
        val owner = tabs(rig, FakeTerminal())
        val id = owner.list().single().id
        val out = runBlocking { AssistantTerminalReadTool(owner).execute(tab(id)) }
        assertEquals(ToolOutput("Session $id has ended.", ToolOutcome.FAILED), out)
        assertEquals(listOf("open $id attach frpjump-0a1b2c-1 only", "close $id tmux=false"), rig.host.log)
        assertEquals("its record is forgotten", 0, rig.sessions.count()); assertNull(rig.records.record(SessionKey("p1", "pi", 22, "root", "frpjump-0a1b2c-1")))
        assertEquals(ToolOutput("Tab $id is not open. Call terminal_tabs again.", ToolOutcome.FAILED), runBlocking { AssistantTerminalReadTool(owner).execute(tab(id)) })
    }

    @Test fun `the saved login of another user is never offered for a remembered session, and the switch off attaches nothing`() {
        val rig = remembered(1); rig.targets.user = "admin"
        val owner = tabs(rig, FakeTerminal().set("x"))
        val id = owner.list().single().id
        assertEquals(ToolOutput(SshRefusalTexts.NEEDS_SETUP, ToolOutcome.FAILED), runBlocking { AssistantTerminalReadTool(owner).execute(tab(id)) })
        assertTrue(rig.host.log.isEmpty())
        val off = remembered(1); off.switchOn = false
        val offTabs = tabs(off, FakeTerminal().set("x"))
        assertTrue(runBlocking { AssistantTerminalReadTool(offTabs).execute(tab(offTabs.list().single().id)) }.text.contains("not connected now (detached)"))
        assertTrue(off.host.log.isEmpty())
        // The owner's own Open still attaches (he asked): the switch is the assistant's.
        assertNotNull(runBlocking { off.sessions.show(off.sessions.entries.value.single().id) })
    }

    @Test fun `a removed saved connection forgets the record, tells the owner, and leaves the session on the device`() {
        val rig = remembered(1); rig.targets.removed += "p1"
        runBlocking { assertEquals(AttachResult.GONE, rig.sessions.attach(rig.sessions.entries.value.single().id)) }
        assertEquals(listOf(SessionNotice.TUNNEL_GONE), rig.notices)
        assertEquals(0, rig.sessions.count()); assertTrue("nothing was killed", rig.host.log.isEmpty())
    }

    @Test fun `a Stop while terminal_open waits closes the half-open session`() {
        val rig = SessionRig(); rig.host.comes = SshState.Connecting
        val job = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) { rig.sessions.open(ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds("p1", "c1"), 3) }
        runBlocking { kotlinx.coroutines.withTimeout(5000) { rig.host.opened.await() }; job.cancelAndJoin() }
        assertEquals(1, rig.host.log.count { it.startsWith("open") })
        assertTrue(rig.host.log.toString(), rig.host.log.last().endsWith("tmux=true")); assertTrue(rig.host.sessions.isEmpty()); assertEquals(0, rig.sessions.count())
    }

    @Test fun `an ask goes, with its notification, when its session is closed or the owner opens it`() {
        val rig = SessionRig(); rig.existing(7, number = 2)
        run(TerminalShowTool(rig.sessions), buildJsonObject { put("tab_id", 7); put("reason", "x") })
        runBlocking { rig.sessions.show(7) }
        assertTrue(rig.sessions.asks.value.isEmpty()); assertEquals(listOf(7), rig.cancelled)
        run(TerminalShowTool(rig.sessions), buildJsonObject { put("tab_id", 7); put("reason", "y") })
        // The record goes (a listing without it): the ask and its notification go with it.
        rig.records.forget(SessionKey("p1", "pi", 22, "root", "frpjump-0a1b2c-7"))
        assertTrue(rig.sessions.asks.value.isEmpty()); assertEquals(listOf(7, 7), rig.cancelled)
    }

    @Test fun `the cap never evicts an assistant record, and a lost record comes back from the device's marker`() {
        val store = ru.adsrv.frpjump.ssh.SessionRecordStore(ru.adsrv.frpjump.ssh.MemoryTextStorage(), max = 2)
        val a = SessionKey("p", "d", 22, "u", "frpjump-0a1b2c-1")
        store.put(a, null, openedBy = SessionOpener.ASSISTANT)
        for (n in 2..6) store.put(SessionKey("p", "d", 22, "u", "frpjump-0a1b2c-$n"), "n$n")
        assertTrue(store.record(a) != null)
        assertEquals(1, store.assistantSessions().size)
        // The record is lost (Forget profile, a file that did not save); the device still says the session is the assistant's: it is adopted again.
        store.forgetProfile("p"); assertNull(store.record(a))
        store.adoptAssistant("p", "d", 22, "u", listOf(a.session))
        assertEquals(SessionOpener.ASSISTANT, store.record(a)?.openedBy)
        store.adoptAssistant("p", "d", 22, "u", listOf(a.session)); assertEquals(1, store.assistantSessions().size)
    }

    @Test fun `a probe that gets no answer is unreachable and never gone, the record stays`() {
        val rig = remembered(1); rig.host.probeTimesOut = true
        val owner = tabs(rig, FakeTerminal().set("x"))
        val id = owner.list().single().id
        val out = runBlocking { AssistantTerminalReadTool(owner).execute(tab(id)) }
        assertTrue(out.text, out.text.contains("not connected now (detached)"))
        assertEquals(1, rig.sessions.count())
        assertNotNull(rig.records.record(SessionKey("p1", "pi", 22, "root", "frpjump-0a1b2c-1")))
        runBlocking { rig.sessions.closeByOwner(id) }
        assertEquals(OwnerSessionProblem(OwnerSessionAction.CLOSE, OwnerSessionFailure.UNREACHABLE), rig.sessions.ownerProblems.value.values.single()); assertEquals(1, rig.sessions.count())
    }

    @Test fun `owner open and close failures retain exact target and distinguish setup then recover`() {
        val rig = remembered(1, 2)
        val first = rig.sessions.entries.value.first()
        val second = rig.sessions.entries.value.last()
        rig.targets.needsSetup += "p1" to "c1"
        assertNull(runBlocking { rig.sessions.show(first.id) })
        runBlocking { rig.sessions.closeByOwner(second.id) }
        assertEquals(OwnerSessionProblem(OwnerSessionAction.OPEN, OwnerSessionFailure.NEEDS_SETUP), rig.sessions.ownerProblems.value[first.key])
        assertEquals(OwnerSessionProblem(OwnerSessionAction.CLOSE, OwnerSessionFailure.NEEDS_SETUP), rig.sessions.ownerProblems.value[second.key])
        assertEquals(2, rig.sessions.count())
        rig.targets.needsSetup.clear()
        assertNotNull(runBlocking { rig.sessions.show(first.id) })
        assertFalse(rig.sessions.ownerProblems.value.containsKey(first.key))
        runBlocking { rig.sessions.closeByOwner(second.id) }
        assertFalse(rig.sessions.ownerProblems.value.containsKey(second.key))
        assertEquals(1, rig.sessions.count())
    }

    @Test fun `unconfirmed remote close retains record and retry removes it only after confirmation`() {
        val rig = remembered(1)
        val entry = rig.sessions.entries.value.single()
        rig.host.closeConfirmed = false
        runBlocking { rig.sessions.closeByOwner(entry.id) }
        assertEquals(OwnerSessionProblem(OwnerSessionAction.CLOSE, OwnerSessionFailure.CLOSE_UNCONFIRMED), rig.sessions.ownerProblems.value[entry.key])
        assertNotNull(rig.records.record(entry.key))
        assertEquals(entry.key, rig.host.confirmedTarget?.first)
        assertEquals(entry.key.profile, rig.host.confirmedTarget?.second?.profile)
        assertEquals(entry.key.device, rig.host.confirmedTarget?.second?.device)
        assertEquals(entry.key.port, rig.host.confirmedTarget?.second?.port)
        assertEquals(entry.key.user, rig.host.confirmedTarget?.second?.user)
        assertEquals(1, rig.sessions.count())
        assertTrue(rig.host.sessions.any { it.id == entry.id })
        rig.host.closeConfirmed = true
        runBlocking { rig.sessions.closeByOwner(entry.id) }
        assertNull(rig.records.record(entry.key))
        assertTrue(rig.sessions.ownerProblems.value.isEmpty())
        assertEquals(0, rig.sessions.count())
    }

    @Test fun `removed record is not republished by a late failed owner attach`() = runBlocking {
        val rig = remembered(1)
        val entry = rig.sessions.entries.value.single()
        rig.host.comes = SshState.Connecting
        val work = launch { rig.sessions.closeByOwner(entry.id) }
        kotlinx.coroutines.yield()
        assertTrue(rig.host.sessions.isNotEmpty())
        rig.records.forget(entry.key)
        rig.sessions.refresh()
        rig.host.sessions.single().flow.value = SshState.Ended("unavailable")
        work.join()
        assertTrue(rig.sessions.ownerProblems.value.isEmpty())
        assertEquals(0, rig.sessions.count())
    }

    @Test fun `a changed target after authentication prevents remote close`() {
        val rig = remembered(1)
        val entry = rig.sessions.entries.value.single()
        rig.host.beforeConfirm = { rig.targets.stillValid = false }
        runBlocking { rig.sessions.closeByOwner(entry.id) }
        assertFalse(rig.host.log.any { it.startsWith("confirm") })
        assertNotNull(rig.records.record(entry.key))
        assertEquals(OwnerSessionFailure.CLOSE_UNCONFIRMED, rig.sessions.ownerProblems.value[entry.key]?.failure)
    }

    @Test fun `a record removed before dispatch cannot be killed or republished`() {
        val rig = remembered(1)
        val entry = rig.sessions.entries.value.single()
        rig.host.beforeConfirm = { rig.records.forget(entry.key); rig.sessions.refresh() }
        runBlocking { rig.sessions.closeByOwner(entry.id) }
        assertFalse(rig.host.log.any { it.startsWith("confirm") })
        assertNull(rig.records.record(entry.key))
        assertTrue(rig.sessions.ownerProblems.value.isEmpty())
    }

    @Test fun `cancellation after authentication prevents owner close dispatch`() = runBlocking {
        val rig = remembered(1)
        val entry = rig.sessions.entries.value.single()
        val work = launch { rig.sessions.closeByOwner(entry.id) }
        rig.host.beforeConfirm = { work.cancel() }
        work.join()
        assertFalse(rig.host.log.any { it.startsWith("confirm") })
        assertNotNull(rig.records.record(entry.key))
        assertTrue(rig.sessions.ownerProblems.value.isEmpty())
    }

    @Test fun `owner receives exact target when saved tunnel or remote session is gone`() {
        val missingTunnel = remembered(1)
        val tunnelEntry = missingTunnel.sessions.entries.value.single()
        missingTunnel.targets.removed += "p1"
        assertNull(runBlocking { missingTunnel.sessions.show(tunnelEntry.id) })
        assertEquals(listOf(tunnelEntry.key to true), missingTunnel.gone)
        assertEquals(0, missingTunnel.sessions.count())
        val missingSession = remembered(1)
        val sessionEntry = missingSession.sessions.entries.value.single()
        missingSession.host.running = emptySet()
        assertNull(runBlocking { missingSession.sessions.show(sessionEntry.id) })
        assertEquals(listOf(sessionEntry.key to false), missingSession.gone)
        assertEquals(0, missingSession.sessions.count())
    }

    @Test fun `owner replacement before dispatch cannot be killed`() {
        val rig = remembered(1)
        val entry = rig.sessions.entries.value.single()
        rig.host.beforeConfirm = {
            rig.host.sessions.clear()
            rig.host.sessions += FakeHosted(entry.id, SessionOpener.OWNER, key = entry.key)
        }
        runBlocking { rig.sessions.closeByOwner(entry.id) }
        assertFalse(rig.host.log.any { it.startsWith("confirm") })
        assertNotNull(rig.records.record(entry.key))
    }

    @Test fun `confirmed close and already-gone retry preserve the record on storage failure`() {
        val file = object : ru.adsrv.frpjump.store.TextStorage {
            var text: String? = null
            var fail = false
            override fun read(): String? = text
            override fun write(text: String) { if (fail) throw java.io.IOException("full"); this.text = text }
            override fun delete() { text = null }
        }
        val records = ru.adsrv.frpjump.ssh.SessionRecordStore(file)
        val rig = SessionRig(records = records)
        val key = SessionKey("p1", "pi", 22, "root", "frpjump-0a1b2c-1")
        records.put(key, null, openedBy = SessionOpener.ASSISTANT)
        rig.sessions.refresh()
        val id = rig.sessions.entries.value.single().id
        file.fail = true
        runBlocking { rig.sessions.closeByOwner(id) }
        assertEquals(OwnerSessionFailure.RECORD_SAVE_FAILED, rig.sessions.ownerProblems.value[key]?.failure)
        assertNotNull(records.record(key))
        assertNotNull(ru.adsrv.frpjump.ssh.SessionRecordStore(file).record(key))
        // The successful remote kill may already have ended the tab. Retry sees the session gone.
        rig.host.sessions.clear()
        rig.host.running = emptySet()
        runBlocking { rig.sessions.closeByOwner(id) }
        assertEquals(OwnerSessionFailure.RECORD_SAVE_FAILED, rig.sessions.ownerProblems.value[key]?.failure)
        assertNotNull(records.record(key))
        rig.host.sessions.clear()
        file.fail = false
        runBlocking { rig.sessions.closeByOwner(id) }
        assertNull(records.record(key))
        assertNull(ru.adsrv.frpjump.ssh.SessionRecordStore(file).record(key))
        assertEquals(0, rig.sessions.count())
    }

    @Test fun `owner retry reconnects retained ended tab through current endpoint`() {
        val rig = SessionRig()
        val old = rig.existing(4)
        old.flow.value = SshState.Ended("drop")
        old.shown = true
        rig.targets.localPort = 49999
        rig.host.closeConfirmed = false
        runBlocking { rig.sessions.closeByOwner(4) }
        assertEquals(49999, rig.host.reconnectedTarget?.localPort)
        assertEquals(49999, rig.host.confirmedTarget?.second?.localPort)
        assertEquals(4, rig.host.sessions.single().id)
        assertTrue(rig.host.sessions.single().shown)
        assertNotNull(rig.records.record(old.key!!))
    }

    @Test fun `cancellation after acknowledgement still finishes durable owner cleanup`() = runBlocking {
        val rig = remembered(1)
        val entry = rig.sessions.entries.value.single()
        val work = launch { rig.sessions.closeByOwner(entry.id) }
        rig.host.afterConfirm = { work.cancel() }
        work.join()
        assertNull(rig.records.record(entry.key))
        assertTrue(rig.host.sessions.none { it.id == entry.id })
        assertEquals(0, rig.sessions.count())
    }

    @Test fun `only a real answer that the session is not there says gone`() {
        assertEquals(AttachVerdict.ATTACH, attachOnlyVerdict(TmuxProbe.EXISTING))
        assertEquals(AttachVerdict.GONE, attachOnlyVerdict(TmuxProbe.FRESH))
        assertEquals(AttachVerdict.UNREACHABLE, attachOnlyVerdict(TmuxProbe.UNKNOWN))
        assertEquals(AttachVerdict.UNREACHABLE, attachOnlyVerdict(TmuxProbe.ABSENT))
    }

    @Test fun `a reconnect keeps the tab_id of an assistant session`() {
        val rig = SessionRig(); val live = rig.existing(4); live.flow.value = SshState.Ended("drop")
        val owner = tabs(rig, FakeTerminal().set("x"))
        runBlocking { assertEquals(AttachResult.ATTACHED, rig.sessions.attach(4)) }
        assertEquals(listOf(4), rig.host.sessions.map { it.id }); assertEquals(listOf(4), rig.sessions.entries.value.map { it.id })
        assertEquals(listOf(4), owner.list().map { it.id })
        assertTrue(rig.host.log.single().startsWith("reconnect 4 -> 4"))
    }

    // ---- terminal_show ----

    @Test fun `terminal_show asks the owner once, with the model's reason cleaned, and the owner opening the session answers it`() {
        val rig = SessionRig(); rig.existing(7, number = 3)
        val out = run(TerminalShowTool(rig.sessions), buildJsonObject { put("tab_id", 7); put("reason", "password\u0007 for sudo\n") })
        assertEquals(ToolOutput("The owner was asked to act in tab 7."), out)
        val ask = rig.sessions.asks.value.single()
        assertEquals(7, ask.id); assertEquals("password for sudo", ask.reason); assertEquals("root", ask.user); assertEquals("Home", ask.profile); assertEquals(3, ask.number)
        assertEquals(listOf(ask), rig.asked)
        // The same session asked again replaces its card.
        run(TerminalShowTool(rig.sessions), buildJsonObject { put("tab_id", 7); put("reason", "a choice") })
        assertEquals(listOf("a choice"), rig.sessions.asks.value.map { it.reason })
        assertSame(ChangeCheck.ReadOnly, TerminalShowTool(rig.sessions).changeCheck(buildJsonObject { put("tab_id", 7); put("reason", "x") }))
        // Opening it as a tab shows it and answers the ask.
        val shown = runBlocking { rig.sessions.show(7) }
        assertNotNull(shown); assertTrue(rig.host.sessions.single().shown); assertTrue(rig.sessions.asks.value.isEmpty())
    }

    @Test fun `terminal_show refuses an owner's tab, an unknown tab and a bad reason`() {
        val rig = SessionRig(); rig.tab(5)
        assertEquals(ToolOutput("Tab 5 is the owner's, not one of your sessions. Nothing was asked.", ToolOutcome.FAILED), run(TerminalShowTool(rig.sessions), buildJsonObject { put("tab_id", 5); put("reason", "x") }))
        assertEquals(ToolOutput("Tab 9 is not open. Call terminal_tabs again.", ToolOutcome.FAILED), run(TerminalShowTool(rig.sessions), buildJsonObject { put("tab_id", 9); put("reason", "x") }))
        for (bad in listOf(buildJsonObject { put("tab_id", 5) }, buildJsonObject { put("tab_id", 5); put("reason", "") }, buildJsonObject { put("tab_id", 5); put("reason", "x".repeat(81)) },
            buildJsonObject { put("tab_id", 5); put("reason", "x"); put("extra", 1) }))
            assertEquals(ToolOutput(TerminalShowTool.INVALID, ToolOutcome.FAILED), run(TerminalShowTool(rig.sessions), bad))
        assertTrue(rig.asked.isEmpty())
    }

    // ---- terminal_close ----

    private class Asker(val answer: ConfirmationDecision, val mode: ConfirmationMode = ConfirmationMode.ASK) {
        val asked = ArrayList<ChangeRequest>()
        val support = ConfirmationSupport(policy = { ConfirmationPolicy(global = mode) }, persistOff = { true })
        suspend fun authorize(tool: AgentTool, arguments: JsonObject) = authorizeCall(support, mapOf("terminal_close" to tool), ToolCall("id", "terminal_close", arguments),
            wait = { request, _ -> asked += request; answer }, onNotSaved = {}, io = Dispatchers.Unconfined)
    }

    private fun kernel(asker: Asker, tool: AgentTool, arguments: JsonObject): ToolOutput = runBlocking { asker.authorize(tool, arguments) ?: tool.execute(arguments) }

    @Test fun `terminal_close asks like every changing tool, shows the session, and closes it with its tmux session only after Allow`() {
        val rig = SessionRig(); rig.existing(4, number = 2)
        val asker = Asker(ConfirmationDecision.ONCE)
        val out = kernel(asker, TerminalCloseTool(rig.sessions), tab(4))
        assertEquals(ToolOutput("Session 4 is closed."), out)
        val card = asker.asked.single().close!!
        assertEquals(listOf(4, "Home", "root", "pi", 2), listOf(card.tabId, card.profile, card.user, card.device, card.number))
        assertNull("no session grant for a close", asker.asked.single().target)
        assertEquals(listOf("close 4 tmux=true"), rig.host.log); assertTrue(rig.host.sessions.isEmpty())
    }

    @Test fun `a declined close closes nothing, and the gate cannot be skipped by running the tool without it`() {
        val rig = SessionRig(); rig.existing(4)
        val declined = kernel(Asker(ConfirmationDecision.DENY), TerminalCloseTool(rig.sessions), tab(4))
        assertEquals(ToolOutcome.DECLINED, declined.outcome); assertTrue(rig.host.log.isEmpty())
        // Run with no description (the confirmation hook did not run): nothing is closed.
        val direct = run(TerminalCloseTool(rig.sessions), tab(4))
        assertEquals(ToolOutput(NOT_AUTHORIZED, ToolOutcome.FAILED), direct); assertTrue(rig.host.log.isEmpty())
        // With requests off the gate does not ask, as for every changing tool.
        val off = Asker(ConfirmationDecision.DENY, ConfirmationMode.OFF)
        assertEquals("Session 4 is closed.", kernel(off, TerminalCloseTool(rig.sessions), tab(4)).text); assertTrue(off.asked.isEmpty())
    }

    @Test fun `terminal_close never closes an owner's tab, and answers an unknown tab`() {
        val rig = SessionRig(); rig.tab(5)
        val asker = Asker(ConfirmationDecision.ONCE)
        assertEquals(ToolOutput("Tab 5 is the owner's; only the owner closes it.", ToolOutcome.FAILED), kernel(asker, TerminalCloseTool(rig.sessions), tab(5)))
        assertEquals(ToolOutput("Tab 9 is not open. Call terminal_tabs again.", ToolOutcome.FAILED), kernel(asker, TerminalCloseTool(rig.sessions), tab(9)))
        assertTrue("nothing to ask about", asker.asked.isEmpty()); assertTrue(rig.host.log.isEmpty()); assertEquals(1, rig.host.sessions.size)
        // Even a call that went through the hook for another tab cannot reach it.
        val tool = TerminalCloseTool(rig.sessions)
        rig.existing(6); (tool.changeCheck(tab(6)) as ChangeCheck.Change)
        assertEquals("Tab 5 is the owner's; only the owner closes it.", run(tool, tab(5)).text)
    }

    @Test fun `a session that changed between the card and the run is not closed`() {
        val rig = SessionRig(); rig.existing(4)
        val tool = TerminalCloseTool(rig.sessions)
        assertTrue(tool.changeCheck(tab(4)) is ChangeCheck.Change)
        rig.host.sessions.clear(); rig.existing(4).also { }  // another session under the same id (a different tmux session)
        rig.host.sessions.clear()
        rig.host.sessions += FakeHosted(4, SessionOpener.ASSISTANT, key = SessionKey("p1", "pi", 22, "root", "frpjump-0a1b2c-99"))
        assertEquals(ToolOutput(NOT_AUTHORIZED, ToolOutcome.FAILED), run(tool, tab(4))); assertTrue(rig.host.log.isEmpty())
    }

    @Test fun `closing a session that cannot be reached closes nothing`() {
        val rig = remembered(1); rig.targets.ready.clear()
        val id = tabs(rig, FakeTerminal()).list().single().id
        val tool = TerminalCloseTool(rig.sessions)
        assertTrue(tool.changeCheck(tab(id)) is ChangeCheck.Change)
        assertEquals(ToolOutput("Could not reach session $id to close it. It was not closed. Try again later.", ToolOutcome.FAILED), run(tool, tab(id)))
        assertTrue(rig.host.log.isEmpty()); assertEquals(1, rig.sessions.count())
    }

    @Test fun `the owner closing the assistant's session takes the same way, no question`() {
        val rig = SessionRig(); rig.existing(4)
        runBlocking { rig.sessions.closeByOwner(4) }
        assertEquals(listOf("confirm 4", "close 4 tmux=false"), rig.host.log)
    }

    @Test fun `the assistant renames a remembered session without attaching it`() {
        val rig = remembered(2)
        val owner = tabs(rig, FakeTerminal())
        val id = owner.list().single().id
        runBlocking { owner.rename(id, "build") }
        assertEquals("build", owner.list().single().name)
        assertEquals(TabName("build", NameSetter.ASSISTANT), rig.records.get(SessionKey("p1", "pi", 22, "root", "frpjump-0a1b2c-2")))
        assertTrue(rig.host.log.isEmpty())
    }
}
