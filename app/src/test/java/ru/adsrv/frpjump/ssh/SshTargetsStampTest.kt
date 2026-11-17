package ru.adsrv.frpjump.ssh

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.profiles.Profile
import ru.adsrv.frpjump.profiles.ProfileIndex
import ru.adsrv.frpjump.profiles.SyntheticProfileInformation

/** The real [SshTargets.labels] and [SshTargets.fingerprint] over real profile runtimes: what the confirmation card shows and what a session grant is bound to. */
class SshTargetsStampTest {
    private fun targets(f: SyntheticProfileInformation): SshTargets {
        val box = FakeKeystore().box("synthetic-ssh")
        val env = SshEnvironment(SshKeyStore(MemoryStorage(), box), SshPasswords(MemoryStorage(), box), { "synthetic" }, TmuxNames(MemoryStorage()),
            clientFactory = { throw AssertionError("must not construct a client") })
        return SshTargets(f.state, f.hidden, env)
    }
    private val base = Connection("ssh", "board1", 22, PortKind.SSH, customName = "Service", user = "root")

    @Test fun `labels give the profile name and the saved connection and nothing else`() {
        SyntheticProfileInformation().use { f ->
            val t = targets(f); f.runtimes.getValue("work").connections.save(base)
            val (profile, connection) = t.labels("work", "ssh")!!
            assertEquals("Work", profile); assertEquals(base, connection)
            assertEquals("Service", connection.label { "SSH" })
            for ((p, c) in listOf("work" to "missing", "nobody" to "ssh", "home" to "ssh")) assertNull("$p/$c", t.labels(p, c))
        }
    }

    @Test fun `the fingerprint is stable and changes for an edit of any field of the connection and for removal`() {
        SyntheticProfileInformation().use { f ->
            val t = targets(f); val runtime = f.runtimes.getValue("work"); runtime.connections.save(base)
            val first = t.fingerprint("work", "ssh")!!
            assertEquals(first, t.fingerprint("work", "ssh"))
            val edits = mapOf("device" to base.copy(device = "board2"), "user" to base.copy(user = "admin"), "port" to base.copy(port = 2222),
                "customName" to base.copy(customName = "Other"), "kind" to base.copy(kind = PortKind.TCP), "https" to base.copy(https = true),
                "localPort" to base.copy(localPort = 41000))
            for ((what, edited) in edits) {
                runtime.connections.save(edited)
                assertNotEquals("an edit of the $what must change the fingerprint", first, t.fingerprint("work", "ssh"))
                runtime.connections.save(base)
            }
            assertEquals("back to the saved connection: the same fingerprint", first, t.fingerprint("work", "ssh"))
            runtime.connections.remove("ssh")
            assertNull("a removed connection has no fingerprint", t.fingerprint("work", "ssh"))
            assertNull(t.labels("work", "ssh"))
        }
    }

    @Test fun `the profile id key alias activation and enrollment are in it, its name and colour are not, and removal drops it`() {
        SyntheticProfileInformation().use { f ->
            val t = targets(f); f.runtimes.getValue("work").connections.save(base)
            val first = t.fingerprint("work", "ssh")!!
            fun edit(change: (Profile) -> Profile) { val s = f.state.value; f.state.value = s.copy(index = ProfileIndex(s.index.profiles.map { if (it.id == "work") change(it) else it }, s.index.current)) }
            edit { it.copy(name = "Renamed") }; assertEquals("a rename is not a change (approved)", first, t.fingerprint("work", "ssh")); assertEquals("Renamed", t.labels("work", "ssh")!!.first)
            edit { it.copy(color = "pink") }; assertEquals("nor is the colour", first, t.fingerprint("work", "ssh"))
            edit { it.copy(keyAlias = "another") }; assertNotEquals("key alias", first, t.fingerprint("work", "ssh"))
            edit { it.copy(keyAlias = "secret-alias", activationPending = true) }; assertNotEquals("activation", first, t.fingerprint("work", "ssh"))
            edit { it.copy(activationPending = false) }; assertEquals(first, t.fingerprint("work", "ssh"))
            f.state.value = f.state.value.copy(enrollments = f.state.value.enrollments + ("work" to 1)); assertNotEquals("re-enrollment", first, t.fingerprint("work", "ssh"))
            f.state.value = f.state.value.copy(enrollments = emptyMap()); assertEquals(first, t.fingerprint("work", "ssh"))
            f.state.value = f.state.value.copy(index = ProfileIndex(listOf(f.profiles[1]), "home"))
            assertNull(t.fingerprint("work", "ssh")); assertNull(t.labels("work", "ssh"))
        }
    }

    @Test fun `the non-secret SSH login and the pinned host key of the device and port are in it`() {
        SyntheticProfileInformation().use { f ->
            val t = targets(f); val runtime = f.runtimes.getValue("work"); runtime.connections.save(base)
            val none = t.fingerprint("work", "ssh")!!
            runtime.sshLogins.remember("board1", 22, SshLogin("root", SshAuthMethod.KEY)); val key = t.fingerprint("work", "ssh")!!
            assertNotEquals(none, key)
            runtime.sshLogins.remember("board1", 22, SshLogin("root", SshAuthMethod.PASSWORD)); val password = t.fingerprint("work", "ssh")!!
            assertNotEquals("the method", key, password)
            runtime.sshLogins.remember("board1", 22, SshLogin("admin", SshAuthMethod.PASSWORD)); assertNotEquals("the user", password, t.fingerprint("work", "ssh"))
            runtime.sshLogins.remember("board1", 22, SshLogin("root", SshAuthMethod.PASSWORD))
            runtime.sshLogins.remember("board1", 2222, SshLogin("root", SshAuthMethod.KEY)); assertEquals("another port's login is not this one's", password, t.fingerprint("work", "ssh"))
            runtime.hostKeys.pin("board1", 22, PinnedHostKey("ssh-ed25519", "a")); val pinned = t.fingerprint("work", "ssh")!!
            assertNotEquals(password, pinned)
            runtime.hostKeys.pin("board1", 22, PinnedHostKey("ssh-ed25519", "b")); assertNotEquals("a changed key", pinned, t.fingerprint("work", "ssh"))
            runtime.hostKeys.pin("board1", 22, PinnedHostKey("ssh-ed25519", "a")); assertEquals(pinned, t.fingerprint("work", "ssh"))
            runtime.hostKeys.forget("board1", 22); assertEquals("forgotten: as before the pin", password, t.fingerprint("work", "ssh"))
        }
    }

    @Test fun `the same connection id in another profile is another target`() {
        SyntheticProfileInformation().use { f ->
            val t = targets(f)
            f.runtimes.getValue("work").connections.save(base); f.runtimes.getValue("home").connections.save(base.copy(device = "other"))
            assertNotEquals(t.fingerprint("work", "ssh"), t.fingerprint("home", "ssh"))
        }
    }
}
