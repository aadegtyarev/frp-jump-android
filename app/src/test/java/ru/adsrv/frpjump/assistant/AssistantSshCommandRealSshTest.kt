package ru.adsrv.frpjump.assistant

import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.ssh.*

/**
 * Real SSHJ client against a loopback Apache SSHD (ephemeral in-memory key, password "test", no real credentials):
 * - `disconnect()` unblocks blocked channel reads (Stop and target loss; the cancelled worker's reader catch and the closer both
 *   disconnect, so this alone does not prove the closer);
 * - with stdout/stderr closed by the server but no exit status ever sent, the worker blocks in `join()` and only the closer's
 *   `disconnect()` can release it on Stop and on target loss.
 */
class AssistantSshCommandRealSshTest {
    /** Accepts exec requests and then does nothing: no output, no exit, until the transport goes away. */
    private class NeverExits(val started: CountDownLatch, val destroyed: CountDownLatch, val closeStreams: Boolean) : Command {
        private var out: OutputStream? = null
        private var err: OutputStream? = null
        override fun setInputStream(`in`: InputStream) = Unit
        override fun setOutputStream(out: OutputStream) { this.out = out }
        override fun setErrorStream(err: OutputStream) { this.err = err }
        override fun setExitCallback(callback: ExitCallback) = Unit
        override fun start(channel: ChannelSession, env: Environment) {
            // The exit callback is never invoked: no exit-status, no channel close. Optionally the output streams end (EOF).
            if (closeStreams) {
                out?.close(); err?.close()
                // EOF on both streams (protected in AbstractChannel): the client's readers end, the channel stays open.
                org.apache.sshd.common.channel.AbstractChannel::class.java.getDeclaredMethod("sendEof").apply { isAccessible = true }.invoke(channel)
            }
            started.countDown()
        }
        override fun destroy(channel: ChannelSession) { destroyed.countDown() }
    }

    private class Peer(closeStreams: Boolean) : AutoCloseable {
        val started = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val server: SshServer = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = KeyPairProvider.wrap(keys)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "test" && password == "test" }
            commandFactory = CommandFactory { _, _ -> NeverExits(started, destroyed, closeStreams) }
        }.also { it.start() }
        val pin = PinnedHostKey(KeyType.fromKey(keys.public).toString(), sshFingerprint(Buffer.PlainBuffer().putPublicKey(keys.public).compactData))
        override fun close() { server.stop(true) }
    }

    private fun connector(peer: Peer): SshConnector {
        val box = FakeKeystore().box("synthetic-ssh")
        val env = SshEnvironment(SshKeyStore(MemoryStorage(), box), SshPasswords(MemoryStorage(), box), { "synthetic" }, TmuxNames(MemoryStorage()))
        val hostKeys = HostKeyStore(MemoryStorage()).apply { pin("device", 22, peer.pin) }
        return SshConnector("profile", hostKeys, "device", 22, peer.server.port, "test", SshAuth.Password("test", false), env)
    }

    private fun scenario(cause: String, closeStreams: Boolean) = runBlocking {
        Peer(closeStreams).use { peer ->
            val operation = SshCommandOperation()
            val connector = connector(peer)
            val result = CompletableDeferred<Result<ToolOutput>>()
            val job = launch(Dispatchers.Default) {
                result.complete(runCatching { operation.run { operation.execute(connector, "never-exits", { true }) } })
            }
            try {
                assertTrue("the command reached the real server", peer.started.await(10, TimeUnit.SECONDS))
                if (closeStreams) {
                    // Extra detection: with the streams at EOF and no exit status the worker sits in join(); nothing releases it by itself.
                    assertNull(withTimeoutOrNull(300) { job.join() })
                }
                val begin = System.nanoTime()
                if (cause == "stop") job.cancel() else operation.loseTarget()
                // The command never exits: the real client's blocked calls end only because disconnect() is called.
                withTimeout(5000) { job.join() }
                val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
                println("real SSHD $cause: reads/join unblocked and run returned after $millis ms")
                assertTrue("$cause: returned within 3 s of the cancel (${millis} ms)", millis < 3000)
                assertTrue("$cause: the real server saw the channel go away", peer.destroyed.await(5, TimeUnit.SECONDS))
                if (cause == "stop") assertTrue(job.isCancelled)
                else {
                    val output = result.await().getOrThrow()
                    assertEquals(ToolOutcome.FAILED, output.outcome)
                    assertTrue(output.text, output.text.contains("remote outcome unknown"))
                }
            } finally { job.cancel() }
        }
    }

    @Test fun `disconnect unblocks real blocked channel reads after Stop`() = scenario("stop", closeStreams = false)
    @Test fun `disconnect unblocks real blocked channel reads after target loss`() = scenario("target-loss", closeStreams = false)
    @Test fun `closer disconnect unblocks a real join with closed streams and no exit status after Stop`() = scenario("stop", closeStreams = true)
    @Test fun `closer disconnect unblocks a real join with closed streams and no exit status after target loss`() = scenario("target-loss", closeStreams = true)
}
