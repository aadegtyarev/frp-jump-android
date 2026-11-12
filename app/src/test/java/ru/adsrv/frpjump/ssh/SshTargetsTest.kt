package ru.adsrv.frpjump.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.api.*
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ports.*
import ru.adsrv.frpjump.profiles.*
import ru.adsrv.frpjump.tunnel.*
import ru.adsrv.frpjump.undo.PendingUndo
import java.util.concurrent.atomic.AtomicInteger

/** SshTargets: the readiness, loss and direct-login policy that the assistant and the Connections UI share. */
class SshTargetsTest {
    private class Fixture : AutoCloseable {
        val profiles = SyntheticProfileInformation()
        val box = FakeKeystore().box("synthetic-ssh")
        val passwords = SshPasswords(MemoryStorage(), box)
        val env = SshEnvironment(SshKeyStore(MemoryStorage(), box), passwords, { "synthetic" }, TmuxNames(MemoryStorage()),
            clientFactory = { error("No client") })
        val runtime = profiles.runtimes.getValue("home")
        val runner = object : FrpcControl {
            override val states = MutableStateFlow(mapOf("g" to VisitorState(TunnelStatus.RELAYED)))
            override val heldPorts = emptySet<Int>()
            override fun apply(launch: FrpcLaunch, ports: Set<Int>) = Unit
            override fun stop() = Unit
            override fun wipe() = Unit
            override fun recentLog() = emptyList<String>()
            override fun expireTrying() = Unit
            override fun refusalsOf(grant: String) = 0
            override fun deviceReady(grant: String, refusalsBefore: Int) = Unit
        }
        var grants = listOf(ConsumedGrant("g", "secret", "svc", "ssh", "same", 22))
        val manager = TunnelManager("home", runtime.store::state, object : AgentApi {
            override suspend fun heartbeat(c: ru.adsrv.frpjump.store.Credentials) = Unit
            override suspend fun desiredState(c: ru.adsrv.frpjump.store.Credentials) = DesiredStateResponse("id", "relay", 7000, grants)
            override suspend fun connect(c: ru.adsrv.frpjump.store.Credentials, deviceName: String, targetPort: Int, protocol: String): ConnectResponse = error("No tunnel start")
            override suspend fun disconnect(c: ru.adsrv.frpjump.store.Credentials, deviceName: String, targetPort: Int): Boolean {
                if (holding) hold.await()
                error("No tunnel control")
            }
        }, runtime.session, LocalPortStore(MemoryStorage()), runner, { true })
        val targets = SshTargets(profiles.state, profiles.hidden, env)
        @Volatile var holding = false
        val hold = CompletableDeferred<Unit>()
        private val scope = CoroutineScope(Job())
        /** A disconnect held inside the API: the tunnel is pending until [close]. */
        suspend fun startPending() {
            holding = true
            scope.launch { manager.disconnect("same", 22) }
            withTimeout(5000) { manager.state.first { it.pending.isNotEmpty() } }
        }
        suspend fun ready() {
            runtime.tunnels.close()
            ProfileRuntime::class.java.getDeclaredField("tunnels").apply { isAccessible = true }.set(runtime, manager)
            runtime.connections.save(Connection("ssh", "same", 22, PortKind.SSH, user = "saved-user"))
            runtime.sshLogins.remember("same", 22, SshLogin("port-user", SshAuthMethod.PASSWORD))
            passwords.remember("home", "same", 22, "saved-user", "private-saved-password")
            runtime.hostKeys.pin("same", 22, PinnedHostKey("ssh-rsa", "pin"))
            manager.sync()
            withTimeout(5000) { manager.state.first { it.tunnels.size == 1 } }
        }
        fun target(): SavedSshTarget = (targets.resolveSaved("home", "ssh") as SshTargetResolution.Ready).target
        fun resolved() = targets.resolveSaved("home", "ssh")
        override fun close() { hold.complete(Unit); scope.cancel(); manager.close(); profiles.close() }
    }

    private fun <T> fixture(block: suspend (Fixture) -> T): T = runBlocking { Fixture().use { f -> f.ready(); block(f) } }

    // ---- resolveSaved: every refusal branch, nothing is started ----

    @Test fun `ready saved SSH connection resolves with its tunnel`() = fixture { f ->
        val t = f.target()
        assertEquals("home", t.profile); assertEquals("ssh", t.connection.id)
        assertEquals(f.manager.state.value.tunnels.single().localPort, t.tunnel.localPort)
        assertTrue(f.targets.isAvailable(t))
    }

