package ru.adsrv.frpjump.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.ChannelFactory
import org.apache.sshd.common.channel.RequestHandler
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.Session
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.concurrent.CopyOnWriteArrayList

/** The `env` requests the app's login sends, against a loopback Apache sshd with an in-memory host key and no shell. */
class TerminalIdentityTest {
    private lateinit var server: SshServer

    /** The `env` requests the server saw, in order. */
    private val seen = CopyOnWriteArrayList<Pair<String, String>>()

    /** Names the server refuses (a stock sshd without a matching `AcceptEnv`); all of them when it holds `*`. */
    @Volatile
    private var refuse: Set<String> = emptySet()

    @Before
    fun start() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair())
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "test" && password == "test" }
            channelFactories = listOf(object : ChannelFactory {
                override fun getName() = "session"
                override fun createChannel(session: Session): Channel = object : ChannelSession() {
                    override fun handleEnvParsed(name: String, value: String): RequestHandler.Result {
                        seen += name to value
                        return if ("*" in refuse || name in refuse) RequestHandler.Result.ReplyFailure else RequestHandler.Result.ReplySuccess
                    }
                }
            })
        }.also { it.start() }
    }

    @After
    fun stop() {
        server.stop(true)
    }

    private fun <T> login(block: (SSHClient) -> T): T = SSHClient().use { client ->
        // Only this loopback fixture bypasses host-key verification.
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.connect("127.0.0.1", server.port)
        client.authPassword("test", "test")
        block(client)
    }

    @Test
    fun `the terminal introduces itself with the two constants`() {
        login { client -> client.startSession().use { introduceTerminal(it, "0.3.98") } }
        assertEquals(listOf("LC_TERMINAL" to "frp-jump", "LC_TERMINAL_VERSION" to "0.3.98"), seen.toList())
    }

    @Test
    fun `a server that refuses the variables is ignored and the login goes on`() {
        refuse = setOf("*")
        login { client ->
            client.startSession().use { introduceTerminal(it, "0.3.98") }
            // One refusal ends the sending: no second round trip.
            assertEquals(listOf("LC_TERMINAL" to "frp-jump"), seen.toList())
            assertTrue(client.isConnected && client.isAuthenticated)
            // The same connection still opens channels (the tab's shell would follow).
            client.startSession().use { assertTrue(it.isOpen) }
        }
    }

    @Test
    fun `a server that takes the first and refuses the second is ignored too`() {
        refuse = setOf("LC_TERMINAL_VERSION")
        login { client ->
            client.startSession().use { introduceTerminal(it, "0.3.98") }
            assertEquals(2, seen.size)
            assertTrue(client.isConnected && client.isAuthenticated)
            client.startSession().use { assertTrue(it.isOpen) }
        }
    }

    @Test
    fun `nothing but the name and the version is ever sent`() {
        val sent = terminalIdentity("1.2.3")
        assertEquals(listOf("LC_TERMINAL", "LC_TERMINAL_VERSION"), sent.map { it.first })
        assertEquals(listOf("frp-jump", "1.2.3"), sent.map { it.second })
        assertFalse(sent.any { it.first.startsWith("LC_") && it.first !in setOf("LC_TERMINAL", "LC_TERMINAL_VERSION") })
    }
}
