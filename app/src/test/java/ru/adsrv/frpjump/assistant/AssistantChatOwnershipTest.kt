package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.assistant.chat.*
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
        for (phase in listOf("prep", "http", "post")) for (action in listOf("force-close", "stop", "disable", "revision")) for (failure in listOf(false, true)) {
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
            }, AgentModule(true), runtime = settings.assistantChat, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
            val newer = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, {
                preparations++; AgentProvider { AgentReply("new answer") }
            }, store::requireRevision, AgentModule(true), runtime = settings.assistantChat, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
            try {
                old.edit("old private input"); old.send(); hold.entered()
                if (phase == "http") {
                    println("HTTP fixture $action/$failure blocking body holds Chat guard: ${httpUnderGuard.get()}")
                    assertFalse("$phase/$action/$failure fake HTTP must suspend before blocking", httpUnderGuard.get())
                }
                when (action) {
                    "force-close" -> old.close()
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

    @Test fun `transport exit keeps app slot across completion cancellation and authority changes`() = runBlocking {
        for (action in listOf("success", "error", "stop", "force-close", "disable", "revision", "timeout")) {
            val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true) } }
            val store = AssistantProviderStore(Memory(), box, settings.assistantChat)
            store.save(AssistantProviderDraft(AssistantProviderSettings(model = "m"), null, "synthetic"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val unrelated = scope.launch { awaitCancellation() }
            val inside = Gate(); val cancelled = CountDownLatch(1)
            // The fake transport is the real primitive: the thread stays inside the blocking exchange until released.
            val old = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, { AgentProvider {
                blockingCancellable({ cancelled.countDown() }) { inside.waitHere() }
                if (action == "error") throw ProviderException(ProviderErrorKind.NETWORK, null, null)
                AgentReply("old answer")
            } }, store::requireRevision, AgentModule(true), limits = { AgentLimits(modelTimeoutMs = if (action == "timeout") 100 else 5000) }, runtime = settings.assistantChat, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
            var preparations = 0
            val newer = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, {
                preparations++; AgentProvider { AgentReply("new answer") }
            }, store::requireRevision, AgentModule(true), runtime = settings.assistantChat, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
            try {
                old.edit("old private input"); old.send(); inside.entered()
                when (action) {
                    "stop" -> old.stop()
                    "force-close" -> old.close()
                    "disable" -> { settings.update { it.copy(assistantEnabled = false) }; settings.update { it.copy(assistantEnabled = true) }; old.refresh() }
                    "revision" -> { store.save(AssistantProviderDraft(AssistantProviderSettings(model = "next"), store.currentSnapshot.revision)); old.refresh() }
                }
                if (action in listOf("success", "error")) assertEquals("$action must not cancel the exchange", 1L, cancelled.count)
                else assertTrue("$action must cancel the exact exchange promptly", cancelled.await(2, TimeUnit.SECONDS))
                // Negative observation: an owner that frees the slot before the blocking exchange returned would do so well inside this window.
                assertNull("$action must keep the slot while the exchange is held", withTimeoutOrNull(300) { settings.assistantChat.busy.first { !it } })
                newer.edit("new input"); newer.send()
                assertEquals("$action must not start replacement", 0, preparations)
                assertTrue("$action must retain slot", settings.assistantChat.busy.value)
                assertTrue(newer.state.value.busy); assertNull(newer.state.value.pendingText)
                assertTrue(newer.state.value.turns.isEmpty()); assertTrue(unrelated.isActive)
                inside.release.countDown()
                withTimeout(4000) { settings.assistantChat.busy.first { !it } }
                assertEquals("no queued send", 0, preparations)
                newer.send(); idle(newer)
                assertEquals(1, preparations); assertEquals("new answer", newer.state.value.turns.single().answer)
            } finally { inside.release.countDown(); old.close(); newer.close(); scope.cancel() }
        }
    }

    /** One loopback HTTP/1.1 connection; [action] picks the reply, or a stall before the headers or in the middle of the body. */
    private class Loopback(private val action: String) : AutoCloseable {
        val server = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val received = CountDownLatch(1); val release = CountDownLatch(1)
        val url: String get() = "http://127.0.0.1:${server.localPort}/v1"
        private val thread = Thread {
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
                    received.countDown()
                    val out = socket.getOutputStream()
                    when (action) {
                        "network" -> Unit
                        "body-stop" -> { out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\nConnection: close\r\n\r\n{\"choices\"".toByteArray()); out.flush(); release.await(5, TimeUnit.SECONDS) }
                        "success", "error", "invalid", "oversize" -> {
                            val text = if (action == "error") "failure" else if (action == "invalid") "not-json" else """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"old answer"}}]}"""
                            val bytes = text.toByteArray()
                            out.write("HTTP/1.1 ${if (action == "error") "500 Error" else "200 OK"}\r\nContent-Length: ${if (action == "oversize") 1_048_577 else bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(bytes); out.flush()
                        }
                        else -> release.await(5, TimeUnit.SECONDS)
                    }
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        override fun close() { release.countDown(); server.close(); thread.join(2000) }
    }

    @Test fun `real adapter outcomes and cancellation release the slot only after exit`() = runBlocking {
        val outcomes = mapOf("success" to null, "error" to ChatProblem.PROVIDER, "invalid" to ChatProblem.INVALID_REPLY,
            "oversize" to ChatProblem.INVALID_REPLY, "network" to ChatProblem.PROVIDER, "timeout" to ChatProblem.TIMEOUT)
        for (action in listOf("success", "error", "invalid", "oversize", "network", "timeout", "stop", "body-stop", "force-close", "disable", "revision")) Loopback(action).use { loop ->
            val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true) } }
            val store = AssistantProviderStore(Memory(), box, settings.assistantChat)
            store.save(AssistantProviderDraft(AssistantProviderSettings(loop.url, "m"), null, "synthetic"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val unrelated = scope.launch { awaitCancellation() }
            val adapter = ru.adsrv.frpjump.agent.openai.OpenAiAgentProvider(ru.adsrv.frpjump.agent.openai.OpenAiConfig(loop.url, "m"), "synthetic")
            val old = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, { adapter },
                store::requireRevision, AgentModule(true), limits = { AgentLimits(modelTimeoutMs = if (action == "timeout") 100 else 5000) }, runtime = settings.assistantChat, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
            var preparations = 0
            val newer = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, {
                preparations++; AgentProvider { AgentReply("new answer") }
            }, store::requireRevision, AgentModule(true), runtime = settings.assistantChat, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
            try {
                old.edit("old private input"); old.send()
                assertTrue("request $action", loop.received.await(4, TimeUnit.SECONDS))
                when (action) {
                    "stop", "body-stop" -> old.stop()
                    "force-close" -> old.close()
                    "disable" -> { settings.update { it.copy(assistantEnabled = false) }; settings.update { it.copy(assistantEnabled = true) }; old.refresh() }
                    "revision" -> { store.save(AssistantProviderDraft(AssistantProviderSettings(loop.url, "next"), store.currentSnapshot.revision)); old.refresh() }
                }
                // The server never releases a stalled exchange: the slot is free only because Call.cancel() ended it, promptly.
                withTimeout(4000) { settings.assistantChat.busy.first { !it } }
                if (action in outcomes) {
                    idle(old)
                    assertEquals(action, outcomes.getValue(action), old.state.value.problem)
                    if (action == "success") assertEquals("old answer", old.state.value.turns.single().answer)
                }
                assertEquals("$action must not start a queued replacement", 0, preparations)
                newer.edit("new input"); newer.send(); idle(newer)
                assertEquals(1, preparations); assertEquals("new answer", newer.state.value.turns.single().answer); assertTrue(unrelated.isActive)
            } finally { loop.release.countDown(); old.close(); newer.close(); scope.cancel() }
        }
    }

    @Test fun `after a committed disable no second model request reaches the server`() = runBlocking {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val first = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"read_page","arguments":"{\"url\":\"https://public.org/doc\"}"}}]}}]}"""
        val server = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val acceptor = Thread {
            try {
                while (true) {
                    val socket = server.accept()
                    Thread {
                        try {
                            socket.use {
                                val input = it.getInputStream().bufferedReader()
                                var length = 0
                                while (true) {
                                    val line = input.readLine() ?: break
                                    if (line.isEmpty()) break
                                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                                }
                                var left = length
                                while (left > 0) { val n = input.read(CharArray(left)); if (n < 0) break; left -= n }
                                val bytes = (if (requests.incrementAndGet() == 1) first else """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"late"}}]}""").toByteArray()
                                it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
                            }
                        } catch (_: java.io.IOException) { }
                    }.apply { isDaemon = true; start() }
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        val url = "http://127.0.0.1:${server.localPort}/v1"
        val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true, assistantReadPage = true) } }
        settings.assistantChat.publicWeb = PublicFetcher { u, _, attempt ->
            admitSideEffect("public-dispatch"); attempt("public-dispatch"); WebResponse(u, "text/plain", "Useful documentation text.".toByteArray(), 200, null)
        }
        val store = AssistantProviderStore(Memory(), box, settings.assistantChat)
        store.save(AssistantProviderDraft(AssistantProviderSettings(url, "m"), null, "synthetic"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = ru.adsrv.frpjump.agent.openai.OpenAiAgentProvider(ru.adsrv.frpjump.agent.openai.OpenAiConfig(url, "m"), "synthetic")
        val chat = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, { adapter }, store::requireRevision,
            AgentModule(true), runtime = settings.assistantChat, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
        val hold = Gate(); val dispatches = java.util.concurrent.atomic.AtomicInteger()
        // The second model request is held just before its start check; the disable commits first, so the check must refuse it.
        settings.assistantChat.probes = ChatProbes(before = { category -> if (category == "dispatch" && dispatches.incrementAndGet() == 2) hold.waitHere() })
        try {
            chat.edit("q"); chat.send(); hold.entered()
            assertEquals(1, requests.get())
            settings.update { it.copy(assistantEnabled = false) }
            hold.release.countDown()
            idle(chat)
            assertEquals(1, requests.get()); assertEquals(2, dispatches.get())
            assertTrue(chat.state.value.turns.isEmpty())
        } finally { hold.release.countDown(); chat.close(); scope.cancel(); server.close(); acceptor.join(2000) }
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
                    }, store::requireRevision, AgentModule(true), limits = { AgentLimits(contextTokens = Utf8TokenEstimate.count(AgentRequest(TestPrompts.real.system,
                        listOf(requestToolSpec), emptyList(), 100)).toInt() + 450, outputReserveTokens = 100) }, runtime = settings.assistantChat, prompts = TestPrompts.lazy, confirmations = TestConfirmations.off)
                    if (category == "window") { chat.edit("x".repeat(100)); chat.send(); idle(chat) }
                    triggerSetup = category == "setup-error"
                    val hold = Gate()
                    val once = AtomicBoolean()
                    val probe: (String) -> Unit = { point -> if (point == category && once.compareAndSet(false, true)) hold.waitHere() }
                    settings.assistantChat.probes = if (commitFirst) ChatProbes(before = probe) else ChatProbes(checked = probe)
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