    @Test fun `resolveSaved refuses profile removed or unknown or not loaded or ambiguous`() {
        val changes = listOf<(Fixture) -> Unit>(
            { it.profiles.state.value = it.profiles.state.value.copy(runtimes = emptyMap()) },
            { it.profiles.state.value = it.profiles.state.value.copy(index = ProfileIndex(emptyList())) },
            { it.profiles.state.value = it.profiles.state.value.copy(loaded = false) },
            { it.profiles.state.value = it.profiles.state.value.copy(index = ProfileIndex(it.profiles.profiles + it.profiles.profiles.last())) },
        )
        for (change in changes) fixture { f -> change(f); assertEquals(SshTargetResolution.Unavailable, f.resolved()) }
        fixture { f -> assertEquals(SshTargetResolution.Unavailable, f.targets.resolveSaved("missing", "ssh")) }
    }

    @Test fun `resolveSaved refuses connection missing non-SSH or unknown id`() {
        fixture { f -> f.runtime.connections.remove("ssh"); assertEquals(SshTargetResolution.Unavailable, f.resolved()) }
        fixture { f -> f.runtime.connections.save(Connection("ssh", "same", 22, PortKind.TCP)); assertEquals(SshTargetResolution.Unavailable, f.resolved()) }
        fixture { f -> assertEquals(SshTargetResolution.Unavailable, f.targets.resolveSaved("home", "other")) }
    }

    @Test fun `resolveSaved refuses connection or tunnel hidden by Undo`() {
        fixture { f -> f.profiles.hidden.value = setOf(PendingUndo.connectionKey("home", "ssh")); assertEquals(SshTargetResolution.Unavailable, f.resolved()) }
        fixture { f -> f.profiles.hidden.value = setOf(PendingUndo.tunnelKey("home", "same", 22)); assertEquals(SshTargetResolution.Unavailable, f.resolved()) }
    }

    @Test fun `resolveSaved refuses removed session and activationPending`() {
        fixture { f -> f.runtime.session.markRemoved(); assertEquals(SshTargetResolution.Unavailable, f.resolved()) }
        fixture { f ->
            val s = f.profiles.state.value
            f.profiles.state.value = s.copy(index = ProfileIndex(s.index.profiles.map { if (it.id == "home") it.copy(activationPending = true) else it }, "work"))
            assertEquals(SshTargetResolution.Unavailable, f.resolved())
        }
    }

    @Test fun `resolveSaved refuses tunnel gone failed or pending`() {
        fixture { f -> f.grants = emptyList(); f.manager.sync(); f.manager.state.first { it.tunnels.isEmpty() }; assertEquals(SshTargetResolution.Unavailable, f.resolved()) }
        fixture { f ->
            (f.runner.states as MutableStateFlow).value = mapOf("g" to VisitorState(TunnelStatus.FAILED))
            f.manager.state.first { it.tunnels.single().state.status == TunnelStatus.FAILED }
            assertEquals(SshTargetResolution.Unavailable, f.resolved())
        }
        fixture { f -> f.startPending(); assertEquals(SshTargetResolution.Unavailable, f.resolved()) }
    }

    @Test fun `resolveSaved refuses tunnel waiting for peer or device or device missing`() {
        for (s in listOf(VisitorState(TunnelStatus.RELAYED, waitingForPeer = true), VisitorState(TunnelStatus.RELAYED, waitingForDevice = true),
            VisitorState(TunnelStatus.RELAYED, deviceMissing = true))) fixture { f ->
            (f.runner.states as MutableStateFlow).value = mapOf("g" to s)
            f.manager.state.first { it.tunnels.singleOrNull()?.let { t -> t.state.waitingForPeer || t.state.waitingForDevice || t.state.deviceMissing } == true }
            assertEquals(SshTargetResolution.Unavailable, f.resolved())
        }
    }

    @Test fun `resolveSaved refuses several tunnels for the connection port`() = fixture { f ->
        f.grants = listOf(f.grants.single(), f.grants.single().copy(grantId = "g2", secret = "secret2"))
        (f.runner.states as MutableStateFlow).value = mapOf("g" to VisitorState(TunnelStatus.RELAYED), "g2" to VisitorState(TunnelStatus.RELAYED))
        f.manager.sync(); withTimeout(5000) { f.manager.state.first { it.tunnels.size == 2 } }
        assertEquals(SshTargetResolution.Unavailable, f.resolved())
    }
    @Test fun `resolveSaved accepts each ready tunnel status`() {
        for (s in listOf(TunnelStatus.DIRECT, TunnelStatus.RELAYED, TunnelStatus.TRYING)) fixture { f ->
            (f.runner.states as MutableStateFlow).value = mapOf("g" to VisitorState(s))
            f.manager.state.first { it.tunnels.singleOrNull()?.state?.status == s }
            assertTrue(f.resolved() is SshTargetResolution.Ready)
        }
    }

