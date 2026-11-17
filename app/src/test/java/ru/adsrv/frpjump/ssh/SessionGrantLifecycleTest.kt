package ru.adsrv.frpjump.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.api.ConsumedGrant
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.appSessionGrants
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.lock.AppLock
import ru.adsrv.frpjump.lock.PromptHost
import ru.adsrv.frpjump.lock.PromptListener
import ru.adsrv.frpjump.ops.ActionClass
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.ProfileIndex
import ru.adsrv.frpjump.settings.*
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.VisitorState
import ru.adsrv.frpjump.undo.PendingUndo

/**
 * The real tunnel manager, runtimes, [SshTargets], Undo queue and app lock behind the app's grants, built by the same factory the application uses
 * ([appSessionGrants]): each row of the contract's "When a grant ends", with no conversation anywhere. "Stays" cases prove the period is not too short.
 */
class SessionGrantLifecycleTest {
    private val target = GrantTarget("home", "ssh")
    private val key = GrantKey(ActionClass.CONNECTION_CHANGE, target)
    private val tool = AssistantToolId.SSH_COMMAND

    private class Host : PromptHost {
        val listeners = mutableListOf<PromptListener>()
        override fun isStateSaved(owner: Any) = false
        override fun show(owner: Any, title: String, subtitle: String, listener: PromptListener) { listeners += listener }
    }

    private inner class Rig(val f: SshTargetsTest.Fixture, cleaner: Boolean = true) : AutoCloseable {
        val settings = SettingsStore(MemoryStorage()).apply { update { it.copy(assistantEnabled = true, assistantSshCommand = true, assistantTerminalTabs = true, assistantTerminalInput = true) } }
        /** The app's Undo queue, whose timer never fires: a test hides with [PendingUndo.enqueue] and cancels with [PendingUndo.undoAll]. */
        val undo = PendingUndo(timer = { _, _ -> {} }, run = { block -> runBlocking { block() }; Unit }, announce = { _, _ -> })
        val targets = SshTargets(f.profiles.state, undo.hidden, f.env, undo::hideCount)
        @Volatile var now = 0L
        val host = Host()
        val lock = AppLock(clock = { now }, host = host)
        private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        /** The app's own factory; with [cleaner] false the cleaner has nothing to hear, so only the checks at use can end a grant. */
        val grants = if (cleaner) appSessionGrants(targets, settings, lock, scope, tick = emptyFlow<Unit>())
            else SessionGrants(ru.adsrv.frpjump.assistant.AppGrantWorld(targets, settings, lock))
        fun give(): Boolean = grants.record(key, tool, grants.stamp(target)!!, grants.coverableBy(tool))
        /** What the cleaner alone left: nothing here looks the grant up (a lookup would drop a stale one by itself). */
        val held: Boolean get() {
            // The cleaner runs on its own thread: give it a moment before saying the grant is still there.
            val until = System.nanoTime() + 500_000_000L
            while (grants.observe().value.isNotEmpty() && System.nanoTime() < until) Thread.sleep(5)
            return grants.observe().value.isNotEmpty()
        }
        fun hideTunnel() = undo.enqueue(PendingUndo.tunnelKey("home", "same", 22), commit = {})
        /** The owner opens the app with the lock on and unlocks it. */
        fun openApp() {
            settings.update { it.copy(appLock = true, appLockAfter = AppLockAfter.SECONDS_30) }
            lock.onStart(true, 30_000); lock.authenticateFor(Any(), "t", "s") {}; host.listeners.last().succeeded()
        }
        override fun close() { scope.cancel() }
    }

    private fun run(cleaner: Boolean = true, block: suspend (Rig) -> Unit) = runBlocking { SshTargetsTest.Fixture().use { f -> f.ready(); Rig(f, cleaner).use { block(it) } } }

    private suspend fun SshTargetsTest.Fixture.resync(grantList: List<ConsumedGrant> = grants) { grants = grantList; manager.sync(); withTimeout(5000) { manager.state.first { s -> s.tunnels.size == grantList.size } } }
    private val original = listOf(ConsumedGrant("g", "secret", "svc", "ssh", "same", 22))

    @Test fun `the grant is born with the open period and a repeated read sees the same`() = run { r ->
        assertNotNull(r.targets.period("home", "ssh")); assertEquals(r.targets.period("home", "ssh"), r.targets.period("home", "ssh"))
        assertTrue(r.give()); assertTrue(r.held); assertTrue(r.grants.covers(tool, key))
    }

    @Test fun `a transient change of the tunnel keeps the period and the grant`() = run { r ->
        val p = r.targets.period("home", "ssh")
        assertTrue(r.give())
        // frpc reconnect, via-server/direct, rebind: the runner's states change, the plan does not.
        @Suppress("UNCHECKED_CAST") val states = r.f.runner.states as MutableStateFlow<Map<String, VisitorState>>
        states.value = mapOf("g" to VisitorState(TunnelStatus.CONNECTING)); states.value = mapOf("g" to VisitorState(TunnelStatus.RELAYED))
        r.f.resync(original)
        assertEquals(p, r.targets.period("home", "ssh")); assertTrue(r.held); assertTrue(r.grants.covers(tool, key))
        // The profile's name and colour are not part of what the grant is bound to.
        val s = r.f.profiles.state.value
        r.f.profiles.state.value = s.copy(index = ProfileIndex(s.index.profiles.map { if (it.id == "home") it.copy(name = "Renamed", color = "red") else it }, s.index.current))
        assertTrue("rename and colour keep it", r.held); assertTrue(r.grants.covers(tool, key))
    }

