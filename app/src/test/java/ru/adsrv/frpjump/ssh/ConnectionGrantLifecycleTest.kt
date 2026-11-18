package ru.adsrv.frpjump.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.api.ConsumedGrant
import ru.adsrv.frpjump.assistant.AppGrantWorld
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.appConnectionGrants
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.lock.AppLock
import ru.adsrv.frpjump.lock.PromptHost
import ru.adsrv.frpjump.lock.PromptListener
import ru.adsrv.frpjump.ops.ActionClass
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.profiles.Profile
import ru.adsrv.frpjump.profiles.ProfileIndex
import ru.adsrv.frpjump.profiles.ProfileRuntime
import ru.adsrv.frpjump.settings.*
import ru.adsrv.frpjump.store.CredentialStore
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.undo.PendingUndo

/**
 * The real tunnel manager, runtimes, [SshTargets], Undo queue and app lock behind the persistent "For this connection" permission, built by the same factory
 * the application uses ([appConnectionGrants]): each row of the contract's "When a persistent grant ends", with no conversation anywhere. "Stays" cases
 * prove nothing ends it too early.
 */
class ConnectionGrantLifecycleTest {
    private val target = GrantTarget("home", "ssh")
    private val key = GrantKey(ActionClass.CONNECTION_CHANGE, target)
    private val tool = AssistantToolId.SSH_COMMAND

    private class Host : PromptHost {
        val listeners = mutableListOf<PromptListener>()
        override fun isStateSaved(owner: Any) = false
        override fun show(owner: Any, title: String, subtitle: String, listener: PromptListener) { listeners += listener }
    }

    private inner class Rig(val f: SshTargetsTest.Fixture, cleaner: Boolean = true) : AutoCloseable {
        val storage = MemoryStorage()
        val settings = SettingsStore(storage).apply { update { it.copy(assistantEnabled = true, assistantSshCommand = true, assistantTerminalTabs = true, assistantTerminalInput = true) } }
        val undo = PendingUndo(timer = { _, _ -> {} }, run = { block -> runBlocking { block() }; Unit }, announce = { _, _ -> })
        val targets = SshTargets(f.profiles.state, undo.hidden, f.env, undo::hideCount)
        @Volatile var now = 0L
        val host = Host()
        val lock = AppLock(clock = { now }, host = host)
        private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val grants = if (cleaner) appConnectionGrants(targets, settings, lock, scope, tick = emptyFlow<Unit>()) else ConnectionGrants(settings, AppGrantWorld(targets, settings, lock))
        fun give(): RecordResult = runBlocking { grants.record(key, tool, grants.stamp(target)!!, setOf(tool, AssistantToolId.TERMINAL_INPUT)) }
        val records get() = settings.assistantConfirmations.overrides
        /** What the cleaner alone left: nothing here looks the record up (a lookup would delete a stale one by itself). */
        val held: Boolean get() {
            val until = System.nanoTime() + 400_000_000L
            while (records.isNotEmpty() && System.nanoTime() < until) Thread.sleep(5)
            return records.isNotEmpty()
        }
        fun openApp() {
            settings.update { it.copy(appLock = true, appLockAfter = AppLockAfter.SECONDS_30) }
            lock.onStart(true, 30_000); lock.authenticateFor(Any(), "t", "s") {}; host.listeners.last().succeeded()
        }
        override fun close() { scope.cancel() }
    }

    private fun run(cleaner: Boolean = true, block: suspend (Rig) -> Unit) = runBlocking { SshTargetsTest.Fixture().use { f -> f.ready(); Rig(f, cleaner).use { block(it) } } }
    private suspend fun SshTargetsTest.Fixture.resync(list: List<ConsumedGrant> = grants) { grants = list; manager.sync(); withTimeout(5000) { manager.state.first { s -> s.tunnels.size == list.size } } }
    private val original = listOf(ConsumedGrant("g", "secret", "svc", "ssh", "same", 22))

