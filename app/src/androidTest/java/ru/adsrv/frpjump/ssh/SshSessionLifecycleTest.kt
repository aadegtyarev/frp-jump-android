package ru.adsrv.frpjump.ssh

import com.termux.terminal.TerminalSessionClient
import java.lang.reflect.Proxy
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ThreadPoolExecutor
import kotlinx.coroutines.delay
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.SSHClient
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage

/** Actual SshSession/Termux, in-memory stores, no socket connection or real credentials. */
@RunWith(AndroidJUnit4::class)
class SshSessionLifecycleTest {
    private class Memory : TextStorage {
        @Volatile var value: String? = null
        @Volatile var block = false
        @Volatile var fail = false
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun read() = value
        override fun write(text: String) {
            if (fail) throw IOException("disk full")
            if (block) {
                reached.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Storage gate not released" }
            }
            value = text
        }
        override fun delete() { value = null }
    }

    private val client = Proxy.newProxyInstance(TerminalSessionClient::class.java.classLoader,
        arrayOf(TerminalSessionClient::class.java)) { _, method, _ ->
        when (method.name) { "hashCode" -> 0; "equals" -> false; "toString" -> "LifecycleTestClient"; else -> null }
    } as TerminalSessionClient

    private val unusedBox = object : SecretBox {
        override fun seal(plain: String): String = error("No credentials in lifecycle tests")
        override fun open(sealed: String): String = error("No credentials in lifecycle tests")
        override fun destroy() = Unit
    }
    private val clock = object : ReconnectClock {
        override fun now() = 0L
        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit = {}
    }

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }

    private fun session(root: kotlinx.coroutines.Job, storage: Memory = Memory(), factory: (() -> SSHClient)? = null,
                        auth: SshAuth = SshAuth.Key, noTmux: Boolean = false): SshSession = main {
        SshSession(1, "synthetic-profile", HostKeyStore(storage), "synthetic-device", 22, 9, "synthetic-user",
            auth, SshEnvironment(SshKeyStore(Memory(), unusedBox), SshPasswords(Memory(), unusedBox),
                { "synthetic" }, TmuxNames(Memory()), reconnectClock = clock, clientFactory = factory),
            CoroutineScope(root + Dispatchers.Main), client, noTmux = noTmux)
    }

    private class ShellClient : SSHClient() {
        val disconnects = AtomicInteger()
        val reading = CountDownLatch(1)
        val eof = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        @Volatile var exitCode: Int? = 0
        @Volatile var inspectConnected: (() -> Unit)? = null
        @Volatile private var connected = false
        private val channel by lazy {
            object : SessionChannel(connection) {
                override fun allocatePTY(term: String, cols: Int, rows: Int, width: Int, height: Int, modes: Map<PTYMode, Int>) = Unit
                override fun startShell() = this
                override fun getInputStream() = object : InputStream() {
                    override fun read(): Int {
                        reading.countDown()
                        check(eof.await(10, TimeUnit.SECONDS))
                        return -1
                    }
                }
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun getExitStatus(): Int? = exitCode
            }
        }
        override fun connect(hostname: String, port: Int) { connected = true }
        override fun authPassword(username: String, password: String) = Unit
        override fun isConnected(): Boolean { inspectConnected?.invoke(); return connected }
        override fun isAuthenticated() = connected
        override fun startSession() = channel
        override fun disconnect() {
            disconnects.incrementAndGet()
            connected = false
            eof.countDown()
            disposed.countDown()
        }
    }

    private fun field(session: SshSession, name: String): Any? =
        SshSession::class.java.getDeclaredField(name).apply { isAccessible = true }.get(session)

    private suspend fun awaitOpen(session: SshSession, client: ShellClient) {
        try {
            withTimeout(5_000) {
                while (session.state.value != SshState.Open || client.reading.count != 0L) delay(10)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Open/pump not reached: state=${session.state.value}, reading=${client.reading.count}", e)
        }
    }

    @Test fun acceptedOpenKeepsOneOwnerAndPasteTargetUntilClose() = runBlocking {
        val root = SupervisorJob()
        val client = ShellClient()
        val factories = AtomicInteger()
        val session = session(root, factory = { factories.incrementAndGet(); client },
            auth = SshAuth.Password("synthetic-only", false), noTmux = true)
        try {
            main { session.connect() }
            awaitOpen(session, client)
            val owner = field(session, "currentAttempt")
            val destination = main { session.capturePasteTarget()!! }
            main { session.connect(); session.connect(retrying = true) }
            assertSame(owner, field(session, "currentAttempt"))
            assertEquals(1, factories.get())
            assertTrue(main { session.paste("synthetic text", destination, enter = false) })
            main { session.close("closed") }
            assertEquals(SshState.Ended("closed"), session.state.value)
            assertFalse(main { session.paste("stale", destination, enter = true) })
            assertFalse(session.terminal.session.isRunning)
            assertTrue(client.disposed.await(5, TimeUnit.SECONDS))
        } finally { client.disconnect(); root.cancel(); main { session.close("closed") } }
    }

    @Test fun closeDisposesAcceptedClientEvenWhenScopeWasAlreadyCancelled() = runBlocking {
        val root = SupervisorJob()
        val client = ShellClient()
        val session = session(root, factory = { client }, auth = SshAuth.Password("synthetic-only", false), noTmux = true)
        try {
            main { session.connect() }
            awaitOpen(session, client)
            root.cancel()
            withTimeout(5_000) { root.join() }
            main { session.close("closed") }
            assertEquals(SshState.Ended("closed"), session.state.value)
            assertTrue("Owned transport cleanup must not be skipped with a canceled scope", client.disposed.await(2, TimeUnit.SECONDS))
        } finally { client.disconnect(); root.cancel(); main { session.close("closed") } }
    }

    @Test fun closeNeverWaitsForReconnectMonitorWhileHoldingLifecycle() = runBlocking {
        val root = SupervisorJob()
        val client = ShellClient()
        val session = session(root, factory = { client }, auth = SshAuth.Password("synthetic-only", false), noTmux = true)
        val enteredClose = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val timerEntered = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val reconnect = field(session, "reconnect")!!
        val retry = SshSession::class.java.getDeclaredMethod("retry", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val closer = Thread({ runCatching { session.close("closed") }.onFailure { failure.set(it) } }, "ssh-close-order-test").apply { isDaemon = true }
        val timer = Thread({
            runCatching {
                // This is the exact monitor/callback order used by SshReconnect.tryNow.
                synchronized(reconnect) {
                    timerEntered.countDown()
                    retry.invoke(session, 1)
                }
            }.onFailure { failure.set(it) }
        }, "ssh-timer-order-test").apply { isDaemon = true }
        try {
            main { session.connect() }
            awaitOpen(session, client)
            client.inspectConnected = {
                if (Thread.currentThread() === closer) {
                    enteredClose.countDown()
                    check(releaseClose.await(5, TimeUnit.SECONDS))
                }
            }
            closer.start()
            assertTrue(enteredClose.await(5, TimeUnit.SECONDS))
            timer.start()
            assertTrue(timerEntered.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { while (timer.state != Thread.State.BLOCKED) delay(10) }
            releaseClose.countDown()
            closer.join(5_000)
            timer.join(5_000)
            assertFalse("Close/reconnect lock inversion", closer.isAlive || timer.isAlive)
            assertEquals(null, failure.get())
            assertEquals(SshState.Ended("closed"), session.state.value)
            assertTrue(client.disposed.await(5, TimeUnit.SECONDS))
        } finally {
            releaseClose.countDown()
            client.disconnect()
            root.cancel()
            // Do not block the Android main thread if a regression deadlocked a test worker.
            if (!closer.isAlive && !timer.isAlive) main { session.close("closed") }
        }
    }

    @Test fun normalShellExitEndsWithoutStartingReconnect() = runBlocking {
        val root = SupervisorJob()
        val client = ShellClient()
        val factories = AtomicInteger()
        val session = session(root, factory = { factories.incrementAndGet(); client },
            auth = SshAuth.Password("synthetic-only", false), noTmux = true)
        try {
            main { session.connect() }
            awaitOpen(session, client)
            client.eof.countDown()
            withTimeout(5_000) { while (session.state.value !is SshState.Ended) delay(10) }
            assertEquals(1, factories.get())
            assertTrue(client.disposed.await(5, TimeUnit.SECONDS))
            assertFalse(session.terminal.session.isRunning)
        } finally { client.disconnect(); root.cancel(); main { session.close("closed") } }
    }

    @Test fun reconnectKeepsHistoryAndRejectsOldEofFallbackAndPasteTarget() = runBlocking {
        val root = SupervisorJob()
        val first = ShellClient().apply { exitCode = null }
        val second = ShellClient()
        val factories = AtomicInteger()
        val session = session(root, factory = { if (factories.getAndIncrement() == 0) first else second },
            auth = SshAuth.Password("synthetic-only", false), noTmux = true)
        try {
            main { session.connect() }
            awaitOpen(session, first)
            val owner = field(session, "currentAttempt")!!
            val channel = field(session, "shell")!!
            val buffer = session.terminal.session.emulator
            val oldTarget = main { session.capturePasteTarget()!! }
            // Plain-shell reset deliberately redraws the active viewport; seed real scrollback above it.
            session.terminal.note("synthetic-history\r\n" + (1..32).joinToString("") { "synthetic line $it\r\n" })
            withTimeout(5_000) {
                while (!main { buffer.screen.transcriptText.contains("synthetic-history") }) delay(10)
            }
            first.eof.countDown()
            awaitOpen(session, second)
            assertSame(buffer, session.terminal.session.emulator)
            withTimeout(5_000) {
                while (!main { buffer.screen.transcriptText.contains("synthetic-history") }) delay(10)
            }
            SshSession::class.java.declaredMethods.single { it.name == "onShellEnded" }
                .apply { isAccessible = true }.invoke(session, owner, first, channel, null)
            SshSession::class.java.declaredMethods.single { it.name == "fallBackToPlainShell" }
                .apply { isAccessible = true }.invoke(session, owner, 1)
            assertEquals(SshState.Open, session.state.value)
            assertEquals(0, second.disconnects.get())
            assertEquals(2, factories.get())
            assertFalse(main { session.paste("stale", oldTarget, enter = true) })
            assertTrue(main { session.capturePasteTarget() != null })
            main { session.close("closed") }
            assertTrue(second.disposed.await(5, TimeUnit.SECONDS))
        } finally { first.disconnect(); second.disconnect(); root.cancel(); main { session.close("closed") } }
    }

    private fun simulateEarlyTmuxFailure(session: SshSession, client: ShellClient) = main {
        session.tmux.disabled = false
        session.tmux.inUse = true
        SshSession::class.java.getDeclaredField("channelStartedAt").apply { isAccessible = true }
            .setLong(session, System.nanoTime())
        client.exitCode = 1
        client.eof.countDown()
    }

    @Test fun earlyTmuxFailureFallsBackToOnePlainShell() = runBlocking {
        val root = SupervisorJob()
        val first = ShellClient()
        val second = ShellClient()
        val factories = AtomicInteger()
        val session = session(root, factory = { if (factories.getAndIncrement() == 0) first else second },
            auth = SshAuth.Password("synthetic-only", false), noTmux = true)
        try {
            main { session.connect() }
            awaitOpen(session, first)
            simulateEarlyTmuxFailure(session, first)
            awaitOpen(session, second)
            assertTrue(session.tmux.disabled)
            assertFalse(session.tmux.inUse)
            assertEquals(2, factories.get())
            main { session.close("closed") }
            assertTrue(second.disposed.await(5, TimeUnit.SECONDS))
        } finally { first.disconnect(); second.disconnect(); root.cancel(); main { session.close("closed") } }
    }

    @Test fun delayedFallbackCannotConstructNewClientAfterClose() = runBlocking {
        val root = SupervisorJob()
        val first = ShellClient()
        val factories = AtomicInteger()
        val session = session(root, factory = { factories.incrementAndGet(); first },
            auth = SshAuth.Password("synthetic-only", false), noTmux = true)
        val writer = session.terminal.javaClass.getDeclaredField("localWriter").apply { isAccessible = true }
            .get(session.terminal) as ThreadPoolExecutor
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            main { session.connect() }
            awaitOpen(session, first)
            // Drain the preceding local note, then stop only the fallback reset's writer.
            writer.execute { reached.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            simulateEarlyTmuxFailure(session, first)
            withTimeout(5_000) { while (writer.queue.isEmpty()) delay(10) }
            main { session.close("closed") }
            root.cancel()
            release.countDown()
            withTimeout(5_000) { root.join() }
            assertEquals(SshState.Ended("closed"), session.state.value)
            assertEquals(1, factories.get())
        } finally { release.countDown(); first.disconnect(); root.cancel(); main { session.close("closed") } }
    }

    @Test fun closeBetweenLastCheckAndOpenCommitMustRemainEnded() = runBlocking {
        val root = SupervisorJob()
        val client = ShellClient()
        val session = session(root, factory = { client }, auth = SshAuth.Password("synthetic-only", false), noTmux = true)
        val field = session.terminal.javaClass.getDeclaredField("localWriter").apply { isAccessible = true }
        val writer = field.get(session.terminal) as ThreadPoolExecutor
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            // A reconnect to a plain shell queues reset after the final existing closedByUser check.
            SshSession::class.java.getDeclaredField("dropped").apply { isAccessible = true }.setBoolean(session, true)
            writer.execute { reached.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            main { session.connect() }
            withTimeout(5_000) { while (writer.queue.size < 2) delay(10) }
            main { session.close("closed") }
            root.cancel()
            release.countDown()
            withTimeout(5_000) { root.join() }
            assertEquals(SshState.Ended("closed"), session.state.value)
            assertTrue("Rejected ready client is disposed", client.disconnects.get() > 0)
        } finally {
            release.countDown()
            root.cancel()
            main { session.close("closed") }
        }
    }

    /** Only construction is real SSHJ; connect never opens a socket. */
    private class DelayedClient(val failure: IOException) : SSHClient() {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connects = AtomicInteger()
        val disconnects = AtomicInteger()
        override fun connect(hostname: String, port: Int) {
            connects.incrementAndGet()
            reached.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            throw failure
        }
        override fun disconnect() { disconnects.incrementAndGet() }
    }

    @Test fun activeTrustPinsPresentedKeyAndConnects() = activeHostKeyDecision(forget = false)
    @Test fun activeForgetAsksForNewKeyBeforeTrustAndConnect() = activeHostKeyDecision(forget = true)

    private fun activeHostKeyDecision(forget: Boolean) = runBlocking {
        val root = SupervisorJob()
        val old = PinnedHostKey("synthetic-type", "synthetic-old")
        val presented = PinnedHostKey("synthetic-type", "synthetic-presented")
        val first = DelayedClient(HostKeyRejected(if (forget) HostKeyCheck.Changed(old) else HostKeyCheck.Unknown, presented))
            .also { it.release.countDown() }
        val unknown = DelayedClient(HostKeyRejected(HostKeyCheck.Unknown, presented)).also { it.release.countDown() }
        val open = ShellClient()
        val clients = if (forget) listOf(first, unknown, open) else listOf(first, open)
        val factories = AtomicInteger()
        val session = session(root, factory = { clients[factories.getAndIncrement().coerceAtMost(clients.lastIndex)] },
            auth = SshAuth.Password("synthetic-only", false), noTmux = true)
        try {
            if (forget) session.hostKeys.pin("synthetic-device", 22, old)
            main { session.connect() }
            withTimeout(5_000) {
                while (if (forget) session.state.value !is SshState.HostKeyChanged else session.state.value !is SshState.HostKeyUnknown) delay(10)
            }
            if (forget) {
                main { session.forgetOldHostKey() }
                withTimeout(5_000) { while (session.state.value !is SshState.HostKeyUnknown) delay(10) }
                assertEquals(null, session.hostKeys.pinned("synthetic-device", 22))
            }
            main { session.trustHostKey() }
            awaitOpen(session, open)
            assertEquals(presented, session.hostKeys.pinned("synthetic-device", 22))
            assertEquals(clients.size, factories.get())
            main { session.close("closed") }
            assertTrue(open.disposed.await(5, TimeUnit.SECONDS))
        } finally {
            first.disconnect(); unknown.disconnect(); open.disconnect()
            root.cancel(); main { session.close("closed") }
        }
    }

    @Test fun lateUnknownHostKeyCannotReplaceEnded() = lateHostKeyResult(changed = false)
    @Test fun lateChangedHostKeyCannotReplaceEnded() = lateHostKeyResult(changed = true)

    private fun lateHostKeyResult(changed: Boolean) = runBlocking {
        val root = SupervisorJob()
        val key = PinnedHostKey("synthetic-key-type", "synthetic-fingerprint")
        val client = DelayedClient(HostKeyRejected(if (changed) HostKeyCheck.Changed(key) else HostKeyCheck.Unknown, key))
        val session = session(root, factory = { client })
        try {
            main { session.connect() }
            assertTrue(client.reached.await(5, TimeUnit.SECONDS))
            main { session.close("closed") }
            root.cancel()
            client.release.countDown()
            withTimeout(5_000) { root.join() }
            assertEquals(SshState.Ended("closed"), session.state.value)
            assertTrue(client.disconnects.get() > 0)
        } finally {
            client.release.countDown()
            root.cancel()
            main { session.close("closed") }
        }
    }

    @Test fun clientConstructedAfterCloseMustBeDisposedWithoutConnecting() = runBlocking {
        val root = SupervisorJob()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = DelayedClient(IOException("synthetic transport failure")).also { it.release.countDown() }
        val session = session(root, factory = {
            reached.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            client
        })
        try {
            main { session.connect() }
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            main { session.close("closed") }
            root.cancel()
            release.countDown()
            withTimeout(5_000) { root.join() }
            assertEquals("No connection after ownership ended", 0, client.connects.get())
            assertTrue("Unaccepted client must be disposed", client.disconnects.get() > 0)
            assertEquals(SshState.Ended("closed"), session.state.value)
        } finally {
            release.countDown()
            root.cancel()
            main { session.close("closed") }
        }
    }

    @Test fun closedSessionCannotStartConnectingAgain() = closedConnect(retrying = false)
    @Test fun closedSessionCannotStartReconnectLoop() = closedConnect(retrying = true)

    private fun closedConnect(retrying: Boolean) {
        val root = SupervisorJob()
        val session = session(root)
        try {
            // Cancel launches first: exercise the synchronous public transition without opening sockets.
            root.cancel()
            main { session.close("closed"); session.connect(retrying) }
            assertEquals(SshState.Ended("closed"), session.state.value)
        } finally { root.cancel(); main { session.close("closed") } }
    }

    @Test fun lateTrustWriteCannotRestartClosedSession() = lateHostKeyWrite(forget = false)
    @Test fun lateForgetWriteCannotRestartClosedSession() = lateHostKeyWrite(forget = true)

    private fun lateHostKeyWrite(forget: Boolean) = runBlocking {
        val root = SupervisorJob()
        val storage = Memory()
        val session = session(root, storage)
        val presented = PinnedHostKey("synthetic-key-type", "synthetic-fingerprint")
        try {
            if (forget) session.hostKeys.pin("synthetic-device", 22, presented)
            storage.block = true
            @Suppress("UNCHECKED_CAST")
            val state = SshSession::class.java.getDeclaredField("_state").apply { isAccessible = true }
                .get(session) as MutableStateFlow<SshState>
            main {
                state.value = if (forget) SshState.HostKeyChanged(presented, presented.copy(fingerprint = "changed-synthetic"))
                    else SshState.HostKeyUnknown(presented)
                if (forget) session.forgetOldHostKey() else session.trustHostKey()
            }
            assertTrue("Actual host-key storage write entered", storage.reached.await(5, TimeUnit.SECONDS))
            main { session.close("closed") }
            // The blocking storage call finishes despite cancellation; a late connect must be rejected.
            root.cancel()
            storage.release.countDown()
            withTimeout(5_000) { root.join() }
            assertEquals(SshState.Ended("closed"), session.state.value)
        } finally {
            storage.release.countDown()
            root.cancel()
            main { session.close("closed") }
        }
    }

    @Test fun failedTrustSaveEndsWithTheErrorAndTrustsNothing() = failedHostKeySave(forget = false)
    @Test fun failedForgetSaveEndsWithTheErrorAndKeepsTheOldPin() = failedHostKeySave(forget = true)

    private fun failedHostKeySave(forget: Boolean) = runBlocking {
        val root = SupervisorJob()
        val storage = Memory()
        val session = session(root, storage)
        val old = PinnedHostKey("synthetic-key-type", "synthetic-old")
        val presented = PinnedHostKey("synthetic-key-type", "synthetic-presented")
        try {
            if (forget) session.hostKeys.pin("synthetic-device", 22, old)
            storage.fail = true
            @Suppress("UNCHECKED_CAST")
            val state = SshSession::class.java.getDeclaredField("_state").apply { isAccessible = true }
                .get(session) as MutableStateFlow<SshState>
            main {
                state.value = if (forget) SshState.HostKeyChanged(old, presented) else SshState.HostKeyUnknown(presented)
                if (forget) session.forgetOldHostKey() else session.trustHostKey()
            }
            withTimeout(5_000) { while (session.state.value !is SshState.Ended) delay(10) }
            val ended = session.state.value as SshState.Ended
            assertTrue(ended.message, ended.message.contains("write error") && !ended.message.contains("disk full"))
            // Nothing trusted; on forget the old pin stays.
            assertEquals(if (forget) old else null, session.hostKeys.pinned("synthetic-device", 22))
            // The store is not broken: once the disk works the owner's retry (a new session on it) pins.
            storage.fail = false
            session.hostKeys.pin("synthetic-device", 22, presented)
            assertEquals(presented, session.hostKeys.pinned("synthetic-device", 22))
        } finally {
            root.cancel(); main { session.close("closed") }
        }
    }
}
