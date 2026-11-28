package ru.adsrv.frpjump.settings

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ssh.PinnedHostKey
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.SshLogin

/** The persisted fingerprint ([GrantFingerprint]): a golden vector computed outside the app, and one assertion per member and per non-member. */
class GrantFingerprintTest {
    private val connection = Connection("c1", "board1", 22, PortKind.SSH, customName = "Service", https = false, user = "root", localPort = null)
    private val enrollment = GrantFingerprint.enrollmentHash("https://ctl.example", "dev-1", "CERT")
    private val login = SshLogin("root", SshAuthMethod.KEY)
    private val hostKey = PinnedHostKey("ssh-ed25519", "SHA256:abc")
    private fun fp(connection: Connection = this.connection, profile: String = "p1", alias: String = "alias", pending: Boolean = false, enrollment: String? = this.enrollment,
        login: SshLogin? = this.login, hostKey: PinnedHostKey? = this.hostKey, mqtt: String? = null) =
        GrantFingerprint.of(connection, profile, alias, pending, enrollment, login, hostKey, mqtt)

    @Test fun `a golden vector computed outside the app`() {
        // sha256("https://ctl.example\ndev-1\nCERT") and the SHA-256 of the compact JSON in the contract's key order, both produced by a separate script (python).
        assertEquals("12aa8213f4f530d0ec09a470b477bb66b60ffa330311833b84c0348b9e20e697", enrollment)
        assertEquals("v1:Sv8lqFDhCJyhGqPoOPhH4OM-PHP43M1DoOTm84TTtSw", fp())
        assertEquals("stable", fp(), fp())
    }

    @Test fun `every field of the connection is in it`() {
        val base = fp()
        val edits = mapOf("id" to connection.copy(id = "c2"), "device" to connection.copy(device = "board2"), "port" to connection.copy(port = 2222), "kind" to connection.copy(kind = PortKind.TCP),
            "customName" to connection.copy(customName = "Other"), "customName cleared" to connection.copy(customName = null), "https" to connection.copy(https = true),
            "user" to connection.copy(user = "admin"), "user cleared" to connection.copy(user = null), "localPort" to connection.copy(localPort = 41000))
        for ((what, edited) in edits) assertNotEquals("an edit of $what must change it", base, fp(connection = edited))
    }

    @Test fun `the profile id key alias activation and every public enrollment field are in it`() {
        val base = fp()
        assertNotEquals("profile id", base, fp(profile = "p2")); assertNotEquals("key alias", base, fp(alias = "other"))
        assertNotEquals("activation", base, fp(pending = true)); assertNotEquals("not enrolled", base, fp(enrollment = null))
        assertNotEquals("control url", base, fp(enrollment = GrantFingerprint.enrollmentHash("https://other.example", "dev-1", "CERT")))
        assertNotEquals("device id", base, fp(enrollment = GrantFingerprint.enrollmentHash("https://ctl.example", "dev-2", "CERT")))
        assertNotEquals("certificate", base, fp(enrollment = GrantFingerprint.enrollmentHash("https://ctl.example", "dev-1", "CERT2")))
    }

    @Test fun `the SSH login the pinned host key and the MQTT user are in it`() {
        val base = fp()
        assertNotEquals("login user", base, fp(login = SshLogin("admin", SshAuthMethod.KEY))); assertNotEquals("login method", base, fp(login = SshLogin("root", SshAuthMethod.PASSWORD)))
        assertNotEquals("no login", base, fp(login = null))
        assertNotEquals("host key type", base, fp(hostKey = PinnedHostKey("ssh-rsa", "SHA256:abc"))); assertNotEquals("host key", base, fp(hostKey = PinnedHostKey("ssh-ed25519", "SHA256:other")))
        assertNotEquals("no host key", base, fp(hostKey = null))
        assertNotEquals("mqtt user", base, fp(mqtt = "broker")); assertNotEquals("another mqtt user", fp(mqtt = "a"), fp(mqtt = "b"))
    }

    @Test fun `the value is versioned and never holds a name address or secret`() {
        assertTrue(fp().startsWith("v1:")); assertEquals(3 + 43, fp().length)
        for (secret in listOf("Service", "board1", "root", "alias", "CERT")) assertFalse(secret, fp().contains(secret))
    }
    @Test fun `Web certificate participates only in Web fingerprints`() {
        val web = connection.copy(kind = PortKind.WEB, port = 443, https = true)
        fun value(c: Connection, cert: String?) = GrantFingerprint.of(c, "p1", "alias", false, enrollment, login, hostKey, null, cert)
        assertNotEquals(value(web, null), value(web, "A")); assertNotEquals(value(web, "A"), value(web, "B"))
        assertEquals(value(connection, null), value(connection, "A"))
        assertEquals(value(connection.copy(kind = PortKind.MQTT), null), value(connection.copy(kind = PortKind.MQTT), "A"))
    }

}