    @Test fun `it is given while the tunnel is open and covers`() = run { r ->
        assertEquals(RecordResult.RECORDED, r.give()); assertTrue(r.held); assertTrue(r.grants.covers(tool, key)); assertTrue(r.grants.covers(AssistantToolId.TERMINAL_INPUT, key))
    }

    @Test fun `each field of the connection ends it when edited, one test per field`() {
        val base = Connection("ssh", "same", 22, PortKind.SSH, user = "saved-user")
        val edits = mapOf("device" to base.copy(device = "other"), "port" to base.copy(port = 2222), "kind" to base.copy(kind = PortKind.TCP),
            "customName" to base.copy(customName = "N"), "https" to base.copy(https = true), "user" to base.copy(user = "admin"), "localPort" to base.copy(localPort = 41000))
        for ((what, edited) in edits) run { r ->
            assertEquals(RecordResult.RECORDED, r.give()); r.f.runtime.connections.save(edited)
            assertFalse("an edit of $what ends it", r.held); assertFalse(what, r.grants.covers(tool, key))
        }
        run { r -> assertEquals(RecordResult.RECORDED, r.give()); r.f.runtime.connections.remove("ssh"); assertFalse("deleted", r.held) }
        // Saved again unchanged: nothing.
        run { r -> assertEquals(RecordResult.RECORDED, r.give()); r.f.runtime.connections.save(base); assertTrue("the same connection saved again", r.held) }
    }

    @Test fun `the profile's key alias activation enrollment and removal end it but not its name or colour`() {
        fun edit(r: Rig, change: (Profile) -> Profile) {
            val s = r.f.profiles.state.value
            r.f.profiles.state.value = s.copy(index = ProfileIndex(s.index.profiles.map { if (it.id == "home") change(it) else it }, s.index.current))
        }
        run { r -> r.give(); edit(r) { it.copy(keyAlias = "other-alias") }; assertFalse("keyAlias", r.held) }
        run { r -> r.give(); edit(r) { it.copy(activationPending = true) }; assertFalse("activation", r.held) }
        run { r -> r.give(); val s = r.f.profiles.state.value; r.f.profiles.state.value = s.copy(index = ProfileIndex(s.index.profiles.filter { it.id == "work" }, "work")); assertFalse("the profile is gone", r.held) }
        run { r -> r.give(); edit(r) { it.copy(name = "Renamed") }; assertTrue("name", r.held); edit(r) { it.copy(color = "red") }; assertTrue("colour", r.held); assertTrue(r.grants.covers(tool, key)) }
        // Each public part of a new enrollment: the control URL, the device id, the certificate.
        for ((what, change) in mapOf<String, (ru.adsrv.frpjump.store.Credentials) -> ru.adsrv.frpjump.store.Credentials>("control url" to { c -> c.copy(controlUrl = "https://elsewhere") },
            "device id" to { c -> c.copy(deviceId = "id-other") }, "certificate" to { c -> c.copy(certPem = "cert-2") })) run { r ->
            r.give(); r.f.runtime.store.save(change(r.f.runtime.store.load()!!))
            // The profiles state publishes when an enrollment is replaced; the cleaner hears it.
            r.f.profiles.state.value = r.f.profiles.state.value.let { it.copy(enrollments = it.enrollments + ("home" to 1)) }
            assertFalse("a new enrollment ($what) ends it", r.held); assertFalse(what, r.grants.covers(tool, key))
        }
        // A new token or key is sealed, not public: not part of the identity.
        run { r -> r.give(); r.f.runtime.store.save(r.f.runtime.store.load()!!.copy(apiToken = "new-token", keyPem = "new-key")); r.f.profiles.state.value = r.f.profiles.state.value.copy(); assertTrue("sealed fields are not in it", r.held) }
    }

