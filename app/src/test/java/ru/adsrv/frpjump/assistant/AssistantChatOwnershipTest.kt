package ru.adsrv.frpjump.assistant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage

class AssistantChatOwnershipTest {
    private class Memory : TextStorage {
        @Volatile var text: String? = null
        var written: (() -> Unit)? = null
        var fail = false
        override fun read() = text
        override fun write(text: String) { if (fail) error("synthetic"); this.text = text; written?.invoke() }
        override fun delete() { text = null }
    }
    private val box = object : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
        override fun destroy() = Unit
    }
    private class Gate {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fun waitHere() { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        fun entered() { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
    }
    private suspend fun idle(chat: AssistantConversation) = withTimeout(4000) { chat.state.first { !it.busy } }

    @Test fun `app slot spans old and reopened owners through prep HTTP post-kernel and cleanup`() = runBlocking {
        for (phase in listOf("prep", "http", "post")) for (action in listOf("exit", "stop", "disable", "revision")) for (failure in listOf(false, true)) {
            val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true) } }
            val store = AssistantProviderStore(Memory(), box, settings.assistantChat)
            store.save(AssistantProviderDraft(AssistantProviderSettings(model = "m"), null, "synthetic"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val unrelated = scope.launch { awaitCancellation() }
            val hold = Gate(); var checks = 0; var preparations = 0
            val httpUnderGuard = AtomicBoolean()
            val old = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, {
                if (phase == "prep") hold.waitHere()
                if (failure && phase == "prep") error("synthetic")
                AgentProvider {
                    if (phase == "http") withContext(NonCancellable + Dispatchers.IO) {
                        // The fake starts UNDISPATCHED under admission: suspend before blocking, even on the same IO dispatcher.
                        yield()
                        httpUnderGuard.set(Thread.holdsLock(settings.assistantChat.gate))
                        hold.waitHere()
                    }
                    if (failure && phase == "http") error("synthetic")
                    AgentReply("old answer")
                }
            }, {
                store.requireRevision(it)
                checks++
                if (phase == "post" && checks == 2) { hold.waitHere(); if (failure) error("synthetic") }
            }, AgentModule(true), runtime = settings.assistantChat)
            val newer = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, {
                preparations++; AgentProvider { AgentReply("new answer") }
            }, store::requireRevision, AgentModule(true), runtime = settings.assistantChat)
            try {
                old.edit("old private input"); old.send(); hold.entered()
                if (phase == "http") {
                    println("HTTP fixture $action/$failure blocking body holds Chat guard: ${httpUnderGuard.get()}")
                    assertFalse("$phase/$action/$failure fake HTTP must suspend before blocking", httpUnderGuard.get())
                }
                when (action) {
                    "exit" -> old.close()
                    "stop" -> old.stop()
                    "disable" -> { settings.update { it.copy(assistantEnabled = false) }; settings.update { it.copy(assistantEnabled = true) }; old.refresh() }
                    "revision" -> { store.save(AssistantProviderDraft(AssistantProviderSettings(model = "next"), store.currentSnapshot.revision)); old.refresh() }
                }
                newer.edit("new input"); newer.send()
                assertEquals("$phase/$action/$failure", 0, preparations)
                assertTrue("$phase/$action/$failure busy", newer.state.value.busy); assertTrue("$phase/$action/$failure stopping", newer.state.value.stopping)
                assertNull(newer.state.value.pendingText); assertTrue(newer.state.value.turns.isEmpty())
                hold.release.countDown()
                withTimeout(4000) { settings.assistantChat.busy.first { !it } }
                assertEquals(0, preparations) // No implicit replacement queue.
                newer.send(); idle(newer)
                assertEquals(1, preparations); assertEquals("new answer", newer.state.value.turns.single().answer)
                assertEquals("new input", newer.state.value.turns.single().user); assertTrue(unrelated.isActive)
            } finally { hold.release.countDown(); old.close(); newer.close(); scope.cancel() }
        }
    }

    @Test fun `actual adapter callback exit keeps app slot across completion cancellation and authority changes`() = runBlocking {
        for (action in listOf("success", "error", "invalid", "oversize", "network", "stop", "body-stop", "exit", "disable", "revision", "timeout")) {
            val server = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
            val serverRelease = CountDownLatch(1)
            val serverThread = Thread {
                try {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        var left = length
                        while (left > 0) { val n = input.read(CharArray(left)); if (n < 0) break; left -= n }
                        if (action == "timeout") serverRelease.await(5, TimeUnit.SECONDS)
                        else if (action != "network") {
                            val text = if (action == "error") "failure" else if (action == "invalid") "not-json" else """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"old answer"}}]}"""
                            val bytes = text.toByteArray()
                            val status = if (action == "error") "500 Error" else "200 OK"
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 $status\r\nContent-Length: ${if (action == "oversize") 1_048_577 else bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes); flush()
                            }
                        }
                    }
                } catch (_: java.io.IOException) { }
            }.apply { isDaemon = true; start() }
            val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true) } }
            val store = AssistantProviderStore(Memory(), box, settings.assistantChat)
            store.save(AssistantProviderDraft(AssistantProviderSettings("http://127.0.0.1:${server.localPort}/v1", "m"), null, "synthetic"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val unrelated = scope.launch { awaitCancellation() }
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val awaiting = CountDownLatch(1)
            val adapter = ru.adsrv.frpjump.agent.openai.OpenAiAgentProvider(
                ru.adsrv.frpjump.agent.openai.OpenAiConfig("http://127.0.0.1:${server.localPort}/v1", "m"), "synthetic")
            val probe = adapter.javaClass.getDeclaredField(if (action == "body-stop") "beforeResponseRead" else "beforeCallbackExit").apply { isAccessible = true }
            probe.set(adapter, { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); Unit })
            adapter.javaClass.getDeclaredField("beforeAwaitCallbackExit").apply { isAccessible = true }
                .set(adapter, { awaiting.countDown(); Unit })
            val old = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, { adapter },
                store::requireRevision, AgentModule(true), limits = { AgentLimits(modelTimeoutMs = if (action == "timeout") 100 else 5000) }, runtime = settings.assistantChat)
            var preparations = 0
            val newer = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, {
                preparations++; AgentProvider { AgentReply("new answer") }
            }, store::requireRevision, AgentModule(true), runtime = settings.assistantChat)
            try {
                old.edit("old private input"); old.send()
                assertTrue("callback $action", entered.await(4, TimeUnit.SECONDS))
                when (action) {
                    "stop", "body-stop" -> old.stop()
                    "exit" -> old.close()
                    "disable" -> { settings.update { it.copy(assistantEnabled = false) }; settings.update { it.copy(assistantEnabled = true) }; old.refresh() }
                    "revision" -> { store.save(AssistantProviderDraft(AssistantProviderSettings("http://127.0.0.1:${server.localPort}/v1", "next"), store.currentSnapshot.revision)); old.refresh() }
                }
                assertTrue("coroutine reached callback-exit wait: $action", awaiting.await(4, TimeUnit.SECONDS))
                newer.edit("new input"); newer.send()
                assertEquals("$action must not start replacement", 0, preparations)
                assertTrue("$action must retain slot", settings.assistantChat.busy.value)
                assertTrue(newer.state.value.busy); assertNull(newer.state.value.pendingText)
                assertTrue(newer.state.value.turns.isEmpty()); assertTrue(unrelated.isActive)
                release.countDown(); serverRelease.countDown()
                withTimeout(4000) { settings.assistantChat.busy.first { !it } }
                assertEquals("no queued send", 0, preparations)
                newer.send(); idle(newer)
                assertEquals(1, preparations); assertEquals("new answer", newer.state.value.turns.single().answer)
            } finally {
                release.countDown(); serverRelease.countDown(); old.close(); newer.close(); scope.cancel(); server.close(); serverThread.join(2000)
            }
        }
    }

    @Test fun `actual commits serialize with guarded admission dispatch and every publication category in both orders`() = runBlocking {
        for (change in listOf("disable", "off-on", "revision", "reload", "read-problem"))
            for (category in listOf("admission", "dispatch", "answer", "finish-error", "setup-error", "window"))
                for (commitFirst in listOf(false, true)) {
                    val settingsFile = Memory()
                    val settings = SettingsStore(settingsFile).apply { update { it.copy(assistantEnabled = true) } }
                    val file = Memory(); val store = AssistantProviderStore(file, box, settings.assistantChat)
                    store.save(AssistantProviderDraft(AssistantProviderSettings(model = "m"), null, "synthetic"))
                    val before = store.currentSnapshot
                    val written = CountDownLatch(1)
                    settingsFile.written = { written.countDown() }; file.written = { written.countDown() }
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    var calls = 0
                    var triggerSetup = false
                    val chat = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, {
                        if (triggerSetup) throw ProviderSetupException(ProviderSetupProblem.CRYPTO_FAILED)
                        AgentProvider { calls++; if (category == "finish-error") error("synthetic"); AgentReply("answer".repeat(20)) }
                    }, store::requireRevision, AgentModule(true), limits = { AgentLimits(contextTokens = 800, outputReserveTokens = 100) }, runtime = settings.assistantChat)
                    if (category == "window") { chat.edit("x".repeat(100)); chat.send(); idle(chat) }
                    triggerSetup = category == "setup-error"
                    val hold = Gate()
                    val once = AtomicBoolean()
                    val probe: (String) -> Unit = { point -> if (point == category && once.compareAndSet(false, true)) hold.waitHere() }
                    if (commitFirst) settings.assistantChat.beforeAction = probe else settings.assistantChat.checkedAction = probe
                    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
                    val commit = Thread {
                        try {
                            when (change) {
                                "disable", "off-on" -> { settings.update { it.copy(assistantEnabled = false) }; if (change == "off-on") settings.update { it.copy(assistantEnabled = true) } }
                                "revision" -> store.save(AssistantProviderDraft(AssistantProviderSettings(model = "next"), before.revision))
                                "reload" -> { val other = AssistantProviderStore(file, box); other.save(AssistantProviderDraft(AssistantProviderSettings(model = "external"), before.revision)); store.reload() }
                                "read-problem" -> { file.text = "invalid"; written.countDown(); store.reload() }
                            }
                        } catch (error: Throwable) { failure.set(error) }
                    }
                    try {
                        val send = scope.launch { chat.edit(if (category == "window") "y".repeat(100) else "draft"); chat.send() }
                        hold.entered()
                        commit.start()
                        if (commitFirst) {
                            commit.join(2000); assertFalse(commit.isAlive)
                            hold.release.countDown()
                        } else {
                            assertTrue(written.await(2, TimeUnit.SECONDS))
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                            while (commit.state != Thread.State.BLOCKED && commit.isAlive && System.nanoTime() < deadline) Thread.yield()
                            assertEquals("commit must wait at the actual common guard", Thread.State.BLOCKED, commit.state)
                            assertEquals(before, store.currentSnapshot)
                            assertTrue(settings.assistantAvailability.enabled)
                            hold.release.countDown()
                            commit.join(2000); assertFalse(commit.isAlive)
                        }
                        send.join(); idle(chat)
                        if (commitFirst && category != "admission") {
                            assertTrue("$change/$category", chat.state.value.turns.isEmpty() || category == "window")
                            assertNull(chat.state.value.problem)
                            assertTrue(chat.state.value.draft.isNotEmpty())
                            if (category == "window") assertFalse(chat.state.value.omitted)
                        }
                        failure.get()?.let { throw it }
                        if (change == "read-problem") assertEquals(ProviderSetupProblem.READ_FAILED, store.currentSnapshot.problem)
                        if (change == "revision" || change == "reload") assertNotEquals(before.revision, store.currentSnapshot.revision)
                    } finally { hold.release.countDown(); chat.close(); scope.cancel() }
                }
    }

    @Test fun `failed settings and provider writes preserve common authority and do not cancel foreign work`() = runBlocking {
        val settingsFile = Memory(); val settings = SettingsStore(settingsFile).apply { update { it.copy(assistantEnabled = true) } }
        val file = Memory(); val store = AssistantProviderStore(file, box, settings.assistantChat)
        store.save(AssistantProviderDraft(AssistantProviderSettings(model = "m"), null, "synthetic"))
        val before = store.currentSnapshot; val availability = settings.assistantAvailability
        settingsFile.fail = true; file.fail = true
        assertThrows(IllegalStateException::class.java) { settings.update { it.copy(assistantEnabled = false) } }
        assertThrows(ProviderSetupException::class.java) { store.save(AssistantProviderDraft(AssistantProviderSettings(model = "next"), before.revision)) }
        assertEquals(before, store.currentSnapshot); assertEquals(availability, settings.assistantAvailability)
    }
}
