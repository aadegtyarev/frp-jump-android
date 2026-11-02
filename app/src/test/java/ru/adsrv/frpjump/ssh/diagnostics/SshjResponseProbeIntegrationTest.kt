package ru.adsrv.frpjump.ssh.diagnostics

import java.security.KeyPairGenerator
import java.util.concurrent.TimeUnit
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.junit.Assert.*
import org.junit.Test

/** Loopback-only test peer with an ephemeral in-memory host key, no shell factory or real credentials. */
class SshjResponseProbeIntegrationTest {
    @Test fun `negative reply is observed without consuming neighboring global request promises`() {
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair())
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "test" && password == "test" }
            globalRequestHandlers = emptyList()
        }
        try {
            server.start()
            SSHClient().use { client ->
                // Only this loopback fixture bypasses host-key verification.
                client.addHostKeyVerifier(PromiscuousVerifier())
                client.connect("127.0.0.1", server.port)
                client.authPassword("test", "test")
                client.startSession().use {
                    val before = client.connection.sendGlobalRequest(SshjResponseProbe.REQUEST, true, byteArrayOf())
                    val measured = SshjResponseProbe(client).send()
                    val after = client.connection.sendGlobalRequest(SshjResponseProbe.REQUEST, true, byteArrayOf())
                    assertEquals(ProbeReply.REPLIED, measured.await(3000))
                    assertTrue(measured.complete)
                    assertTrue(runCatching { before.tryRetrieve(3, TimeUnit.SECONDS) }.isFailure)
                    assertTrue(runCatching { after.tryRetrieve(3, TimeUnit.SECONDS) }.isFailure)
                    assertTrue(before.isFulfilled)
                    assertTrue(after.isFulfilled)
                    assertTrue(client.isConnected)
                    assertTrue(client.isAuthenticated)
                }
            }
        } finally { server.stop(true) }
    }
}