    @Test fun `a changed SSH login method or user and a changed or forgotten host key end it, and a password or the same login do not`() {
        run { r -> r.give(); r.f.runtime.sshLogins.remember("same", 22, SshLogin("port-user", SshAuthMethod.KEY)); assertFalse("login method", r.held) }
        run { r -> r.give(); r.f.runtime.sshLogins.remember("same", 22, SshLogin("other-user", SshAuthMethod.PASSWORD)); assertFalse("login user", r.held) }
        run { r -> r.give(); r.f.runtime.hostKeys.pin("same", 22, PinnedHostKey("ssh-rsa", "changed")); assertFalse("pinned host key", r.held) }
        run { r -> r.give(); r.f.runtime.hostKeys.forget("same", 22); assertFalse("forgotten host key", r.held) }
        run { r -> r.give(); r.f.runtime.sshLogins.remember("same", 22, SshLogin("port-user", SshAuthMethod.PASSWORD)); assertTrue("the same login written again", r.held) }
        run { r -> r.give(); r.f.passwords.remember("home", "same", 22, "saved-user", "another-secret"); assertTrue("a remembered password is a secret, never in it", r.held) }
    }

    @Test fun `a tunnel disconnect and reconnect and a transient change end nothing`() = run { r ->
        assertEquals(RecordResult.RECORDED, r.give())
        r.f.resync(emptyList()); assertNull(r.targets.period("home", "ssh"))
        assertTrue("the tunnel closed", r.held); assertTrue("and the record is not tied to a tunnel", r.grants.covers(tool, key))
        r.f.resync(original); assertTrue(r.held); assertTrue(r.grants.covers(tool, key))
    }

    @Test fun `hidden for Undo it is not used, Undo cancelled it is used again, expired it is removed`() = run { r ->
        assertEquals(RecordResult.RECORDED, r.give())
        r.undo.enqueue(PendingUndo.connectionKey("home", "ssh"), commit = {})
        assertFalse("hidden for Undo: not used", r.grants.covers(tool, key)); assertTrue("but not deleted", r.held)
        r.undo.undoAll(); assertTrue("Undo cancelled: used again", r.grants.covers(tool, key))
        // The delete commits: the connection is gone and the record goes (the app's commit also forgets it first).
        r.undo.enqueue(PendingUndo.connectionKey("home", "ssh"), commit = { r.grants.forget("home", "ssh"); r.f.runtime.connections.remove("ssh") })
        r.undo.commitAll(); assertFalse("expired: removed", r.held); assertFalse(r.grants.covers(tool, key))
    }

    @Test fun `the assistant off and a tool's switch end it for good`() {
        run { r -> r.give(); r.settings.update { it.copy(assistantEnabled = false) }; assertFalse("off", r.held) }
        run { r -> r.give(); r.settings.update { it.copy(assistantEnabled = false) }; r.settings.update { it.copy(assistantEnabled = true) }; assertFalse("off and on", r.held); assertFalse(r.grants.covers(tool, key)) }
        run { r ->
            r.give(); r.settings.setAssistantTool(tool, false); r.settings.setAssistantTool(tool, true)
            assertFalse("switching it on does not bring ssh_command back", r.grants.covers(tool, key)); assertTrue(r.grants.covers(AssistantToolId.TERMINAL_INPUT, key))
        }
    }

    @Test fun `the lock's interval stops every use, the record stays and covers again after the unlock, with the lock off nothing changes`() {
        run(cleaner = false) { r ->
            r.openApp(); assertEquals(RecordResult.RECORDED, r.give()); assertTrue(r.grants.covers(tool, key))
            r.lock.onStop(); r.now = 10_000; assertTrue("inside the interval", r.grants.covers(tool, key))
            r.now = 31_000
            assertNull("no new grant can be given while it would lock", r.grants.stamp(target)); assertFalse("the interval ran out", r.grants.covers(tool, key)); assertEquals("not deleted", 1, r.records.size)
            r.lock.onStart(true, 30_000); r.lock.authenticateFor(Any(), "t", "s") {}; r.host.listeners.last().succeeded()
            assertTrue("after the unlock it covers again", r.grants.covers(tool, key))
        }
        run { r ->
            r.openApp(); r.give(); r.lock.onStop(); r.now = 31_000; r.lock.onStart(true, 30_000)
            assertTrue(r.lock.locked.value); assertTrue("the lock engaged: the cleaner keeps the record", r.held); assertFalse(r.grants.covers(tool, key))
        }
        run(cleaner = false) { r -> r.lock.onStart(false); r.give(); r.lock.onStop(); r.now = 100 * 60_000L; assertTrue(r.grants.covers(tool, key)) }
    }