    @Test fun `a different grant id under the same key ends the period because the two cannot be told apart`() = run { r ->
        val p = r.targets.period("home", "ssh")!!
        assertTrue(r.give())
        r.f.resync(listOf(ConsumedGrant("g2", "secret2", "svc", "ssh", "same", 22)))
        assertNotEquals("another grant is another period", p, r.targets.period("home", "ssh"))
        assertFalse(r.held); assertFalse(r.grants.covers(tool, key))
    }

    @Test fun `disconnect then connect ends the grant and a new connect has a new period`() = run { r ->
        val p = r.targets.period("home", "ssh")!!
        assertTrue(r.give())
        r.f.resync(emptyList())
        assertNull(r.targets.period("home", "ssh")); assertFalse("the cleaner dropped it with no lookup", r.held)
        r.f.resync(original)
        val q = r.targets.period("home", "ssh")!!
        assertNotEquals("a period is never reused", p, q)
        assertFalse("and the old grant does not come back", r.grants.covers(tool, key))
    }

    @Test fun `stopping everything - disconnect all or the server removing this phone - ends the period for good`() {
        run { r ->
            val p = r.targets.period("home", "ssh")!!
            assertTrue(r.give())
            r.f.manager.disconnectAll()
            r.f.resync(original)
            assertFalse(r.held)
            assertNotEquals("the same key planned again is a new period, never the old one", p, r.targets.period("home", "ssh"))
            assertFalse(r.grants.covers(tool, key))
        }
        run { r ->
            val p = r.targets.period("home", "ssh")!!
            assertTrue(r.give())
            r.f.runtime.session.markRemoved()
            withTimeout(5000) { while (r.targets.period("home", "ssh") != null) delay(10) }
            assertFalse(r.held); assertFalse(r.grants.covers(tool, key)); assertNull("nothing runs for a removed phone", r.targets.period("home", "ssh"))
        }
    }

    @Test fun `a disconnect that is pending ends the grant for good even when it fails`() = run { r ->
        val p = r.targets.period("home", "ssh")!!
        assertTrue(r.give())
        r.f.startPending()
        assertNull(r.targets.period("home", "ssh")); assertFalse(r.held)
        r.f.hold.complete(Unit)
        withTimeout(5000) { r.f.manager.state.first { it.pending.isEmpty() } }
        r.f.manager.sync()
        assertNotEquals("the tunnel stays but this is a new period", p, r.targets.period("home", "ssh")); assertFalse(r.grants.covers(tool, key))
    }

    @Test fun `hiding the tunnel behind Undo ends the grant even when Undo then cancels`() = run { r ->
        assertTrue(r.give())
        r.hideTunnel()
        assertFalse(r.held)
        r.undo.undoAll()
        assertFalse("Undo cancelled the disconnect: the grant is still gone", r.held); assertFalse(r.grants.covers(tool, key))
        assertTrue("a new answer is possible again", r.give())
        r.undo.enqueue(PendingUndo.connectionKey("home", "ssh"), commit = {})
        assertFalse("a connection hidden for deletion ends it too", r.held)
    }

    @Test fun `hide and Undo with nothing looking in between still ends the grant`() = run(cleaner = false) { r ->
        assertTrue(r.give())
        // No cleaner, no lookup while hidden: the hide count in the period is what remembers it.
        r.hideTunnel(); r.undo.undoAll()
        assertNotNull("the tunnel is open again", r.targets.period("home", "ssh"))
        assertFalse(r.grants.covers(tool, key))
        assertTrue("a new answer works", r.give()); assertTrue(r.grants.covers(tool, key))
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

    @Test fun `assistant off and off then on and a tool's switch end the grant`() {
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
    }

    @Test fun `a tool switched off and on again with no cleaner and no lookup in between is not covered`() = run(cleaner = false) { r ->
        assertTrue(r.give())
        r.settings.setAssistantTool(tool, false); r.settings.setAssistantTool(tool, true)
        assertFalse("the off count remembers it", r.grants.covers(tool, key)); assertTrue(r.grants.covers(AssistantToolId.TERMINAL_INPUT, key))
    }

    @Test fun `the app lock ends the grant when it engages and when its interval runs out while out of sight, with the lock off nothing changes`() {
        // The interval runs out with no event at all: the next use sees it.
        run(cleaner = false) { r ->
            r.openApp(); assertTrue(r.give()); assertTrue(r.grants.covers(tool, key))
            r.lock.onStop(); r.now = 10_000
            assertTrue("inside the interval", r.grants.covers(tool, key))
            r.now = 31_000
            assertNull("no new grant can be given either", r.grants.stamp(target))
            assertFalse("the interval ran out: it would lock on return", r.grants.covers(tool, key)); assertTrue(r.grants.snapshot().isEmpty())
        }
        // The lock engaged: the cleaner clears it with no lookup.
        run { r ->
            r.openApp(); assertTrue(r.give())
            r.lock.onStop(); r.now = 31_000
            r.lock.onStart(true, 30_000)
            assertTrue(r.lock.locked.value); assertFalse("the app lock ends it", r.held)
        }
        // Immediately: any time out of sight is enough.
        run(cleaner = false) { r ->
            r.settings.update { it.copy(appLock = true, appLockAfter = AppLockAfter.IMMEDIATELY) }
            r.lock.onStart(true, 0); r.lock.authenticateFor(Any(), "t", "s") {}; r.host.listeners.last().succeeded()
            assertTrue(r.give()); r.lock.onStop(); assertFalse(r.grants.covers(tool, key))
        }
        // The app lock off: out of sight for ever and nothing happens.
        run(cleaner = false) { r ->
            r.lock.onStart(false); assertTrue(r.give()); r.lock.onStop(); r.now = 100 * 60_000L
            assertTrue(r.grants.covers(tool, key))
        }
    }
}