    // ---- isAvailable: a captured target does not follow a replacement ----

    @Test fun `captured target is lost on enrollment change profile removal replaced connection hide pending removal and tunnel loss`() {
        val losses = listOf<suspend (Fixture) -> Unit>(
            { it.profiles.state.value = it.profiles.state.value.copy(enrollments = mapOf("home" to 1)) },
            { it.profiles.state.value = it.profiles.state.value.copy(runtimes = emptyMap()) },
            { it.profiles.state.value = it.profiles.state.value.copy(loaded = false) },
            { it.runtime.connections.save(it.runtime.connections.connections.value.single().copy(user = "replacement-user")) },
            { it.runtime.connections.remove("ssh") },
            { it.profiles.hidden.value = setOf(PendingUndo.connectionKey("home", "ssh")) },
            { it.profiles.hidden.value = setOf(PendingUndo.tunnelKey("home", "same", 22)) },
            { it.runtime.session.markRemoved() },
            { it.startPending() },
            { it.grants = emptyList(); it.manager.sync(); it.manager.state.first { s -> s.tunnels.isEmpty() } },
            { (it.runner.states as MutableStateFlow).value = mapOf("g" to VisitorState(TunnelStatus.FAILED)); it.manager.state.first { s -> s.tunnels.single().state.status == TunnelStatus.FAILED } },
        )
        for ((i, loss) in losses.withIndex()) fixture { f -> val t = f.target(); loss(f); assertFalse("loss #$i", f.targets.isAvailable(t)) }
    }

    @Test fun `a tunnel with another grant or local port is not the captured one`() = fixture { f ->
        val t = f.target()
        fun with(tunnel: Tunnel) = SavedSshTarget(t.profile, t.runtime, t.enrollment, t.connection, tunnel)
        assertTrue(f.targets.isAvailable(with(t.tunnel)))
        assertFalse(f.targets.isAvailable(with(t.tunnel.copy(grantId = "g-other"))))
        assertFalse(f.targets.isAvailable(with(t.tunnel.copy(localPort = t.tunnel.localPort + 1))))
    }

    @Test fun `Undo hide then restore does not revive a lost target but a new resolve succeeds`() = fixture { f ->
        val t = f.target()
        f.profiles.hidden.value = setOf(PendingUndo.connectionKey("home", "ssh")); f.profiles.hidden.value = emptySet()
        assertTrue(f.targets.isAvailable(t))
        assertTrue(f.resolved() is SshTargetResolution.Ready)
    }

    @Test fun `profile with activationPending makes a captured target unavailable`() = fixture { f ->
        val t = f.target()
        val s = f.profiles.state.value
        f.profiles.state.value = s.copy(index = ProfileIndex(s.index.profiles.map { if (it.id == "home") it.copy(activationPending = true) else it }, "work"))
        assertFalse(f.targets.isAvailable(t))
    }

    // ---- watchLost: each of the six sources ----

    private fun watch(change: suspend (Fixture) -> Unit, restore: suspend (Fixture) -> Unit = {}) = fixture { f ->
        val t = f.target()
        val lost = AtomicInteger()
        val scope = CoroutineScope(Job())
        try {
            val jobs = f.targets.watchLost(t, scope) { lost.incrementAndGet() }
            assertEquals(6, jobs.size); assertEquals(0, lost.get())
            change(f)
            withTimeoutOrNull(5000) { while (lost.get() == 0) delay(10) }
            assertTrue("onLost not called", lost.get() > 0)
            restore(f)
        } finally { scope.cancel() }
    }