    // ---- the three-valued fingerprint of the real SshTargets ----

    private fun field(runtime: ProfileRuntime, name: String, value: Any) = ProfileRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.set(runtime, value)
    private class Unreadable : TextStorage { override fun read(): String? = throw java.io.IOException("Synthetic"); override fun write(text: String) = Unit; override fun delete() = Unit }

    @Test fun `profiles not loaded or without a runtime and unreadable stores are Unknown, and a record asks and is kept`() = run { r ->
        assertEquals(RecordResult.RECORDED, r.give())
        val state = r.f.profiles.state.value
        r.f.profiles.state.value = state.copy(loaded = false)
        assertEquals("profiles not loaded yet", PersistentFp.Unknown, r.targets.persistentFingerprint("home", "ssh")); assertFalse(r.grants.covers(tool, key)); assertTrue(r.held)
        // A profile index that failed to load is not "every profile is gone".
        r.f.profiles.state.value = state.copy(loaded = false, index = ProfileIndex(), failure = "unreadable"); assertTrue(r.held); assertFalse(r.grants.covers(tool, key)); assertEquals(1, r.records.size)
        r.f.profiles.state.value = state.copy(runtimes = state.runtimes - "home"); assertEquals("listed but no runtime", PersistentFp.Unknown, r.targets.persistentFingerprint("home", "ssh"))
        r.f.profiles.state.value = state
        assertTrue("loaded again: it covers again", r.grants.covers(tool, key))
        val runtime = r.f.runtime
        val goodStore = runtime.store; field(runtime, "store", CredentialStore(Unreadable(), FakeKeystore().box("home")))
        assertEquals("credential file unreadable", PersistentFp.Unknown, r.targets.persistentFingerprint("home", "ssh")); assertFalse(r.grants.covers(tool, key)); assertEquals(1, r.records.size)
        field(runtime, "store", goodStore)
        val goodLogins = runtime.sshLogins; field(runtime, "sshLogins", SshLoginMemory(MemoryStorage("not json")))
        assertEquals("login store unreadable", PersistentFp.Unknown, r.targets.persistentFingerprint("home", "ssh")); assertEquals(1, r.records.size)
        field(runtime, "sshLogins", goodLogins)
        val goodKeys = runtime.hostKeys; field(runtime, "hostKeys", HostKeyStore(Unreadable()))
        assertEquals("host-key store unreadable", PersistentFp.Unknown, r.targets.persistentFingerprint("home", "ssh")); assertEquals(1, r.records.size)
        field(runtime, "hostKeys", goodKeys)
        assertTrue(r.grants.covers(tool, key)); assertEquals(1, r.records.size)
    }

    @Test fun `a profile or connection absent from a loaded state is Gone`() = run { r ->
        assertTrue(r.targets.persistentFingerprint("home", "ssh") is PersistentFp.Known)
        assertEquals(PersistentFp.Gone, r.targets.persistentFingerprint("home", "missing")); assertEquals(PersistentFp.Gone, r.targets.persistentFingerprint("nobody", "ssh"))
    }

