package ru.adsrv.frpjump.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.api.ConsumedGrant
import ru.adsrv.frpjump.assistant.AppGrantWorld
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ops.ActionClass
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.ProfileIndex
import ru.adsrv.frpjump.settings.*
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.VisitorState
import ru.adsrv.frpjump.undo.PendingUndo

/**
 * The real tunnel manager, runtimes and [SshTargets] behind the app's grants ([AppGrantWorld] + [SessionGrantCleaner]): each row of the
 * contract's "When a grant ends", with no conversation anywhere. One test per row; "stays" cases prove the period is not too short.
 */
class SessionGrantLifecycleTest {
    private val target = GrantTarget("home", "ssh")
    private val key = GrantKey(ActionClass.CONNECTION_CHANGE, target)
    private val tool = AssistantToolId.SSH_COMMAND

    private inner class Rig(val f: SshTargetsTest.Fixture) : AutoCloseable {
        val settings = SettingsStore(MemoryStorage()).apply { update { it.copy(assistantEnabled = true, assistantSshCommand = true, assistantTerminalTabs = true, assistantTerminalInput = true) } }
        val grants = SessionGrants(AppGrantWorld(f.targets, settings))
        val locked = MutableStateFlow(false)
        private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        init {
            SessionGrantCleaner(grants, scope, merge(f.targets.changes(), settings.assistantDisables, settings.settings.map { it.assistantEnabled to it.assistantTools() }.distinctUntilChanged()), locked)
        }
        fun give(): Boolean = grants.record(key, tool, grants.stamp(target)!!)
        /** What the cleaner alone left: nothing here looks the grant up (a lookup would drop a stale one by itself). */
        val held get() = grants.observe().value.isNotEmpty()
        override fun close() { scope.cancel() }
    }

    private fun run(block: suspend (Rig) -> Unit) = runBlocking { SshTargetsTest.Fixture().use { f -> f.ready(); Rig(f).use { block(it) } } }

    private suspend fun SshTargetsTest.Fixture.resync(grantList: List<ConsumedGrant> = grants) { grants = grantList; manager.sync(); withTimeout(5000) { manager.state.first { s -> s.tunnels.size == grantList.size } } }
    private val original = listOf(ConsumedGrant("g", "secret", "svc", "ssh", "same", 22))

    @Test fun `the grant is born with the open period and a repeated read sees the same`() = run { r ->
        assertNotNull(r.f.targets.period("home", "ssh")); assertEquals(r.f.targets.period("home", "ssh"), r.f.targets.period("home", "ssh"))
        assertTrue(r.give()); assertTrue(r.held); assertTrue(r.grants.covers(tool, key))
    }

    @Test fun `a transient change of the tunnel keeps the period and the grant`() = run { r ->
        val p = r.f.targets.period("home", "ssh")
        assertTrue(r.give())
        // frpc reconnect, via-server/direct, rebind: the runner's states change, the plan does not.
        @Suppress("UNCHECKED_CAST") val states = r.f.runner.states as MutableStateFlow<Map<String, VisitorState>>
        states.value = mapOf("g" to VisitorState(TunnelStatus.CONNECTING)); states.value = mapOf("g" to VisitorState(TunnelStatus.RELAYED))
        // A new grant id and the same key (a restore): still the same open tunnel.
        r.f.resync(listOf(ConsumedGrant("g2", "secret2", "svc", "ssh", "same", 22)))
        assertEquals(p, r.f.targets.period("home", "ssh")); assertTrue(r.held); assertTrue(r.grants.covers(tool, key))
        // The profile's name and colour are not part of what the grant is bound to.
        val s = r.f.profiles.state.value
        r.f.profiles.state.value = s.copy(index = ProfileIndex(s.index.profiles.map { if (it.id == "home") it.copy(name = "Renamed", color = "red") else it }, s.index.current))
        assertTrue("rename and colour keep it", r.held); assertTrue(r.grants.covers(tool, key))
    }

    @Test fun `disconnect then connect ends the grant and a new connect has a new period`() = run { r ->
        val p = r.f.targets.period("home", "ssh")!!
        assertTrue(r.give())
        r.f.resync(emptyList())
        assertNull(r.f.targets.period("home", "ssh")); assertFalse("the cleaner dropped it with no lookup", r.held)
        r.f.resync(original)
        val q = r.f.targets.period("home", "ssh")!!
        assertNotEquals("a period is never reused", p, q); assertTrue(q > p)
        assertFalse("and the old grant does not come back", r.grants.covers(tool, key))
    }

    @Test fun `a disconnect that is pending ends the grant for good even when it fails`() = run { r ->
        val p = r.f.targets.period("home", "ssh")!!
        assertTrue(r.give())
        r.f.startPending()
        assertNull(r.f.targets.period("home", "ssh")); assertFalse(r.held)
        r.f.hold.complete(Unit)
        withTimeout(5000) { r.f.manager.state.first { it.pending.isEmpty() } }
        r.f.manager.sync()
        assertNotEquals("the tunnel stays but this is a new period", p, r.f.targets.period("home", "ssh")); assertFalse(r.grants.covers(tool, key))
    }

    @Test fun `hiding the tunnel behind Undo ends the grant even when Undo then cancels`() = run { r ->
        assertTrue(r.give())
        r.f.profiles.hidden.value = setOf(PendingUndo.tunnelKey("home", "same", 22))
        assertFalse(r.held)
        r.f.profiles.hidden.value = emptySet()
        assertFalse("Undo cancelled the disconnect: the grant is still gone", r.held); assertFalse(r.grants.covers(tool, key))
        assertTrue("a new answer is possible again", r.give())
        r.f.profiles.hidden.value = setOf(PendingUndo.connectionKey("home", "ssh"))
        assertFalse("a connection hidden for deletion ends it too", r.held)
    }

