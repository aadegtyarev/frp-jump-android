package ru.adsrv.frpjump.ssh

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.profiles.Profile
import ru.adsrv.frpjump.profiles.ProfileIndex
import ru.adsrv.frpjump.profiles.SyntheticProfileInformation

/** The real [SshTargets.labels] and [SshTargets.stamp] over real profile runtimes: what the confirmation card shows and what a grant is bound to. */
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

    @Test fun `the stamp is stable and changes for an edit of host user port name or kind and for removal`() {
        SyntheticProfileInformation().use { f ->
            val t = targets(f); val runtime = f.runtimes.getValue("work"); runtime.connections.save(base)
            val first = t.stamp("work", "ssh")!!
            assertEquals(first, t.stamp("work", "ssh"))
            val edits = mapOf("host" to base.copy(device = "board2"), "user" to base.copy(user = "admin"), "port" to base.copy(port = 2222),
                "name" to base.copy(customName = "Other"), "kind" to base.copy(kind = PortKind.TCP))
            for ((what, edited) in edits) {
                runtime.connections.save(edited)
                assertNotEquals("an edit of the $what must change the stamp", first, t.stamp("work", "ssh"))
                runtime.connections.save(base)
            }
            runtime.connections.remove("ssh")
            assertNull("a removed connection has no stamp", t.stamp("work", "ssh"))
            assertNull(t.labels("work", "ssh"))
        }
    }

    @Test fun `a profile rename or removal changes or drops the stamp`() {
        SyntheticProfileInformation().use { f ->
            val t = targets(f); f.runtimes.getValue("work").connections.save(base)
            val first = t.stamp("work", "ssh")!!
            f.state.value = f.state.value.copy(index = ProfileIndex(listOf(Profile("work", "Renamed", "green", "secret-alias"), f.profiles[1]), "work"))
            assertNotEquals(first, t.stamp("work", "ssh")); assertEquals("Renamed", t.labels("work", "ssh")!!.first)
            f.state.value = f.state.value.copy(index = ProfileIndex(listOf(f.profiles[1]), "home"))
            assertNull(t.stamp("work", "ssh")); assertNull(t.labels("work", "ssh"))
        }
    }

    @Test fun `the same connection id in another profile is another target`() {
        SyntheticProfileInformation().use { f ->
            val t = targets(f)
            f.runtimes.getValue("work").connections.save(base); f.runtimes.getValue("home").connections.save(base.copy(device = "other"))
            assertNotEquals(t.stamp("work", "ssh"), t.stamp("home", "ssh"))
        }
    }
}