    @Test fun `the MQTT user is in the fingerprint and an unreadable MQTT store is Unknown`() = run { _ ->
        SshTargetsTest.Fixture().use { f ->
            f.ready(); f.runtime.connections.save(Connection("mq", "same", 1883, PortKind.MQTT))
            var user: String? = "broker"; var fail = false
            val t = SshTargets(f.profiles.state, f.profiles.hidden, f.env, mqttUser = { _, _ -> if (fail) throw java.io.IOException("Synthetic"); user })
            val first = (t.persistentFingerprint("home", "mq") as PersistentFp.Known).fp
            user = "other"; assertNotEquals("the MQTT user changed", first, (t.persistentFingerprint("home", "mq") as PersistentFp.Known).fp)
            fail = true; assertEquals(PersistentFp.Unknown, t.persistentFingerprint("home", "mq"))
            assertTrue("an SSH connection never reads the MQTT store", t.persistentFingerprint("home", "ssh") is PersistentFp.Known)
        }
    }

    @Test fun `the persisted fingerprint and the session one move together for every edit they share`() = run { r ->
        val base = Connection("ssh", "same", 22, PortKind.SSH, user = "saved-user")
        val sessionFirst = r.targets.fingerprint("home", "ssh"); val persistentFirst = r.targets.persistentFingerprint("home", "ssh")
        val edits = listOf(base.copy(device = "o"), base.copy(port = 2222), base.copy(kind = PortKind.TCP), base.copy(customName = "N"), base.copy(https = true), base.copy(user = "a"), base.copy(localPort = 41000))
        for (edited in edits) {
            r.f.runtime.connections.save(edited)
            assertNotEquals(sessionFirst, r.targets.fingerprint("home", "ssh")); assertNotEquals(persistentFirst, r.targets.persistentFingerprint("home", "ssh"))
        }
        r.f.runtime.connections.save(base)
        val sessionBack = r.targets.fingerprint("home", "ssh"); val persistentBack = r.targets.persistentFingerprint("home", "ssh")
        for (change in listOf<() -> Unit>({ r.f.runtime.sshLogins.remember("same", 22, SshLogin("x", SshAuthMethod.KEY)) }, { r.f.runtime.hostKeys.pin("same", 22, PinnedHostKey("t", "f")) })) {
            change(); assertNotEquals(sessionBack, r.targets.fingerprint("home", "ssh")); assertNotEquals(persistentBack, r.targets.persistentFingerprint("home", "ssh"))
            r.f.runtime.sshLogins.remember("same", 22, SshLogin("port-user", SshAuthMethod.PASSWORD)); r.f.runtime.hostKeys.pin("same", 22, PinnedHostKey("ssh-rsa", "pin"))
            assertEquals(sessionBack, r.targets.fingerprint("home", "ssh")); assertEquals(persistentBack, r.targets.persistentFingerprint("home", "ssh"))
        }
    }

    @Test fun `the stores say whether they could be read`() {
        assertTrue(SshLoginMemory(MemoryStorage()).readable); assertTrue(HostKeyStore(MemoryStorage()).readable)
        val logins = SshLoginMemory(MemoryStorage("not json")); assertFalse(logins.readable); logins.remember("d", 22, SshLogin()); assertTrue("a successful write is the new truth", logins.readable)
        val keys = HostKeyStore(Unreadable()); assertFalse(keys.readable); keys.wipe(); assertTrue(keys.readable)
        assertEquals(ru.adsrv.frpjump.store.EnrollmentIdentity.Unreadable, CredentialStore(Unreadable(), FakeKeystore().box("a")).identity())
        assertEquals(ru.adsrv.frpjump.store.EnrollmentIdentity.None, CredentialStore(MemoryStorage(), FakeKeystore().box("a")).identity())
        assertEquals("a malformed file is unreadable here, and nothing is wiped", ru.adsrv.frpjump.store.EnrollmentIdentity.Unreadable, MemoryStorage("{}").let { CredentialStore(it, FakeKeystore().box("a")).identity().also { _ -> assertEquals("{}", it.text) } })
    }
}