    @Test fun `watchLost profiles source enrollment change`() = watch({ it.profiles.state.value = it.profiles.state.value.copy(enrollments = mapOf("home" to 1)) })
    @Test fun `watchLost profiles source activationPending`() = watch({ f ->
        val s = f.profiles.state.value
        f.profiles.state.value = s.copy(index = ProfileIndex(s.index.profiles.map { if (it.id == "home") it.copy(activationPending = true) else it }, "work"))
    })
    @Test fun `watchLost hidden source connection Undo`() = watch({ it.profiles.hidden.value = setOf(PendingUndo.connectionKey("home", "ssh")) })
    @Test fun `watchLost hidden source tunnel Undo`() = watch({ it.profiles.hidden.value = setOf(PendingUndo.tunnelKey("home", "same", 22)) })
    @Test fun `watchLost connections source`() = watch({ it.runtime.connections.save(it.runtime.connections.connections.value.single().copy(user = "replacement-user")) })
    @Test fun `watchLost connections source removal`() = watch({ it.runtime.connections.remove("ssh") })
    @Test fun `watchLost tunnel state source`() = watch({ f ->
        (f.runner.states as MutableStateFlow).value = mapOf("g" to VisitorState(TunnelStatus.FAILED))
    })
    @Test fun `watchLost tunnel pending source`() = watch({ it.startPending() })
    // Another source may fire as well; the session-removed watcher alone is pinned by AssistantSshCommandTest.
    @Test fun `watchLost fires after the session is removed`() = watch({ it.runtime.session.markRemoved() })
    // The plan watcher runs Unconfined inside the sync, before the Default-dispatched projection can report the loss.
    @Test fun `plan loss is signalled from the plan before the projection`() { repeat(20) { fixture { f ->
        val lost = AtomicInteger(); val scope = CoroutineScope(Job())
        try {
            f.targets.watchLost(f.target(), scope) { lost.incrementAndGet() }
            f.grants = emptyList(); f.manager.sync()
            assertTrue(lost.get() > 0)
        } finally { scope.cancel() }
    } } }

    @Test fun `targetPresent follows the plan entry and pending state of the exact tunnel`() = fixture { f ->
        val t = f.target().tunnel
        assertTrue(f.manager.targetPresent(t).first())
        assertFalse(f.manager.targetPresent(t.copy(grantId = "g-other")).first())
        assertFalse(f.manager.targetPresent(t.copy(localPort = t.localPort + 1)).first())
        f.grants = emptyList(); f.manager.sync()
        assertFalse(f.manager.targetPresent(t).first())
    }

    @Test fun `targetPresent is false while the tunnel is pending`() = fixture { f ->
        val t = f.target().tunnel
        f.startPending()
        assertFalse(f.manager.targetPresent(t).first())
    }

    @Test fun `watchLost does not fire for unrelated profile changes`() = fixture { f ->
        val lost = AtomicInteger(); val scope = CoroutineScope(Job())
        try {
            f.targets.watchLost(f.target(), scope) { lost.incrementAndGet() }
            f.profiles.hidden.value = setOf(PendingUndo.connectionKey("work", "ssh"))
            f.profiles.state.value = f.profiles.state.value.copy(enrollments = mapOf("work" to 1))
            assertEquals(0, lost.get())
        } finally { scope.cancel() }
    }

    // ---- connector: needs-setup branches and auth ----

    @Test fun `connector uses saved user port stored method and Remember for a remembered password`() = fixture { f ->
        val c = f.targets.connector(f.target())!!
        assertEquals("saved-user", c.user); assertEquals(22, c.port); assertEquals("same", c.device); assertEquals("home", c.profile)
        assertEquals(f.target().tunnel.localPort, c.localPort)
        val auth = c.auth as SshAuth.Password
        assertNull(auth.typed); assertTrue(auth.remember)
        assertSame(f.runtime.hostKeys, c.hostKeys)
    }

    @Test fun `connector for the key method uses key auth`() = fixture { f ->
        f.runtime.sshLogins.remember("same", 22, SshLogin("x", SshAuthMethod.KEY))
        assertSame(SshAuth.Key, f.targets.connector(f.target())!!.auth)
    }

    @Test fun `connector needs setup without a stored login or with a missing password or a refusal`() {
        fixture { f -> f.runtime.sshLogins.wipe(); assertNull(f.targets.connector(f.target())) }
        fixture { f -> f.passwords.forget("home", "same", 22, "saved-user"); assertNull(f.targets.connector(f.target())) }
        fixture { f -> f.env.refusals.record("home", "same", 22, "saved-user", LoginRefusal.PASSWORD_SAVED); assertNull(f.targets.connector(f.target())) }
        fixture { f -> f.env.refusals.record("home", "same", 22, "saved-user", LoginRefusal.PASSWORD_UNREADABLE); assertNull(f.targets.connector(f.target())) }
        fixture { f ->
            f.runtime.sshLogins.remember("same", 22, SshLogin("x", SshAuthMethod.KEY))
            f.env.refusals.record("home", "same", 22, "saved-user", LoginRefusal.KEY); assertNull(f.targets.connector(f.target()))
        }
    }

