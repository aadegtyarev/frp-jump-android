package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.ops.ssh.*
import ru.adsrv.frpjump.ops.*
import ru.adsrv.frpjump.assistant.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.connection.channel.direct.Signal
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.api.*
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ports.*
import ru.adsrv.frpjump.profiles.*
import ru.adsrv.frpjump.ssh.*
import ru.adsrv.frpjump.tunnel.*
import ru.adsrv.frpjump.undo.PendingUndo
import java.io.*
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AssistantSshCommandTest {
    private class Client : SSHClient() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public
        val pin = PinnedHostKey(KeyType.fromKey(key).toString(), sshFingerprint(Buffer.PlainBuffer().putPublicKey(key).compactData))
        val verifiers = mutableListOf<HostKeyVerifier>()
        var connectedPort = 0
        var user = ""
        var password = ""
        var keyAuth = false
        var refuse = false
        var failExec = false
        var failRead = false
        var code: Int? = 0
        var signal: Signal? = null
        var out = "stdout synthetic"
        var err = "stderr synthetic"
        var beforeAuth: (() -> Unit)? = null
        var beforeDisconnect: (() -> Unit)? = null
        var beforeConnect: (() -> Unit)? = null
        var beforeStartSession: (() -> Unit)? = null
        val authAttempts = AtomicInteger()
        val sessions = AtomicInteger()
        val disconnected = CountDownLatch(1)
        val disconnectedTwice = CountDownLatch(2)
        var readHook: ((String) -> Unit)? = null
        val disconnectThreads = java.util.concurrent.CopyOnWriteArrayList<String>()
        var heldRead: CountDownLatch? = null
        val reading = CountDownLatch(2)
        val disconnects = AtomicInteger()
        val commands = java.util.concurrent.CopyOnWriteArrayList<String>()
        private fun stream(text: String, name: String) = object : ByteArrayInputStream(text.toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                readHook?.let { it(name); return super.read(b, off, len) }
                if (failRead) throw IOException("private-stream-detail")
                reading.countDown(); heldRead?.let { check(it.await(5, TimeUnit.SECONDS)) }
                return super.read(b, off, len)
            }
        }
        val channel by lazy { object : SessionChannel(connection) {
            override fun exec(command: String): net.schmizz.sshj.connection.channel.direct.Session.Command { commands += command; if (failExec) throw IOException("private-exec-detail"); return this }
            override fun getInputStream() = stream(out, "stdout")
            override fun getErrorStream() = stream(err, "stderr")
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getExitStatus(): Int? = code
            override fun getExitSignal(): Signal? = signal
            override fun join() = Unit
            override fun close() = Unit
        } }
        override fun addHostKeyVerifier(verifier: HostKeyVerifier) { verifiers += verifier }
        override fun connect(hostname: String, port: Int) {
            beforeConnect?.invoke()
            assertEquals("127.0.0.1", hostname); connectedPort = port
            if (!verifiers.all { it.verify(hostname, port, key) }) throw IOException("private trust detail")
        }
        override fun authPassword(username: String, password: String) {
            authAttempts.incrementAndGet(); beforeAuth?.invoke(); if (refuse) throw UserAuthException("private auth detail")
            user = username; this.password = password
        }
        override fun authPublickey(username: String, providers: Iterable<KeyProvider>) {
            authAttempts.incrementAndGet(); beforeAuth?.invoke(); if (refuse) throw UserAuthException("private key detail")
            user = username; keyAuth = true
        }
        override fun startSession(): SessionChannel { sessions.incrementAndGet(); beforeStartSession?.invoke(); return channel }
        override fun disconnect() {
            disconnectThreads += Thread.currentThread().name; disconnects.incrementAndGet(); disconnected.countDown(); disconnectedTwice.countDown(); beforeDisconnect?.invoke()
        }
    }
    private class Fixture : AutoCloseable {
        val profiles = SyntheticProfileInformation()
        val client = Client()
        val factories = AtomicInteger()
        var beforeFactory: (() -> Unit)? = null
        val box = FakeKeystore().box("synthetic-ssh")
        val passwords = SshPasswords(MemoryStorage(), box)
        val env = SshEnvironment(SshKeyStore(MemoryStorage(), box), passwords, { "synthetic" }, TmuxNames(MemoryStorage()),
            clientFactory = { beforeFactory?.invoke(); factories.incrementAndGet(); client })
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
            override suspend fun disconnect(c: ru.adsrv.frpjump.store.Credentials, deviceName: String, targetPort: Int): Boolean = error("No tunnel control")
        }, runtime.session, LocalPortStore(MemoryStorage()), runner, { true })
        val service = AssistantSshCommands(SshTargets(profiles.state, profiles.hidden, env))
        val tool = AssistantSshCommandTool(service)
        val args = buildJsonObject { put("profile_id", "home"); put("connection_id", "ssh"); put("command", "printf synthetic") }
        suspend fun ready() {
            runtime.tunnels.close()
            // Existing real profile fixture; swap only its transport owner for synthetic frpc/API.
            ProfileRuntime::class.java.getDeclaredField("tunnels").apply { isAccessible = true }.set(runtime, manager)
            runtime.connections.save(Connection("ssh", "same", 22, PortKind.SSH, user = "saved-user"))
            profiles.runtimes.getValue("work").connections.save(Connection("ssh", "same", 22, PortKind.SSH, user = "other-user"))
            runtime.sshLogins.remember("same", 22, SshLogin("port-user", SshAuthMethod.PASSWORD))
            passwords.remember("home", "same", 22, "saved-user", "private-saved-password")
            runtime.hostKeys.pin("same", 22, client.pin)
            manager.sync()
            withTimeout(5000) { manager.state.first { it.tunnels.size == 1 } }
        }
        suspend fun loseTarget(source: String) {
            when (source) {
                "profiles" -> {
                    val old = profiles.state.value
                    profiles.state.value = old.copy(enrollments = mapOf("home" to 1))
                    profiles.state.value = old
                }
                "hidden-connection", "hidden-tunnel" -> {
                    profiles.hidden.value = setOf(if (source == "hidden-connection") PendingUndo.connectionKey("home", "ssh")
                        else PendingUndo.tunnelKey("home", "same", 22))
                    profiles.hidden.value = emptySet()
                }
                "connection" -> {
                    val old = runtime.connections.connections.value.single()
                    runtime.connections.save(old.copy(user = "replacement-user")); runtime.connections.save(old)
                }
                "tunnel-state" -> {
                    val before = client.disconnects.get()
                    (runner.states as MutableStateFlow).value = mapOf("g" to VisitorState(TunnelStatus.FAILED))
                    withTimeout(5000) { while (client.disconnects.get() == before) delay(10) }
                    (runner.states as MutableStateFlow).value = mapOf("g" to VisitorState(TunnelStatus.RELAYED))
                }
                "tunnel-pending" -> manager.disconnect("same", 22) // Synthetic API refuses control; pending still invalidates old work.
                "session" -> { runtime.session.markRemoved(); runtime.session.clearRemoved() }
                "plan" -> { val old = grants; grants = emptyList(); manager.sync(); grants = old; manager.sync() }
                else -> error("Unknown synthetic loss")
            }
        }
        suspend fun execute() = tool.execute(args)
        override fun close() { manager.close(); profiles.close() }
    }
    @Test fun `saved any-profile target uses exact port user remembered password without terminal or inventory request`() = runBlocking {
        Fixture().use { f ->
            f.ready(); assertEquals(0, f.factories.get())
            val result = f.execute()
            assertEquals(ToolOutcome.SUCCESS, result.outcome)
            assertTrue(result.text.contains("stdout:\nstdout synthetic")); assertTrue(result.text.contains("stderr:\nstderr synthetic"))
            assertTrue(result.text.contains("exit_status: 0"))
            assertEquals(f.manager.state.value.tunnels.single().localPort, f.client.connectedPort)
            assertEquals("saved-user", f.client.user); assertEquals("private-saved-password", f.client.password)
            assertEquals("private-saved-password", f.passwords.get("home", "same", 22, "saved-user"))
            assertFalse(result.text.contains("private-")); assertEquals(listOf("printf synthetic"), f.client.commands)
            assertEquals(0, f.profiles.deviceRequests.get()); assertEquals(0, f.profiles.versionRequests.get())
            assertTrue(f.client.disconnects.get() > 0)
        }
    }
    @Test fun `missing ambiguous non-SSH hidden removed and down targets never construct client`() = runBlocking {
        for (change in listOf<suspend (Fixture) -> Unit>(
            { it.profiles.state.value = it.profiles.state.value.copy(index = ProfileIndex(emptyList())) },
            { it.profiles.state.value = it.profiles.state.value.copy(runtimes = emptyMap()) },
            { it.profiles.state.value = it.profiles.state.value.copy(index = ProfileIndex(it.profiles.profiles + it.profiles.profiles.last())) },
            { it.runtime.connections.remove("ssh") },
            { it.runtime.connections.save(Connection("ssh", "same", 22, PortKind.TCP)) },
            { it.profiles.hidden.value = setOf(PendingUndo.connectionKey("home", "ssh")) },
            { it.profiles.hidden.value = setOf(PendingUndo.tunnelKey("home", "same", 22)) },
            { it.runtime.session.markRemoved() },
            { it.grants = emptyList(); it.manager.sync(); it.manager.state.first { s -> s.tunnels.isEmpty() } },
            { (it.runner.states as MutableStateFlow).value = mapOf("g" to VisitorState(TunnelStatus.FAILED)); it.manager.state.first { s -> s.tunnels.single().state.status == TunnelStatus.FAILED } },
        )) Fixture().use { f -> f.ready(); change(f); assertEquals(ToolOutcome.FAILED, f.execute().outcome); assertEquals(0, f.factories.get()) }
    }
    @Test fun `missing refused unreadable auth and unknown or changed pins execute no command and hide diagnostics`() = runBlocking {
        for (change in listOf<(Fixture) -> Unit>(
            { it.runtime.sshLogins.wipe() },
            { it.passwords.forget("home", "same", 22, "saved-user") },
            { it.env.refusals.record("home", "same", 22, "saved-user", LoginRefusal.PASSWORD_SAVED) },
            { it.box.destroy() },
            { it.runtime.hostKeys.forget("same", 22) },
            { it.runtime.hostKeys.pin("same", 22, PinnedHostKey("ssh-rsa", "changed")) },
            { it.client.refuse = true },
        )) Fixture().use { f -> f.ready(); change(f); val result = f.execute(); assertEquals(ToolOutcome.FAILED, result.outcome)
            assertTrue(f.client.commands.isEmpty()); assertFalse(result.text.contains("private-")); assertTrue(result.text.contains("SSH")) }
    }
    @Test fun `ordinary key auth and bounded concurrent streams preserve nonzero unknown and signal`() = runBlocking {
        for ((code, signal) in listOf(7 to null, null to null, null to Signal.TERM, 0 to null)) Fixture().use { f ->
            f.ready(); f.runtime.sshLogins.remember("same", 22, SshLogin(method = SshAuthMethod.KEY))
            f.client.code = code; f.client.signal = signal
            f.client.out = "o".repeat(100_000); f.client.err = "e".repeat(100_000)
            val result = f.execute()
            assertTrue(f.client.keyAuth); assertTrue(result.text.length < 8000)
            assertEquals(2, Regex("output omitted").findAll(result.text).count())
            assertTrue(result.text.contains("exit_status: ${code ?: "unknown"}"))
            assertTrue(result.text.contains("exit_signal: ${signal?.name ?: "unknown"}"))
            assertEquals(if (code == 0) ToolOutcome.SUCCESS else ToolOutcome.FAILED, result.outcome)
        }
    }
    @Test fun `exec stream and construction failures are fixed unknown outcomes not success or raw diagnostics`() = runBlocking {
        for (phase in listOf("exec", "read", "factory")) Fixture().use { f -> f.ready()
            when (phase) {
                "exec" -> f.client.failExec = true
                "read" -> { f.client.code = null; f.client.failRead = true }
                else -> f.beforeFactory = { error("private-factory-detail") }
            }
            val result = f.execute()
            assertEquals(ToolOutcome.FAILED, result.outcome)
            assertTrue(result.text.contains("unknown")); assertFalse(result.text.contains("private-"))
            assertEquals(if (phase == "factory") 0 else 1, f.client.commands.size)
        }
    }
    @Test fun `actual worker admission refusal performs no credential or client work`() = runBlocking {
        Fixture().use { f -> f.ready()
            val asked = mutableListOf<String>()
            try { withContext(SideEffectAdmission { asked += it; it != "ssh-dispatch" }) { f.tool.execute(f.args) }; fail("must cancel") } catch (_: CancellationException) { }
            assertEquals(listOf("tool-start", "ssh-dispatch"), asked)
            assertEquals(0, f.factories.get()); assertTrue(f.client.commands.isEmpty()); assertEquals(0, f.client.disconnects.get())
        }
    }
    @Test fun `refused tool-start on a ready target stops before worker admission credentials or client for valid and invalid arguments`() = runBlocking {
        Fixture().use { f -> f.ready()
            val admitted = mutableListOf<String>()
            for (arguments in listOf(f.args, buildJsonObject { put("profile_id", "home") })) {
                try { withContext(SideEffectAdmission { admitted += it; false }) { f.tool.execute(arguments) }; fail("must cancel") } catch (_: CancellationException) { }
            }
            assertEquals("ssh-dispatch is never asked after a refused tool-start", listOf("tool-start", "tool-start"), admitted)
            assertEquals(0, f.factories.get()); assertTrue(f.client.commands.isEmpty())
        }
    }
    @Test fun `an owned call admits tool-start once before the ssh worker`() = runBlocking {
        Fixture().use { f -> f.ready()
            val events = java.util.concurrent.CopyOnWriteArrayList<String>()
            f.beforeFactory = { events += "factory" }
            val result = withContext(SideEffectAdmission { events += "admit:$it"; true }) { f.tool.execute(f.args) }
            assertEquals(ToolOutcome.SUCCESS, result.outcome)
            assertEquals(listOf("admit:tool-start", "admit:ssh-dispatch", "factory"), events.toList())
        }
    }
    @Test fun `commit after the ssh-dispatch check constructs no client`() = runBlocking {
        Fixture().use { f -> f.ready()
            // A Stop that lands exactly after the start check passed: the caller's own job is cancelled inside the admission check.
            lateinit var job: Job
            job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                withContext(SideEffectAdmission { category -> if (category == "ssh-dispatch") job.cancel(); true }) { f.tool.execute(f.args) }
            }
            job.start(); withTimeout(5000) { job.join() }
            assertTrue(job.isCancelled)
            assertEquals(0, f.factories.get()); assertTrue(f.client.commands.isEmpty()); assertEquals(0, f.client.sessions.get())
        }
    }
    @Test fun `cancel at each phase never starts the command and disconnects`() = runBlocking {
        for (phase in listOf("factory", "connect", "auth", "startSession")) Fixture().use { f -> f.ready()
            lateinit var job: Job
            job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { f.execute() }
            val hook = { job.cancel(); Unit }
            when (phase) {
                "factory" -> f.beforeFactory = hook
                "connect" -> f.client.beforeConnect = hook
                "auth" -> f.client.beforeAuth = hook
                else -> f.client.beforeStartSession = hook
            }
            job.start(); withTimeout(5000) { job.join() }
            assertTrue("$phase: caller cancelled", job.isCancelled)
            assertTrue("$phase: no command", f.client.commands.isEmpty())
            assertEquals("$phase: no session after cancel", if (phase == "startSession") 1 else 0, f.client.sessions.get())
            assertEquals("$phase: no auth after cancel", if (phase == "auth" || phase == "startSession") 1 else 0, f.client.authAttempts.get())
            assertEquals("$phase: no connect after cancel", if (phase == "factory") 0 else f.manager.state.value.tunnels.single().localPort, f.client.connectedPort)
            assertTrue("$phase: client disconnected", f.client.disconnects.get() >= 1)
        }
    }
    @Test fun `disconnect runs on an IO thread for Stop and for target loss never on the caller or publisher thread`() = runBlocking {
        val main = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        val publisher = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "fake-publisher") }
        // Coroutine debug mode renames threads ("fake-main @coroutine#7"): compare the base name.
        fun base(name: String) = name.substringBefore(" @coroutine")
        try {
            for (cause in listOf("stop", "target-loss")) Fixture().use { f -> f.ready()
                // The worker is blocked inside authentication (a blocking call), so the closer's disconnect is necessarily the first one.
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                f.client.beforeAuth = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                val work = launch(main.asCoroutineDispatcher()) { f.execute() }
                try {
                    assertTrue("$cause: worker blocked in auth", entered.await(3, TimeUnit.SECONDS))
                    publisher.submit { if (cause == "stop") work.cancel() else runBlocking { f.loseTarget("hidden-connection") } }.get(3, TimeUnit.SECONDS)
                    assertTrue("$cause: closer disconnected while the worker is blocked", f.client.disconnected.await(3, TimeUnit.SECONDS))
                    assertFalse("$cause: the blocked worker still owns the exit", work.isCompleted)
                    val first = f.client.disconnectThreads.toList()
                    assertEquals("$cause: only the closer disconnected so far: $first", 1, first.size)
                    assertTrue("$cause: closer on an IO worker: $first", base(first.single()).startsWith("DefaultDispatcher-worker"))
                } finally {
                    release.countDown()
                    // Even after a failed assertion: join (or cancel) before the dispatchers shut down, so a regression fails instead of hanging.
                    if (withTimeoutOrNull(5000) { work.join() } == null) work.cancel()
                }
                assertTrue("$cause: worker exited within 5 s after release", work.isCompleted)
                val threads = f.client.disconnectThreads.map(::base)
                assertTrue("$cause: every disconnect on an IO worker: $threads", threads.isNotEmpty() && threads.all { it.startsWith("DefaultDispatcher-worker") })
            }
        } finally { main.shutdownNow(); publisher.shutdownNow() }
    }
    @Test fun `cancel while the sibling reader blocks releases it`() = runBlocking {
        Fixture().use { f -> f.ready()
            val release = CountDownLatch(1); val stderrBlocked = CountDownLatch(1)
            // The stderr reader blocks (the fake never unblocks by itself); only then does the stdout reader fail. Without that order
            // a fast stdout failure cancels the stderr reader before it ever starts, and there is no blocked sibling to test.
            f.client.readHook = { name ->
                if (name == "stdout") { check(stderrBlocked.await(5, TimeUnit.SECONDS)); throw IOException("private-stream-detail") }
                else { stderrBlocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            }
            val work = async(Dispatchers.Default) { f.execute() }
            try {
                assertTrue("the failed reader's catch disconnects while its sibling is blocked", f.client.disconnected.await(3, TimeUnit.SECONDS))
                delay(50); assertFalse("the blocked reader still owns the exit: ${if (work.isCompleted) runCatching { work.getCompleted().text } else ""}", work.isCompleted)
            } finally { release.countDown() }
            val result = withTimeout(5000) { work.await() }
            assertEquals(ToolOutcome.FAILED, result.outcome); assertTrue(result.text.contains("SSH command failed")); assertFalse(result.text.contains("private-"))
        }
    }
    @Test fun `target hide then Undo during auth fails old operation without retargeting`() = runBlocking {
        for (loss in listOf("hidden", "connection", "profile", "tunnel")) Fixture().use { f -> f.ready()
            f.client.beforeAuth = {
                when (loss) {
                    "hidden" -> {
                        f.profiles.hidden.value = setOf(PendingUndo.tunnelKey("home", "same", 22))
                        f.profiles.hidden.value = emptySet()
                    }
                    "connection" -> {
                        val old = f.runtime.connections.connections.value.single()
                        f.runtime.connections.remove("ssh"); f.runtime.connections.save(old)
                    }
                    "profile" -> {
                        val old = f.profiles.state.value
                        f.profiles.state.value = old.copy(runtimes = emptyMap()); f.profiles.state.value = old
                    }
                    else -> runBlocking {
                        val old = f.grants; f.grants = emptyList(); f.manager.sync()
                        f.grants = old; f.manager.sync()
                    }
                }
            }
            val result = f.execute()
            assertEquals("invalidated $loss target", ToolOutcome.FAILED, result.outcome)
            assertTrue(result.text.contains("No command was executed"))
            assertTrue(f.client.commands.isEmpty()); assertEquals(1, f.factories.get())
        }
    }
    @Test fun `cancel waits for actual worker readers and cleanup including late-created client`() = runBlocking {
        for (phase in listOf("factory", "read", "cleanup")) Fixture().use { f -> f.ready()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val unrelatedClient = Client()
            when (phase) {
                "factory" -> f.beforeFactory = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                "read" -> f.client.heldRead = release
                else -> f.client.beforeDisconnect = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            }
            val work = launch(Dispatchers.Default) { f.execute() }
            try {
                assertTrue((if (phase == "read") f.client.reading else entered).await(3, TimeUnit.SECONDS))
                work.cancel()
                // G1: Stop reaches the client promptly (read: the closer; cleanup: the closer next to the worker's own held disconnect).
                if (phase == "read") assertTrue("read: disconnected promptly", f.client.disconnected.await(3, TimeUnit.SECONDS))
                if (phase == "cleanup") assertTrue("cleanup: the closer disconnects next to the held one", f.client.disconnectedTwice.await(3, TimeUnit.SECONDS))
                if (phase != "factory") assertTrue("$phase: disconnect threads ${f.client.disconnectThreads}", f.client.disconnectThreads.all { it.startsWith("DefaultDispatcher-worker") })
                delay(50); assertFalse("$phase must retain actual-exit ownership", work.isCompleted)
            } finally { release.countDown() }
            withTimeout(5000) { work.join() }
            assertTrue(f.client.disconnects.get() > 0)
            if (phase == "factory") assertTrue(f.client.commands.isEmpty())
            assertEquals(0, unrelatedClient.disconnects.get()); assertTrue(unrelatedClient.commands.isEmpty())
        }
    }
    private class Chat(val f: Fixture, private val timeout: Long = 30_000) : AutoCloseable {
        val settings = ru.adsrv.frpjump.settings.SettingsStore(MemoryStorage()).apply { update { it.copy(assistantEnabled = true) } }
        val providerFile = MemoryStorage()
        val store = AssistantProviderStore(providerFile, FakeKeystore().box("synthetic-model"), settings.assistantChat).apply {
            save(AssistantProviderDraft(AssistantProviderSettings(model = "synthetic"), null, "synthetic-key"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requests = java.util.concurrent.CopyOnWriteArrayList<AgentRequest>()
        var heldReply: CompletableDeferred<Unit>? = null
        var afterTool: CompletableDeferred<Unit>? = null
        val provider = AgentProvider { request ->
            requests += request
            if (request.turns.last().exchanges.isEmpty()) {
                heldReply?.await()
                AgentReply(calls = listOf(ToolCall("ssh-${requests.size}", "ssh_command", f.args)))
            } else { afterTool?.await(); AgentReply("Synthetic answer") }
        }
        val module = bindAssistantLifecycle(settings, scope)
        val chat = conversation()
        fun conversation() = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, { provider },
            store::requireRevision, module, Dispatchers.IO, { AgentLimits(toolTimeoutMs = timeout) }, settings.assistantChat,
            sshCommands = f.service, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
        fun send() { chat.edit("synthetic command"); chat.send() }
        suspend fun idle() = withTimeout(5000) { chat.state.first { !it.busy } }
        override fun close() { chat.close(); scope.cancel() }
    }
    @Test fun `Chat default off explicit invocation and captured next Send set preserve history`() = runBlocking {
        Fixture().use { f -> f.ready(); Chat(f).use { c ->
            c.send(); c.idle()
            assertEquals(ToolOutcome.UNKNOWN_TOOL, c.requests.last().turns.last().exchanges.single().results.single().outcome)
            assertEquals(0, f.factories.get()); assertTrue(c.requests.first().tools.isEmpty())
            f.client.beforeAuth = { assertFalse(Thread.holdsLock(c.settings.assistantChat.gate)) }
            c.settings.setAssistantTool(AssistantToolId.SSH_COMMAND, true); assertEquals(0, f.factories.get())
            c.heldReply = CompletableDeferred(); c.send()
            withTimeout(5000) { while (c.requests.size < 3) delay(10) }
            c.settings.setAssistantTool(AssistantToolId.SSH_COMMAND, false)
            c.heldReply!!.complete(Unit); c.idle(); c.heldReply = null
            assertEquals(1, f.factories.get())
            assertEquals(ToolOutcome.SUCCESS, c.requests.last().turns.last().exchanges.single().results.single().outcome)
            assertEquals(2, c.chat.state.value.turns.size)
            c.send(); c.idle(); assertTrue(c.requests.last().tools.isEmpty()); assertEquals(1, f.factories.get())
        } }
    }
    @Test fun `Chat guard revokes actual SSH admission before any worker starts`() = runBlocking {
        Fixture().use { f -> f.ready(); Chat(f).use { c ->
            c.settings.setAssistantTool(AssistantToolId.SSH_COMMAND, true)
            c.settings.assistantChat.probes = ChatProbes(before = { category -> if (category == "ssh-dispatch") {
                c.settings.update { it.copy(assistantEnabled = false) }
            } })
            c.send(); c.idle()
            assertEquals(0, f.factories.get()); assertTrue(f.client.commands.isEmpty()); assertTrue(c.chat.state.value.turns.isEmpty())
        } }
    }
    @Test fun `Chat Stop pop disable and provider change hold shared slot until owned cleanup exits`() = runBlocking {
        for (revoke in listOf("stop", "pop", "disable", "provider")) Fixture().use { f -> f.ready(); Chat(f).use { c ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.client.beforeDisconnect = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            c.settings.setAssistantTool(AssistantToolId.SSH_COMMAND, true); c.send()
            var replacement: AssistantConversation? = null
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                when (revoke) {
                    "stop" -> c.chat.stop()
                    "pop" -> c.chat.close()
                    "disable" -> { c.settings.update { it.copy(assistantEnabled = false) }; c.chat.refresh() }
                    else -> { c.store.save(AssistantProviderDraft(AssistantProviderSettings(model = "new"), c.store.currentSnapshot.revision)); c.chat.refresh() }
                }
                replacement = c.conversation(); replacement.edit("replacement"); replacement.send()
                assertTrue(c.settings.assistantChat.busy.value); assertTrue(replacement.state.value.busy)
                assertEquals(1, f.factories.get()); assertTrue(c.chat.state.value.turns.isEmpty())
            } finally { release.countDown() }
            withTimeout(5000) { c.settings.assistantChat.busy.first { !it } }
            assertTrue(c.chat.state.value.turns.isEmpty()); assertEquals(1, c.requests.size)
            replacement?.close()
        } }
    }
    @Test fun `Chat tool timeout retains slot through held readers and returns honest uncertain timeout`() = runBlocking {
        Fixture().use { f -> f.ready(); Chat(f, timeout = 100).use { c ->
            val release = CountDownLatch(1); f.client.heldRead = release
            c.settings.setAssistantTool(AssistantToolId.SSH_COMMAND, true); c.send()
            try {
                assertTrue(f.client.reading.await(3, TimeUnit.SECONDS))
                withTimeout(5000) { while (f.client.disconnects.get() == 0) delay(10) }
                assertTrue(c.settings.assistantChat.busy.value); assertTrue(c.chat.state.value.busy)
                c.chat.send(); assertEquals(1, f.factories.get())
            } finally { release.countDown() }
            c.idle()
            val result = c.requests.last().turns.last().exchanges.single().results.single()
            assertEquals(ToolOutcome.TIMEOUT, result.outcome); assertTrue(result.text.contains("unknown"))
            assertEquals(1, f.client.commands.size)
        } }
    }

    @Test fun `SSH TARGET OUTCOME before exec is an explicit failed tool result`() = runBlocking {
        Fixture().use { f -> f.ready()
            f.client.beforeAuth = {
                f.profiles.hidden.value = setOf(PendingUndo.connectionKey("home", "ssh"))
                f.profiles.hidden.value = emptySet()
            }
            val result = try { f.execute() } catch (_: CancellationException) {
                fail("Local target loss must return FAILED, not cancel an authorized invocation"); error("unreachable")
            }
            assertEquals(ToolOutcome.FAILED, result.outcome)
            assertTrue(result.text.contains("No command was executed"))
            assertTrue(f.client.commands.isEmpty()); assertEquals(1, f.factories.get())
        }
        Fixture().use { f -> f.ready()
            f.beforeFactory = {
                f.profiles.hidden.value = setOf(PendingUndo.connectionKey("home", "ssh"))
                throw IOException("private-late-construction-failure")
            }
            val result = f.execute()
            assertEquals(ToolOutcome.FAILED, result.outcome)
            assertTrue(result.text.contains("No command was executed")); assertFalse(result.text.contains("private-"))
            assertTrue(f.client.commands.isEmpty())
        }
    }
    @Test fun `SSH TARGET OUTCOME cleanup loss gives Chat failed feedback with known exit and no late output`() = runBlocking {
        Fixture().use { f -> f.ready(); Chat(f).use { c ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.client.code = 7; f.client.out = "private-late-stdout"; f.client.err = "private-late-stderr"
            f.client.beforeDisconnect = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            c.settings.setAssistantTool(AssistantToolId.SSH_COMMAND, true); c.send()
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                f.profiles.hidden.value = setOf(PendingUndo.tunnelKey("home", "same", 22))
                f.profiles.hidden.value = emptySet()
                assertTrue(c.settings.assistantChat.busy.value)
                assertEquals(1, c.requests.size)
            } finally { release.countDown() }
            c.idle()
            assertEquals("Authorized Send must receive the failed ToolResult and continue", 2, c.requests.size)
            val result = c.requests.last().turns.last().exchanges.single().results.single()
            assertEquals(ToolOutcome.FAILED, result.outcome)
            assertTrue(result.text.contains("exit_status: 7")); assertFalse(result.text.contains("private-"))
            assertTrue(c.chat.state.value.turns.single().toolFailed)
            assertEquals(1, f.client.commands.size); assertEquals(1, f.factories.get())
        } }
    }

    @Test fun `SSH TARGET OUTCOME all local sources before exec during readers and cleanup reach actual Chat failure`() = runBlocking {
        val sources = listOf("profiles", "hidden-connection", "hidden-tunnel", "connection", "tunnel-state", "tunnel-pending", "session", "plan")
        for (source in sources) for (phase in listOf("auth", "read", "cleanup")) Fixture().use { f -> f.ready(); Chat(f).use { c ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val unrelated = Client()
            f.client.out = "private-late-stdout"; f.client.err = "private-late-stderr"
            when (phase) {
                "auth" -> f.client.beforeAuth = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                "read" -> {
                    val received = when (source) {
                        "profiles", "hidden-connection" -> 0 to null
                        "hidden-tunnel", "connection" -> 7 to null
                        "tunnel-state", "tunnel-pending" -> null to Signal.TERM
                        else -> null to null
                    }
                    f.client.code = received.first; f.client.signal = received.second; f.client.heldRead = release
                }
                else -> { f.client.code = 7; f.client.beforeDisconnect = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            }
            c.afterTool = CompletableDeferred()
            c.settings.setAssistantTool(AssistantToolId.SSH_COMMAND, true); c.send()
            try {
                assertTrue("$source/$phase entered", (if (phase == "read") f.client.reading else entered).await(3, TimeUnit.SECONDS))
                f.loseTarget(source)
                if (source == "plan" && phase == "read") f.client.failRead = true // Disconnect/read failure must still be a local target failure.
                assertTrue("$source/$phase must hold actual-exit slot", c.settings.assistantChat.busy.value)
                assertEquals(1, c.requests.size)
            } finally { release.countDown() }
            withTimeout(5000) { while (c.requests.size < 2) delay(10) }
            val result = c.requests.last().turns.last().exchanges.single().results.single()
            assertEquals("$source/$phase", ToolOutcome.FAILED, result.outcome)
            assertFalse("$source/$phase late private output", result.text.contains("private-")); assertFalse("$source/$phase replacement output", result.text.contains("replacement-user"))
            when (phase) {
                "auth" -> assertTrue(result.text.contains("No command was executed"))
                "read" -> if (f.client.code == null && f.client.signal == null) assertTrue(result.text.contains("remote outcome unknown")) else {
                    assertTrue(result.text.contains("exit_status: ${f.client.code ?: "unknown"}"))
                    assertTrue(result.text.contains("exit_signal: ${f.client.signal?.name ?: "unknown"}"))
                }
                else -> assertTrue(result.text.contains("exit_status: 7"))
            }
            assertTrue(c.chat.state.value.toolFailed); assertEquals(ChatToolProgress.FAILED, c.chat.state.value.toolProgress)
            c.afterTool!!.complete(Unit); c.idle()
            assertTrue(c.chat.state.value.turns.single().toolFailed)
            assertEquals(if (phase == "auth") 0 else 1, f.client.commands.size)
            assertEquals(1, f.factories.get()); assertEquals(0, unrelated.disconnects.get())
            println("target-source=$source phase=$phase FAILED feedback; output withheld; actual cleanup exited")
        } }
    }
    @Test fun `SSH TARGET OUTCOME known zero nonzero signal and missing exit remain truthful after cleanup loss`() = runBlocking {
        for ((code, signal) in listOf(0 to null, 7 to null, null to Signal.TERM, null to null)) Fixture().use { f -> f.ready()
            f.client.code = code; f.client.signal = signal
            f.client.beforeDisconnect = { f.profiles.hidden.value = setOf(PendingUndo.connectionKey("home", "ssh")) }
            val result = f.execute()
            assertEquals(ToolOutcome.FAILED, result.outcome)
            if (code != null || signal != null) {
                assertTrue(result.text.contains("exit_status: ${code ?: "unknown"}"))
                assertTrue(result.text.contains("exit_signal: ${signal?.name ?: "unknown"}"))
            } else assertTrue(result.text.contains("remote outcome unknown"))
            assertFalse(result.text.contains("stdout synthetic")); assertFalse(result.text.contains("stderr synthetic"))
        }
    }
    @Test fun `SSH TARGET OUTCOME external owner revocation wins local failure race through cleanup`() = runBlocking {
        for (revoke in listOf("stop", "pop", "disable", "off-on", "provider", "read-problem")) Fixture().use { f -> f.ready(); Chat(f).use { c ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.client.beforeDisconnect = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            c.settings.setAssistantTool(AssistantToolId.SSH_COMMAND, true); c.send()
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                f.loseTarget("hidden-connection")
                when (revoke) {
                    "stop" -> c.chat.stop()
                    "pop" -> c.chat.close()
                    "disable", "off-on" -> {
                        c.settings.update { it.copy(assistantEnabled = false) }
                        if (revoke == "off-on") c.settings.update { it.copy(assistantEnabled = true) }
                        c.chat.refresh()
                    }
                    "provider" -> { c.store.save(AssistantProviderDraft(AssistantProviderSettings(model = "new"), c.store.currentSnapshot.revision)); c.chat.refresh() }
                    else -> { c.providerFile.write("invalid"); c.store.reload(); c.chat.refresh() }
                }
                assertTrue(c.settings.assistantChat.busy.value)
            } finally { release.countDown() }
            withTimeout(5000) { c.settings.assistantChat.busy.first { !it } }
            assertEquals("$revoke must not publish stale local target failure", 1, c.requests.size)
            assertTrue(c.chat.state.value.turns.isEmpty())
        } }
    }
    @Test fun `SSH TARGET OUTCOME timeout racing target loss keeps timeout and actual reader exit`() = runBlocking {
        Fixture().use { f -> f.ready(); Chat(f, timeout = 100).use { c ->
            val release = CountDownLatch(1); f.client.heldRead = release
            c.settings.setAssistantTool(AssistantToolId.SSH_COMMAND, true); c.send()
            try {
                assertTrue(f.client.reading.await(3, TimeUnit.SECONDS))
                f.loseTarget("hidden-tunnel")
                // Timeout must actually cancel the caller before the synthetic readers are released.
                delay(150)
                assertTrue(c.settings.assistantChat.busy.value); assertEquals(1, c.requests.size)
            } finally { release.countDown() }
            c.idle()
            val result = c.requests.last().turns.last().exchanges.single().results.single()
            assertEquals(ToolOutcome.TIMEOUT, result.outcome); assertEquals(1, f.client.commands.size)
        } }
    }

    private suspend fun availableExitWithReaders(code: Int?, signal: Signal?, readFails: Boolean, loseTarget: Boolean): ToolOutput = coroutineScope {
        Fixture().use { f -> f.ready()
            val release = CountDownLatch(1)
            f.client.code = code; f.client.signal = signal
            f.client.out = "private-held-stdout"; f.client.err = "private-held-stderr"
            f.client.heldRead = release
            val work = async(Dispatchers.Default) { f.execute() }
            try {
                assertTrue(f.client.reading.await(3, TimeUnit.SECONDS))
                if (loseTarget) f.loseTarget("hidden-connection")
                if (readFails) f.client.failRead = true
                assertFalse("Held readers must retain actual operation exit", work.isCompleted)
            } finally { release.countDown() }
            val result = work.await()
            assertEquals(ToolOutcome.FAILED, result.outcome)
            assertFalse(result.text.contains("private-")); assertEquals(1, f.client.commands.size)
            result
        }
    }
    /** The operation's own typed result for the same scenario as [availableExitWithReaders]. */
    private suspend fun heldOperationResult(code: Int?, signal: Signal?, readFails: Boolean, loseTarget: Boolean): OpResult<SshExecOutput> = coroutineScope {
        Fixture().use { f -> f.ready()
            val release = CountDownLatch(1)
            f.client.code = code; f.client.signal = signal
            f.client.out = "private-held-stdout"; f.client.err = "private-held-stderr"
            f.client.heldRead = release
            val work = async(Dispatchers.Default) { f.service.exec(SshExecInput("home", "ssh", "printf synthetic")) }
            try {
                assertTrue(f.client.reading.await(3, TimeUnit.SECONDS))
                if (loseTarget) f.loseTarget("hidden-connection")
                if (readFails) f.client.failRead = true
            } finally { release.countDown() }
            work.await()
        }
    }
    @Test fun `OPERATION result types and remote outcomes after a lost target or a failed read`() = runBlocking {
        // Target lost while the readers are held: what the channel already reported is KNOWN, nothing reported is UNKNOWN.
        assertEquals(OpResult.Failed(SshFailure.TargetLost(SshExit(7, null)), RemoteOutcome.KNOWN), heldOperationResult(7, null, readFails = false, loseTarget = true))
        assertEquals(OpResult.Failed(SshFailure.TargetLost(SshExit(null, "TERM")), RemoteOutcome.KNOWN), heldOperationResult(null, Signal.TERM, readFails = false, loseTarget = true))
        assertEquals(OpResult.Failed(SshFailure.TargetLost(null), RemoteOutcome.UNKNOWN), heldOperationResult(null, null, readFails = false, loseTarget = true))
        // A reader failure after the command was sent: a command failure, KNOWN only with an exit.
        assertEquals(OpResult.Failed(SshFailure.CommandFailed(SshExit(0, null)), RemoteOutcome.KNOWN), heldOperationResult(0, null, readFails = true, loseTarget = false))
        assertEquals(OpResult.Failed(SshFailure.CommandFailed(null), RemoteOutcome.UNKNOWN), heldOperationResult(null, null, readFails = true, loseTarget = false))
    }
    @Test fun `EXIT FACTS held readers preserve available zero nonzero signal and missing exit`() = runBlocking {
        val failures = mutableListOf<String>()
        for ((code, signal) in listOf(0 to null, 7 to null, null to Signal.TERM, null to null)) {
            val result = availableExitWithReaders(code, signal, readFails = false, loseTarget = true)
            val correct = if (code == null && signal == null) result.text.contains("remote outcome unknown") else
                result.text.contains("exit_status: ${code ?: "unknown"}") && result.text.contains("exit_signal: ${signal?.name ?: "unknown"}")
            if (!correct) failures += "held status=$code signal=$signal returned ${result.text}"
            println("held status=$code signal=$signal facts-preserved=$correct")
        }
        assertEquals("Available owned-channel exit facts must not depend on reader completion", emptyList<String>(), failures)
    }
    @Test fun `EXIT FACTS failed readers preserve available facts with and without target loss`() = runBlocking {
        val failures = mutableListOf<String>()
        for (lost in listOf(false, true)) for ((code, signal) in listOf(0 to null, 7 to null, null to Signal.TERM, null to null)) {
            val result = availableExitWithReaders(code, signal, readFails = true, loseTarget = lost)
            val correct = if (code == null && signal == null) result.text.contains("remote outcome unknown") else
                result.text.contains("exit_status: ${code ?: "unknown"}") && result.text.contains("exit_signal: ${signal?.name ?: "unknown"}")
            if (!correct) failures += "failed-reader lost=$lost status=$code signal=$signal returned ${result.text}"
            println("failed-reader lost=$lost status=$code signal=$signal facts-preserved=$correct")
        }
        assertEquals("Reader IOException must not erase available channel exit facts", emptyList<String>(), failures)
    }

    @Test fun `a cancelled caller never receives the local target failure result`() = runBlocking {
        repeat(20) {
            val operation = SshCommandOperation()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val outcome = java.util.concurrent.atomic.AtomicReference<String?>()
            val job = launch(Dispatchers.Default) {
                try {
                    operation.run { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); OpResult.Done(SshExecOutput("late result", "", 0, null)) }
                    outcome.set("returned")
                } catch (cancelled: CancellationException) { outcome.set("cancelled"); throw cancelled }
            }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                operation.loseTarget(); job.cancel()
            } finally { release.countDown() }
            withTimeout(5000) { job.join() }
            assertEquals("Stop/timeout/revocation wins even when it races a local target failure", "cancelled", outcome.get())
        }
    }
    @Test fun `tool rejects extra credentials and malformed arguments before admission`() = runBlocking {
        Fixture().use { f ->
            for (args in listOf(buildJsonObject {}, JsonObject(f.args + ("password" to JsonPrimitive("secret"))),
                JsonObject(f.args + ("command" to JsonPrimitive(1))))) {
                assertEquals(ToolOutcome.FAILED, f.tool.execute(args).outcome)
            }
            assertEquals(0, f.factories.get())
        }
    }
}
