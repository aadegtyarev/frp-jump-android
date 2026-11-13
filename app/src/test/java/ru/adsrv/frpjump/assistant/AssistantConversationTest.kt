package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.assistant.chat.*
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.settings.*
import ru.adsrv.frpjump.store.*

class AssistantConversationTest {
    private class Memory : TextStorage {
        var text: String? = null; var fail = false; var writes = 0
        override fun read() = text
        override fun write(text: String) { if (fail) error("private-storage"); writes++; this.text = text }
        override fun delete() { text = null }
    }
    private class Box : SecretBox {
        var opens = 0
        override fun seal(plain: String) = Base64.getEncoder().encodeToString(plain.toByteArray())
        override fun open(sealed: String): String { opens++; return String(Base64.getDecoder().decode(sealed)) }
        override fun destroy() = Unit
    }
    private fun windowLimits() = AgentLimits(contextTokens = Utf8TokenEstimate.count(
        AgentRequest(TestPrompts.real.system, emptyList(), emptyList(), 100)).toInt() + 450, outputReserveTokens = 100)
    private class Fixture(
        val provider: AgentProvider = AgentProvider { AgentReply("answer") },
        val io: CoroutineDispatcher = Dispatchers.Unconfined,
        val prepareGate: (() -> Unit)? = null,
        val limit: AgentLimits = AgentLimits(),
    ) : AutoCloseable {
        val settingsFile = Memory()
        val settings = SettingsStore(settingsFile).apply { update { it.copy(assistantEnabled = true) } }
        val file = Memory(); val box = Box(); val store = AssistantProviderStore(file, box)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var preparations = 0
        /** Every end the conversation told (the notification's): true for a reply, false for a failure. */
        val ends = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        init { store.save(AssistantProviderDraft(AssistantProviderSettings(model = "m"), null, "synthetic")) }
        val module = bindAssistantLifecycle(settings, scope)
        val chat = AssistantConversation(scope, { settings.assistantAvailability }, { store.state.value }, {
            preparations++; prepareGate?.invoke(); provider
        }, store::requireRevision, module, io, { limit }, prompts = TestPrompts.lazy, confirmations = TestConfirmations.off, onEnded = { ends += it })
        suspend fun idle() = withTimeout(3000) { chat.state.first { !it.busy } }
        suspend fun send(text: String = "question"): ChatState { chat.edit(text); chat.send(); return idle() }
        fun revise() = store.save(AssistantProviderDraft(AssistantProviderSettings(model = "new"), store.state.value.revision))
        override fun close() { chat.close(); scope.cancel() }
    }

    @Test fun `open is inert and two turns reuse provider and successful context only`() = runBlocking {
        val requests = mutableListOf<AgentRequest>()
        val opaque = object : AgentContinuation {
            override val estimatedTokens = 1L; override val sizeChars = 1
            override fun toString() = "<opaque>"
        }
        Fixture(AgentProvider { requests += it; AgentReply("complete", continuation = opaque) }).use { f ->
            assertEquals(0, f.preparations); assertEquals(0, f.box.opens)
            f.send("one"); f.send("two")
            assertEquals(1, f.preparations); assertEquals(2, requests.size)
            assertSame(opaque, requests[1].turns[0].exchanges.single().reply.continuation)
            assertEquals(listOf("one", "two"), requests[1].turns.map { it.user })
            assertTrue(requests.all { it.tools.isEmpty() && it.instructions == TestPrompts.real.system })
            assertEquals(listOf(ChatTurn("one", "complete"), ChatTurn("two", "complete")), f.chat.state.value.turns)
            assertFalse(f.chat.state.value.toString().contains("opaque"))
            assertFalse(f.file.text!!.contains("one")); assertFalse(f.settingsFile.text!!.contains("one"))
        }
    }