    @Test fun `connector never reads the password value`() = fixture { f ->
        f.box.destroy() // sealed passwords would throw if opened; has() must not unseal them
        assertNotNull(f.targets.connector(f.target()))
    }

    // ---- directLogin versus the old loginAtOnce / assistant logic (copied here as the oracle) ----

    private fun oldLoginAtOnce(f: Fixture, device: String, port: Int, sshUser: String): SshLogin? {
        val login = f.runtime.sshLogins.stored(device, port)?.copy(user = sshUser) ?: return null
        val saved = f.passwords.has("home", device, port, login.user)
        return login.takeIf { connectsDirectly(it, saved, f.env.refusals.get("home", device, port, login.user.trim()), login.user) }
    }

    @Test fun `directLogin equals the old rule for every stored login password and refusal combination`() {
        var cases = 0
        for (stored in listOf<SshLogin?>(null, SshLogin("a", SshAuthMethod.KEY), SshLogin("a", SshAuthMethod.PASSWORD)))
            for (keptPassword in listOf(false, true))
                for (refusal in listOf<LoginRefusal?>(null) + LoginRefusal.entries)
                    for (refusedUser in listOf("saved-user", "other")) fixture { f ->
                        f.runtime.sshLogins.wipe()
                        stored?.let { f.runtime.sshLogins.remember("same", 22, it) }
                        f.passwords.forget("home", "same", 22, "saved-user")
                        if (keptPassword) f.passwords.remember("home", "same", 22, "saved-user", "pw")
                        refusal?.let { f.env.refusals.record("home", "same", 22, refusedUser, it) }
                        val expected = oldLoginAtOnce(f, "same", 22, "saved-user")
                        assertEquals("$stored $keptPassword $refusal $refusedUser", expected, f.targets.directLogin("home", "same", 22, "saved-user"))
                        // The assistant path: a connector exists exactly when the old rule connects directly.
                        assertEquals(expected != null, f.targets.connector(f.target()) != null)
                        cases++
                    }
        assertEquals(3 * 2 * (1 + LoginRefusal.entries.size) * 2, cases)
    }

    @Test fun `directLogin spans the wrong user port or unknown profile and uses the saved user`() = fixture { f ->
        assertEquals(SshLogin("saved-user", SshAuthMethod.PASSWORD), f.targets.directLogin("home", "same", 22, "saved-user"))
        assertNull(f.targets.directLogin("home", "same", 23, "saved-user"))
        assertNull(f.targets.directLogin("home", "other", 22, "saved-user"))
        assertNull(f.targets.directLogin("missing", "same", 22, "saved-user"))
        assertNull(f.targets.directLogin("home", "same", 22, "other-user")) // no password kept for that user
        // As the old loginAtOnce: no loaded/index check.
        f.profiles.state.value = f.profiles.state.value.copy(loaded = false)
        assertNotNull(f.targets.directLogin("home", "same", 22, "saved-user"))
    }

    // ---- toAuth: every method, password and remember against the old expressions ----

    private fun oldMapping(method: SshAuthMethod, password: String, remember: Boolean): SshAuth = when (method) {
        SshAuthMethod.KEY -> SshAuth.Key
        SshAuthMethod.PASSWORD -> SshAuth.Password(password.ifEmpty { null }, remember)
    }
    private fun oldAssistant(method: SshAuthMethod): SshAuth = if (method == SshAuthMethod.KEY) SshAuth.Key else SshAuth.Password(null, true)

    private fun same(a: SshAuth, b: SshAuth) {
        assertEquals(a.toString(), b.toString())
        if (a is SshAuth.Password) {
            b as SshAuth.Password
            assertEquals(a.typed, b.typed); assertEquals(a.remember, b.remember)
        } else assertSame(a, b)
    }

    @Test fun `toAuth equals the old UI expression for every method password and remember`() {
        for (m in SshAuthMethod.entries) for (p in listOf("", "typed", " ")) for (r in listOf(false, true)) same(oldMapping(m, p, r), m.toAuth(p, r))
    }

    @Test fun `toAuth defaults and the assistant form`() {
        for (m in SshAuthMethod.entries) {
            same(oldMapping(m, "", false), m.toAuth())
            same(oldAssistant(m), m.toAuth(remember = true))
        }
        assertEquals(2, SshAuthMethod.entries.size)
    }
}