    @Test fun `each field of the connection ends the grant when edited`() {
        val base = Connection("ssh", "same", 22, PortKind.SSH, user = "saved-user")
        val edits = mapOf("device" to base.copy(device = "other"), "port" to base.copy(port = 2222), "kind" to base.copy(kind = PortKind.TCP),
            "customName" to base.copy(customName = "N"), "https" to base.copy(https = true), "user" to base.copy(user = "admin"), "localPort" to base.copy(localPort = 41000))
        for ((what, edited) in edits) run { r ->
            assertTrue(r.give()); r.f.runtime.connections.save(edited)
            assertFalse("an edit of $what ends the grant", r.held); assertFalse(what, r.grants.covers(tool, key))
        }
        run { r -> assertTrue(r.give()); r.f.runtime.connections.remove("ssh"); assertFalse("deleted", r.held) }
    }

    @Test fun `the profile's key alias activation enrollment and removal end the grant but not its name or colour`() {
        fun edit(r: Rig, change: (ru.adsrv.frpjump.profiles.Profile) -> ru.adsrv.frpjump.profiles.Profile) {
            val s = r.f.profiles.state.value
            r.f.profiles.state.value = s.copy(index = ProfileIndex(s.index.profiles.map { if (it.id == "home") change(it) else it }, s.index.current))
        }
        run { r -> assertTrue(r.give()); edit(r) { it.copy(keyAlias = "other-alias") }; assertFalse("keyAlias", r.held) }
        run { r -> assertTrue(r.give()); edit(r) { it.copy(activationPending = true) }; assertFalse("activation", r.held) }
        run { r -> assertTrue(r.give()); r.f.profiles.state.value = r.f.profiles.state.value.let { it.copy(enrollments = it.enrollments + ("home" to 1)) }; assertFalse("re-enrollment", r.held) }
        run { r -> assertTrue(r.give()); val s = r.f.profiles.state.value; r.f.profiles.state.value = s.copy(index = ProfileIndex(s.index.profiles.filter { it.id != "home" }, "work")); assertFalse("Forget profile", r.held) }
        run { r -> assertTrue(r.give()); edit(r) { it.copy(name = "Renamed") }; assertTrue("name", r.held); edit(r) { it.copy(color = "red") }; assertTrue("colour", r.held) }
    }

    @Test fun `a changed SSH login method or user and a changed or forgotten host key end the grant`() {
        run { r -> assertTrue(r.give()); r.f.runtime.sshLogins.remember("same", 22, SshLogin("port-user", SshAuthMethod.KEY)); assertFalse("login method", r.held) }
        run { r -> assertTrue(r.give()); r.f.runtime.sshLogins.remember("same", 22, SshLogin("other-user", SshAuthMethod.PASSWORD)); assertFalse("login user", r.held) }
        run { r -> assertTrue(r.give()); r.f.runtime.hostKeys.pin("same", 22, PinnedHostKey("ssh-rsa", "changed")); assertFalse("pinned host key", r.held) }
        run { r -> assertTrue(r.give()); r.f.runtime.hostKeys.forget("same", 22); assertFalse("forgotten host key", r.held) }
        // The same login written again changes nothing.
        run { r -> assertTrue(r.give()); r.f.runtime.sshLogins.remember("same", 22, SshLogin("port-user", SshAuthMethod.PASSWORD)); assertTrue(r.held) }
    }

    @Test fun `assistant off and off then on and a tool's switch and the app lock end the grant`() {
        run { r -> assertTrue(r.give()); r.settings.update { it.copy(assistantEnabled = false) }; assertFalse("off", r.held) }
        run { r -> assertTrue(r.give()); r.settings.update { it.copy(assistantEnabled = false) }; r.settings.update { it.copy(assistantEnabled = true) }; assertFalse("off/on", r.held); assertFalse(r.grants.covers(tool, key)) }
        run { r ->
            assertTrue(r.give()); r.settings.setAssistantTool(tool, false)
            assertTrue("the grant stays for typing, which is still in effect", r.held)
            assertFalse("ssh_command left it", r.grants.covers(tool, key)); assertTrue(r.grants.covers(AssistantToolId.TERMINAL_INPUT, key))
            r.settings.setAssistantTool(AssistantToolId.TERMINAL_INPUT, false); assertFalse("an empty grant is dropped", r.held)
        }
        run { r ->
            assertTrue(r.give()); r.settings.setAssistantTool(tool, false); r.settings.setAssistantTool(tool, true)
            assertFalse("switching it on again does not bring ssh_command back", r.grants.covers(tool, key))
            assertTrue("terminal_input stays covered", r.grants.covers(AssistantToolId.TERMINAL_INPUT, key))
        }
        run { r -> assertTrue(r.give()); r.settings.setAssistantTool(AssistantToolId.TERMINAL_TABS, false); assertTrue("ssh stays", r.grants.covers(tool, key)); assertFalse("typing falls out of effect with the tabs", r.grants.covers(AssistantToolId.TERMINAL_INPUT, key)) }
        run { r -> assertTrue(r.give()); r.locked.value = true; assertFalse("the app lock ends it", r.held); r.locked.value = false; assertFalse(r.grants.covers(tool, key)) }
    }
}