    @Test fun `busy includes blocking preparation and Stop holds slot until its exit`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture(io = Dispatchers.IO, prepareGate = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }).use { f ->
            f.chat.edit("recover"); f.chat.send()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            f.chat.send(); assertEquals(1, f.preparations)
            f.chat.stop(); assertTrue(f.chat.state.value.stopping); assertTrue(f.chat.state.value.busy)
            f.chat.send(); assertEquals(1, f.preparations)
            release.countDown(); f.idle()
            assertEquals("recover", f.chat.state.value.draft); assertTrue(f.chat.state.value.turns.isEmpty())
            f.send(); assertEquals(1, f.chat.state.value.turns.size)
        }
    }

    @Test fun `Stop cancels genuine suspended provider and permits explicit retry without failed context`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>(); var calls = 0
        val requests = mutableListOf<AgentRequest>()
        Fixture(AgentProvider {
            requests += it; calls++
            if (calls == 1) try { entered.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) }
            AgentReply("recovered")
        }).use { f ->
            f.chat.edit("retry"); f.chat.send(); entered.await(); f.chat.stop(); cancelled.await(); f.idle()
            assertEquals("retry", f.chat.state.value.draft)
            f.chat.send(); f.idle(); assertEquals(1, requests.last().turns.size)
            assertEquals("recovered", f.chat.state.value.turns.single().answer)
        }
    }

    @Test fun `noncooperative late answer and error cannot publish after Stop disable off-on revision or a forced close`() = runBlocking {
        for (action in listOf("stop", "disable", "revision", "force-close")) for (throws in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            Fixture(AgentProvider {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                if (throws) error("private-provider-error") else AgentReply("late-private-answer")
            }).use { f ->
                f.chat.edit("recover"); f.chat.send(); entered.await()
                when (action) {
                    "stop" -> f.chat.stop()
                    "disable" -> { f.settings.update { it.copy(assistantEnabled = false) }; f.settings.update { it.copy(assistantEnabled = true) } }
                    "revision" -> f.revise()
                    "force-close" -> f.chat.close()
                }
                // Intentionally no conversation observer: publication/admission must check current authority.
                release.complete(Unit); f.idle()
                assertTrue("$action/$throws", f.chat.state.value.turns.isEmpty())
                assertNull(f.chat.state.value.problem)
                assertFalse(f.chat.state.value.busy)
                assertEquals("$action/$throws: no end is told for an attempt that was stopped, disabled or revoked", emptyList<Boolean>(), f.ends.toList())
            }
        }
    }

    @Test fun `a reply and a failure each tell their end once`() = runBlocking {
        Fixture(AgentProvider { AgentReply("answer") }).use { f -> f.send(); assertEquals(listOf(true), f.ends.toList()) }
        Fixture(AgentProvider { error("synthetic") }).use { f -> f.send(); assertEquals(listOf(false), f.ends.toList()) }
    }

    @Test fun `committed revision and disable clear completed context while failed writes retain it`() = runBlocking {
        val requests = mutableListOf<AgentRequest>()
        Fixture(AgentProvider { requests += it; AgentReply("answer") }).use { f ->
            f.send("one")
            f.settingsFile.fail = true
            assertThrows(IllegalStateException::class.java) { f.settings.update { it.copy(assistantEnabled = false) } }
            f.file.fail = true
            assertThrows(ProviderSetupException::class.java) { f.revise() }
            f.send("two"); assertEquals(2, requests.last().turns.size); assertEquals(1, f.preparations)
            f.file.fail = false; f.revise(); f.send("three")
            assertEquals(1, requests.last().turns.size); assertEquals(2, f.preparations)
            f.settingsFile.fail = false
            f.settings.update { it.copy(assistantEnabled = false) }; f.settings.update { it.copy(assistantEnabled = true) }
            f.send("four"); assertEquals(1, requests.last().turns.size); assertEquals(3, f.preparations)
        }
    }

    @Test fun `timeout invalid empty and excessive replies are fixed recoverable failures without extra calls`() = runBlocking {
        val variants = listOf<Pair<ChatProblem, suspend () -> AgentReply>>(
            ChatProblem.TIMEOUT to { awaitCancellation() },
            ChatProblem.INVALID_REPLY to { AgentReply("") },
            ChatProblem.INVALID_REPLY to { AgentReply("x".repeat(32_001)) },
            ChatProblem.PROVIDER to { error("secret exception") },
        )
        for ((expected, reply) in variants) {
            var calls = 0
            Fixture(AgentProvider { calls++; reply() }, limit = AgentLimits(modelTimeoutMs = 20)).use { f ->
                val state = f.send("kept")
                assertEquals(expected, state.problem); assertEquals("kept", state.draft)
                assertTrue(state.turns.isEmpty()); assertEquals(1, calls)
                assertFalse(state.toString().contains("secret exception"))
            }
        }
    }

    @Test fun `context input turn and window bounds preserve whole turns and signal omissions`() = runBlocking {
        val requests = mutableListOf<AgentRequest>()
        Fixture(AgentProvider { requests += it; AgentReply("answer") }).use { f ->
            f.chat.edit("kept"); f.chat.edit("x".repeat(8001))
            assertEquals("kept", f.chat.state.value.draft); assertEquals(ChatProblem.TOO_LONG, f.chat.state.value.problem)
            f.chat.edit("x".repeat(8000)); assertEquals(8000, f.chat.state.value.draft.length)
            repeat(34) { f.send("turn $it") }
            assertEquals(32, f.chat.state.value.turns.size); assertEquals("turn 2", f.chat.state.value.turns.first().user)
            assertTrue(f.chat.state.value.omitted)
        }
        Fixture(AgentProvider { requests += it; AgentReply("answer".repeat(20)) }, limit = windowLimits()).use { f ->
            f.send("first".repeat(20)); f.send("second".repeat(20)); f.send("third".repeat(20))
            assertTrue(f.chat.state.value.omitted); assertTrue(requests.last().turns.size < 3)
            val calls = requests.size; val state = f.send("x".repeat(8000))
            assertEquals(ChatProblem.CONTEXT_FULL, state.problem); assertEquals(calls, requests.size)
            assertEquals(8000, state.draft.length)
        }
    }

    @Test fun `omission is visible even when an omitted-history request fails`() = runBlocking {
        var calls = 0
        Fixture(AgentProvider { calls++; if (calls == 3) error("fixed failure") else AgentReply("answer".repeat(20)) },
            limit = windowLimits()).use { f ->
            f.send("first".repeat(20)); f.send("second".repeat(20)); val state = f.send("third".repeat(20))
            assertTrue(state.omitted); assertEquals(ChatProblem.PROVIDER, state.problem)
        }
    }

    @Test fun `pre-dispatch cancellation releases slot and a forced close discards all memory`() = runBlocking {
        Fixture().use { f ->
            f.send(); f.chat.edit("draft"); f.chat.close()
            assertEquals(ChatState(), f.chat.state.value); f.revise(); f.chat.refresh(); f.chat.stop(); f.chat.send(); assertEquals(ChatState(), f.chat.state.value)
        }
        val queued = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() { override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued += block } }
        val scope = CoroutineScope(Job() + dispatcher)
        val chat = AssistantConversation(scope, { AssistantAvailability(true) }, { AssistantProviderSnapshot(AssistantProviderSettings(model="m"), "r") }, { AgentProvider { AgentReply("a") } }, {}, AgentModule(true), prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
        chat.edit("q"); chat.send(); chat.stop(); queued.toList().forEach { it.run() }
        assertFalse(chat.state.value.busy); assertEquals("q", chat.state.value.draft); scope.cancel()
    }

    @Test fun `publication rechecks authority after kernel return and old completion cannot affect a new conversation`() = runBlocking {
        for (change in listOf("disable", "revision", "stop")) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true) } }
            var saved = AssistantProviderSnapshot(AssistantProviderSettings(model = "m"), "one")
            var checks = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val module = bindAssistantLifecycle(settings, scope)
            val chat = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, { AgentProvider { AgentReply("late") } }, {
                checks++
                if (checks == 2) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            }, module, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
            try {
                chat.edit("keep"); chat.send(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                when (change) {
                    "disable" -> { settings.update { it.copy(assistantEnabled = false) }; settings.update { it.copy(assistantEnabled = true) } }
                    "revision" -> saved = saved.copy(revision = "two")
                    "stop" -> chat.stop()
                }
                release.countDown(); withTimeout(3000) { chat.state.first { !it.busy } }
                assertTrue(chat.state.value.turns.isEmpty()); assertNull(chat.state.value.problem)
                chat.edit("new"); chat.send(); withTimeout(3000) { chat.state.first { !it.busy } }
                assertEquals("new", chat.state.value.turns.single().user)
            } finally { release.countDown(); chat.close(); scope.cancel() }
        }
    }

    @Test fun `disabled absent unreadable and crypto failures never leak diagnostics or discard draft`() = runBlocking {
        for (problem in listOf(null, ProviderSetupProblem.READ_FAILED, ProviderSetupProblem.CRYPTO_FAILED)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var preparations = 0
            val saved = AssistantProviderSnapshot(if (problem == ProviderSetupProblem.READ_FAILED) null else AssistantProviderSettings(model = "m"), "r", problem.takeIf { it == ProviderSetupProblem.READ_FAILED })
            val chat = AssistantConversation(scope, { AssistantAvailability(true) }, { saved }, {
                preparations++; throw ProviderSetupException(problem ?: ProviderSetupProblem.KEY_REQUIRED)
            }, {}, AgentModule(true), Dispatchers.Unconfined, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
            chat.edit("kept"); chat.send()
            assertEquals(if (problem == ProviderSetupProblem.CRYPTO_FAILED) ChatProblem.KEY else if (problem == ProviderSetupProblem.READ_FAILED) ChatProblem.READ else ChatProblem.SETUP, chat.state.value.problem)
            assertEquals("kept", chat.state.value.draft); assertEquals(if (problem == ProviderSetupProblem.READ_FAILED) 0 else 1, preparations)
            chat.close(); scope.cancel()
        }
        for (enabled in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val chat = AssistantConversation(scope, { AssistantAvailability(enabled) }, { AssistantProviderSnapshot() }, { error("must not prepare") }, {}, AgentModule(true), prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
            chat.edit("kept"); chat.send(); assertEquals(ChatProblem.SETUP, chat.state.value.problem); chat.close(); scope.cancel()
        }
    }

    @Test fun `real owner Stop cancels adapter HTTP socket while preserving retry input and unrelated work`() = runBlocking {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val received = CountDownLatch(1); val disconnected = CountDownLatch(1)
        val thread = Thread {
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val input = socket.getInputStream().bufferedReader()
                var length = 0
                while (true) {
                    val line = input.readLine()
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                var left = length
                while (left > 0) { val n = input.read(CharArray(left)); if (n < 0) break; left -= n }
                received.countDown()
                if (input.read() == -1) disconnected.countDown()
            }
        }.apply { isDaemon = true; start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val unrelated = scope.launch { awaitCancellation() }
        try {
            val store = AssistantProviderStore(Memory(), Box())
            store.save(AssistantProviderDraft(AssistantProviderSettings("http://127.0.0.1:${server.localPort}/v1", "m"), null, "synthetic"))
            val chat = AssistantConversation(scope, { AssistantAvailability(true) }, { store.state.value }, {
                store.provider(AssistantProviderDraft(it.settings!!, it.revision))
            }, store::requireRevision, AgentModule(true), prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
            chat.edit("retry HTTP"); chat.send(); assertTrue(received.await(2, TimeUnit.SECONDS))
            chat.stop(); withTimeout(3000) { chat.state.first { !it.busy } }
            assertTrue(disconnected.await(2, TimeUnit.SECONDS)); assertEquals("retry HTTP", chat.state.value.draft)
            assertTrue(chat.state.value.turns.isEmpty()); assertTrue(unrelated.isActive); chat.close()
        } finally { scope.cancel(); server.close(); thread.join(1000) }
    }

    @Test fun `real adapter header and body timeouts both produce fixed recoverable chat timeout`() = runBlocking {
        listOf(false, true).map { partialBody -> async(Dispatchers.Default) {
            val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val received = CountDownLatch(1)
            val thread = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 35000
                    val input = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine()
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    var left = length
                    while (left > 0) { val n = input.read(CharArray(left)); if (n < 0) break; left -= n }
                    if (partialBody) socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n{".toByteArray()); flush()
                    }
                    received.countDown()
                    input.read() // Exact adapter timeout closes the socket, not a test sleep.
                }
            }.apply { isDaemon = true; start() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val store = AssistantProviderStore(Memory(), Box())
                store.save(AssistantProviderDraft(AssistantProviderSettings("http://127.0.0.1:${server.localPort}/v1", "m"), null, "synthetic"))
                val chat = AssistantConversation(scope, { AssistantAvailability(true) }, { store.state.value }, {
                    store.provider(AssistantProviderDraft(it.settings!!, it.revision))
                }, store::requireRevision, AgentModule(true), prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
                chat.edit("retry after timeout"); chat.send(); assertTrue(received.await(2, TimeUnit.SECONDS))
                val state = withTimeout(35000) { chat.state.first { !it.busy } }
                assertEquals("partialBody=$partialBody", ChatProblem.TIMEOUT, state.problem)
                assertEquals("retry after timeout", state.draft); assertTrue(state.turns.isEmpty()); chat.close()
            } finally { scope.cancel(); server.close(); thread.join(1000) }
        } }.awaitAll()
        Unit
    }

    @Test fun `real saved store to adapter loopback retains opaque provider identity and exact two turn payload`() = runBlocking {
        val seen = java.util.concurrent.LinkedBlockingQueue<String>()
        val server = ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            repeat(2) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine()
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length)
                    var read = 0
                    while (read < length) read += input.read(body, read, length - read)
                    seen.add(String(body))
                    val response = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"complete answer","reasoning_content":"synthetic-opaque"}}]}""".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(response); flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val file = Memory(); val box = Box(); val store = AssistantProviderStore(file, box)
            store.save(AssistantProviderDraft(AssistantProviderSettings("http://127.0.0.1:${server.localPort}/v1", "synthetic", "high", 4096, 512, true), null, "synthetic-key"))
            val writes = file.writes
            val chat = AssistantConversation(scope, { AssistantAvailability(true) }, { store.state.value }, {
                store.provider(AssistantProviderDraft(it.settings!!, it.revision))
            }, store::requireRevision, AgentModule(true), prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
            assertEquals(0, box.opens); assertTrue(seen.isEmpty())
            repeat(2) { chat.edit("message-$it"); chat.send(); withTimeout(3000) { chat.state.first { !it.busy } } }
            assertEquals(1, box.opens); assertEquals(writes, file.writes)
            assertEquals(2, seen.size)
            val first = Json.parseToJsonElement(seen.remove()).jsonObject
            val second = Json.parseToJsonElement(seen.remove()).jsonObject
            assertEquals(512, second["max_completion_tokens"]!!.jsonPrimitive.int)
            assertEquals("high", second["reasoning_effort"]!!.jsonPrimitive.content)
            assertNull(second["tools"]); assertFalse(second.toString().contains("synthetic-key"))
            assertEquals(2, first["messages"]!!.jsonArray.size)
            val messages = second["messages"]!!.jsonArray
            assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals("synthetic-opaque", messages[2].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
            assertFalse(chat.state.value.toString().contains("synthetic-opaque"))
            chat.close()
        } finally { scope.cancel(); server.close(); thread.join(1000) }
    }
    @Test fun `unknown and disabled proposals return bounded results then continue without public requests`() = runBlocking {
        for (name in listOf("unknown", "web_search", "read_page", "inspect_image")) {
            var calls = 0
            Fixture(AgentProvider { request ->
                if (++calls == 1) AgentReply(calls = listOf(ToolCall("one", name, buildJsonObject {})))
                else {
                    assertEquals(ToolOutcome.UNKNOWN_TOOL, request.turns.last().exchanges.single().results.single().outcome)
                    assertTrue(request.tools.isEmpty()); AgentReply("Tool unavailable; answering from text")
                }
            }).use { f ->
                val state = f.send(); assertNull(state.problem); assertTrue(state.turns.single().toolFailed)
                assertEquals(2, calls); assertTrue(state.turns.single().sources.isEmpty())
            }
        }
    }

    @Test fun `tool off-on allows current completion and preserves history and prepared provider`() = runBlocking {
        var calls = 0; val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        Fixture(AgentProvider {
            calls++
            if (calls == 2) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            AgentReply("answer $calls")
        }).use { f ->
            f.send("complete")
            f.chat.edit("pending"); f.chat.send(); entered.await()
            f.settings.update { it.copy(assistantSearch = true) }; f.settings.update { it.copy(assistantSearch = false) }
            release.complete(Unit); f.idle()
            assertEquals(listOf("complete", "pending"), f.chat.state.value.turns.map { it.user })
            assertEquals("", f.chat.state.value.draft); assertNull(f.chat.state.value.problem)
            f.send("next"); assertEquals(3, f.chat.state.value.turns.size)
            assertEquals(1, f.preparations)
        }
    }

    @Test fun `aggregate retained history evicts complete turns visibly before the turn count limit`() = runBlocking {
        Fixture(AgentProvider { AgentReply("x".repeat(30_000)) }, limit = AgentLimits(contextTokens = 1_000_000, outputReserveTokens = 100)).use { f ->
            repeat(20) { f.send("q$it") }
            val state = f.chat.state.value
            assertEquals(17, state.turns.size); assertEquals("q3", state.turns.first().user)
            assertTrue(state.omitted); assertTrue(state.turns.all { it.answer.length == 30_000 })
        }
    }

}
