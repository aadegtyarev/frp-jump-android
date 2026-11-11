package ru.adsrv.frpjump.assistant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.store.TextStorage

class AssistantWebOwnershipTest {
    private class Memory : TextStorage {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
        override fun delete() { text = null }
    }
    private val saved = AssistantProviderSnapshot(AssistantProviderSettings(model = "m"), "r")
    private suspend fun idle(chat: AssistantConversation) = withTimeout(4000) { chat.state.first { !it.busy } }
    private fun reply(id: String) = AgentReply(calls = listOf(ToolCall(id, "read_page", buildJsonObject { put("url", "https://public.org/doc") })))
    private fun document() = WebResponse("https://public.org/doc", "text/html", "<title>Fetched guide</title><p>Static documentation with enough useful content for the reader.</p>".toByteArray(), 200, null)

    @Test fun `all three tool changes preserve admitted public start progress source in both orders`() = runBlocking {
        for (tool in listOf("web_search", "read_page", "inspect_image")) for (category in listOf("tool-start", "public-dispatch", "source", "progress")) for (commitFirst in listOf(false, true)) {
            val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true, assistantReadPage = true) } }
            var fetches = 0; var calls = 0
            settings.assistantChat.publicWeb = PublicFetcher { _, _, attempt -> admitSideEffect("public-dispatch"); attempt("public-dispatch"); fetches++; document() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val chat = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, {
                AgentProvider { if (++calls == 1) reply("call") else AgentReply("complete") }
            }, {}, AgentModule(true), runtime = settings.assistantChat)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val once = AtomicBoolean()
            val probe: (String) -> Unit = { point -> if (point == category && once.compareAndSet(false, true)) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } }
            if (commitFirst) settings.assistantChat.beforeAction = probe else settings.assistantChat.checkedAction = probe
            val committed = CountDownLatch(1)
            val commit = Thread {
                settings.setAssistantTool(AssistantToolId.fromWire(tool)!!, true); settings.setAssistantTool(AssistantToolId.fromWire(tool)!!, false); committed.countDown()
            }
            try {
                chat.edit("q"); chat.send(); assertTrue("$category/$commitFirst", entered.await(2, TimeUnit.SECONDS))
                commit.start()
                if (commitFirst) assertTrue(committed.await(2, TimeUnit.SECONDS))
                else {
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (commit.state != Thread.State.BLOCKED && commit.isAlive && System.nanoTime() < until) Thread.yield()
                    assertEquals(Thread.State.BLOCKED, commit.state)
                }
                release.countDown(); commit.join(2000); idle(chat)
                assertEquals("complete", chat.state.value.turns.single().answer); assertNull(chat.state.value.problem)
                assertEquals(1, fetches)
            } finally { release.countDown(); chat.close(); scope.cancel(); commit.join(2000) }
        }
    }

    @Test fun `all three start tools remain fixed after disabling and next Send uses committed booleans`() = runBlocking {
        for (tool in listOf("web_search", "read_page", "inspect_image")) {
            val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true, assistantSearch = true, assistantReadPage = true, assistantInspectImage = true) } }
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val requests = mutableListOf<AgentRequest>(); var fetches = 0
            settings.assistantChat.publicWeb = PublicFetcher { _, _, attempt -> admitSideEffect("public-dispatch"); attempt("public-dispatch"); fetches++; document() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val chat = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, { AgentProvider { request ->
                requests += request
                if (requests.size == 1) { entered.complete(Unit); release.await(); reply("after-toggle") }
                else AgentReply("complete")
            } }, {}, AgentModule(true), runtime = settings.assistantChat)
            try {
                chat.edit("current"); chat.send(); entered.await()
                settings.setAssistantTool(AssistantToolId.fromWire(tool)!!, false); chat.refresh()
                assertTrue(chat.state.value.busy); assertFalse(chat.state.value.stopping)
                release.complete(Unit); idle(chat)
                assertEquals(1, fetches); assertEquals("current", chat.state.value.turns.single().user)
                assertEquals("", chat.state.value.draft)
                assertTrue(requests.take(2).all { it.tools.map { spec -> spec.name } == listOf("web_search", "read_page", "inspect_image") })
                chat.edit("next"); chat.send(); idle(chat)
                assertEquals(listOf("web_search", "read_page", "inspect_image").filter { it != tool }, requests.last().tools.map { it.name })
                assertEquals(2, requests.last().turns.size); assertEquals(2, chat.state.value.turns.size)
            } finally { release.complete(Unit); chat.close(); scope.cancel() }
        }
    }

    @Test fun `public cleanup spans pop reopen and revoked source cannot be published`() = runBlocking {
        val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true, assistantReadPage = true) } }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        settings.assistantChat.publicWeb = PublicFetcher { _, _, attempt ->
            admitSideEffect("public-dispatch"); attempt("public-dispatch"); entered.complete(Unit)
            withContext(NonCancellable) { release.await() }; document()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val old = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, { AgentProvider { calls++; reply("old") } }, {}, AgentModule(true), runtime = settings.assistantChat)
        var replacements = 0
        val newer = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, { AgentProvider { replacements++; AgentReply("new") } }, {}, AgentModule(true), runtime = settings.assistantChat)
        try {
            old.edit("private"); old.send(); entered.await(); old.close()
            newer.edit("new"); newer.send(); assertEquals(0, replacements); assertTrue(newer.state.value.busy)
            release.complete(Unit); withTimeout(4000) { settings.assistantChat.busy.first { !it } }
            assertEquals(ChatState(), old.state.value); assertEquals(1, calls)
            newer.send(); idle(newer); assertEquals("new", newer.state.value.turns.single().answer)
        } finally { release.complete(Unit); old.close(); newer.close(); scope.cancel() }
    }

    @Test fun `successful fetched sources follow retained turns and tool results count in aggregate history`() = runBlocking {
        val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true, assistantReadPage = true) } }
        settings.assistantChat.publicWeb = PublicFetcher { _, _, attempt -> admitSideEffect("public-dispatch"); attempt("public-dispatch"); document() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var calls = 0
        val chat = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, {
            AgentProvider { request ->
                if (request.turns.last().exchanges.isEmpty()) reply("call-${++calls}")
                else AgentReply("x".repeat(30_000))
            }
        }, {}, AgentModule(true), limits = { AgentLimits(contextTokens = 1_000_000, outputReserveTokens = 100) }, runtime = settings.assistantChat)
        try {
            repeat(20) { chat.edit("q$it"); chat.send(); idle(chat) }
            val state = chat.state.value
            assertTrue(state.omitted); assertTrue(state.turns.size < 20)
            assertTrue(state.turns.all { it.sources == listOf(ChatSource("Fetched guide", "https://public.org/doc")) })
            assertTrue(state.currentSources.isEmpty())
        } finally { chat.close(); scope.cancel() }
    }
    @Test fun `oversize current tool history fails context full without truncating input or publishing answer`() = runBlocking {
        val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true, assistantReadPage = true) } }
        settings.assistantChat.publicWeb = PublicFetcher { _, _, attempt ->
            admitSideEffect("public-dispatch"); attempt("public-dispatch")
            WebResponse("https://public.org/doc", "text/plain", "x".repeat(8000).toByteArray(), 200, null)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var calls = 0
        val chat = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, { AgentProvider {
            calls++
            AgentReply("x".repeat(30_000), (1..4).map { index -> ToolCall("$calls-$index", "read_page", buildJsonObject { put("url", "https://public.org/doc") }) })
        } }, {}, AgentModule(true), limits = { AgentLimits(contextTokens = 1_000_000, outputReserveTokens = 100) }, runtime = settings.assistantChat)
        try {
            chat.edit("Keep exact input"); chat.send(); idle(chat)
            assertEquals(ChatProblem.CONTEXT_FULL, chat.state.value.problem)
            assertEquals("Keep exact input", chat.state.value.draft); assertTrue(chat.state.value.turns.isEmpty())
            assertTrue(calls < 12)
        } finally { chat.close(); scope.cancel() }
    }

    @Test fun `WEB-AUTH-04 source publication obeys real revocations and no payload is reused by a new owner`() = runBlocking {
        val revocations = listOf("stop", "pop", "revision", "read-problem", "disable", "off-on")
        for (revoke in revocations) for (revokeFirst in listOf(true, false)) {
            val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true, assistantSearch = true, assistantReadPage = true, assistantInspectImage = true) } }
            val file = Memory()
            val box = object : ru.adsrv.frpjump.store.SecretBox {
                override fun seal(plain: String) = plain
                override fun open(sealed: String) = sealed
                override fun destroy() = Unit
            }
            val store = AssistantProviderStore(file, box, settings.assistantChat)
            store.save(AssistantProviderDraft(AssistantProviderSettings(model = "m"), null, "synthetic"))
            val originalFile = file.text
            var fetches = 0
            settings.assistantChat.publicWeb = PublicFetcher { url, _, attempt ->
                admitSideEffect("public-dispatch"); attempt("public-dispatch"); fetches++
                WebResponse(url, "text/html", "<a class=result__a href='https://public.org/doc'>Guide</a>".toByteArray(), 200, null)
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val provider = AgentProvider { request ->
                if (request.turns.last().exchanges.isEmpty()) AgentReply(calls = listOf(ToolCall("search", "web_search", buildJsonObject { put("query", "q") })))
                else AgentReply("done")
            }
            fun chat() = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, { provider }, store::requireRevision,
                AgentModule(true), runtime = settings.assistantChat)
            val old = chat()
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val once = AtomicBoolean()
            val probe: (String) -> Unit = { category -> if (category == "source" && once.compareAndSet(false, true)) {
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            } }
            if (revokeFirst) settings.assistantChat.beforeAction = probe else settings.assistantChat.checkedAction = probe
            val commit = Thread {
                when (revoke) {
                    "stop" -> old.stop()
                    "pop" -> old.close()
                    "revision" -> store.save(AssistantProviderDraft(AssistantProviderSettings(model = "new"), store.currentSnapshot.revision))
                    "read-problem" -> { file.text = "invalid"; store.reload() }
                    "disable", "off-on" -> { settings.update { it.copy(assistantEnabled = false) }; if (revoke == "off-on") settings.update { it.copy(assistantEnabled = true) } }
                }
            }
            try {
                old.edit("q"); old.send(); assertTrue("$revoke/$revokeFirst", entered.await(2, TimeUnit.SECONDS))
                commit.start()
                if (revokeFirst) { commit.join(2000); assertFalse(commit.isAlive) }
                else {
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (commit.state != Thread.State.BLOCKED && commit.isAlive && System.nanoTime() < until) Thread.yield()
                    assertEquals("$revoke", Thread.State.BLOCKED, commit.state)
                }
                release.countDown(); commit.join(2000); idle(old)
                withTimeout(4000) { settings.assistantChat.busy.first { !it } }
                if (revokeFirst) {
                    assertTrue("$revoke late source", old.state.value.currentSources.isEmpty())
                    assertTrue("$revoke late answer", old.state.value.turns.isEmpty())
                }
                assertThrows(WebException::class.java) { settings.assistantChat.webSearch.check(WebBudget()) } // Never reset attempted charges.
                settings.assistantChat.beforeAction = null; settings.assistantChat.checkedAction = null
                if (revoke == "read-problem") { file.text = originalFile; store.reload() }
                settings.update { it.copy(assistantEnabled = true, assistantSearch = true, assistantReadPage = true, assistantInspectImage = true) }
                val next = chat()
                try {
                    next.edit("q"); next.send(); idle(next)
                    assertTrue("$revoke/$revokeFirst", next.state.value.turns.single().sources.isEmpty())
                    assertTrue(next.state.value.turns.single().toolFailed)
                    assertEquals(1, fetches)
                } finally { next.close() }
            } finally { release.countDown(); old.close(); scope.cancel(); commit.join(2000) }
        }
    }

    @Test fun `tool failure detail and generated query survive a missing model reply then clear for next Send`() = runBlocking {
        val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true, assistantSearch = true) } }
        settings.assistantChat.publicWeb = PublicFetcher { url, _, attempt ->
            admitSideEffect("public-dispatch"); attempt("public-dispatch")
            WebResponse(url, "text/html", "<form id='challenge-form'></form>".toByteArray(), 202, null)
        }
        val received = CompletableDeferred<ToolResult>(); val reply = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, { AgentProvider { request ->
            val turn = request.turns.last()
            if (turn.user == "clean") AgentReply("Clean answer")
            else if (turn.exchanges.isEmpty()) AgentReply(calls = listOf(ToolCall("search", "web_search", buildJsonObject { put("query", "generated documentation query") })))
            else { received.complete(turn.exchanges.single().results.single()); reply.await(); error("private provider exception") }
        } }, {}, AgentModule(true), runtime = settings.assistantChat)
        try {
            chat.edit("Find documentation"); chat.send()
            val result = withTimeout(4000) { received.await() }
            assertTrue(chat.state.value.busy)
            val failure = chat.state.value.toolFailures.single()
            assertSame(result, failure.result) // No second output copy or unbounded diagnostic store.
            assertEquals("generated documentation query", failure.query)
            assertTrue(failure.result.text.contains("HTTP 202"))
            reply.complete(Unit); idle(chat)
            assertEquals(ChatProblem.PROVIDER, chat.state.value.problem)
            assertTrue(chat.state.value.turns.isEmpty())
            assertSame(result, chat.state.value.toolFailures.single().result)
            assertFalse(chat.state.value.toolFailures.toString().contains("private provider exception"))
            chat.edit("clean"); chat.send(); idle(chat)
            assertTrue(chat.state.value.toolFailures.isEmpty())
            assertTrue(chat.state.value.turns.single().toolFailures.isEmpty())
        } finally { reply.complete(Unit); chat.close(); scope.cancel() }
    }

    @Test fun `tool failure remains visible when context cannot admit the next model request`() = runBlocking {
        val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true, assistantReadPage = true) } }
        settings.assistantChat.publicWeb = PublicFetcher { _, _, attempt -> admitSideEffect("public-dispatch"); attempt("public-dispatch"); throw WebException(WebFailure.HTTP, 503) }
        var calls = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, { AgentProvider {
            calls++; AgentReply("x".repeat(4000), listOf(ToolCall("read", "read_page", buildJsonObject { put("url", "https://public.org/doc") })))
        } }, {}, AgentModule(true), limits = { AgentLimits(contextTokens = 4096, outputReserveTokens = 100) }, runtime = settings.assistantChat)
        try {
            chat.edit("Read"); chat.send(); idle(chat)
            assertEquals(1, calls)
            assertEquals(ChatProblem.CONTEXT_FULL, chat.state.value.problem)
            assertTrue(chat.state.value.turns.isEmpty())
            assertTrue(chat.state.value.toolFailures.single().result.text.contains("HTTP 503"))
        } finally { chat.close(); scope.cancel() }
    }

    @Test fun `completed tool details share bounded turns and leave with eviction and authority change`() = runBlocking {
        val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true, assistantReadPage = true) } }
        settings.assistantChat.publicWeb = PublicFetcher { _, _, attempt -> admitSideEffect("public-dispatch"); attempt("public-dispatch"); throw WebException(WebFailure.HTTP, 503) }
        var snapshot = saved
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = AssistantConversation(scope, { settings.assistantAvailability }, { snapshot }, { AgentProvider { request ->
            val turn = request.turns.last()
            if (turn.user == "failure" && turn.exchanges.isEmpty()) reply("read") else AgentReply("Available answer")
        } }, {}, AgentModule(true), runtime = settings.assistantChat)
        suspend fun send(text: String) { chat.edit(text); chat.send(); idle(chat) }
        try {
            send("failure")
            assertTrue(chat.state.value.toolFailures.isEmpty())
            assertTrue(chat.state.value.turns.single().toolFailures.single().result.text.contains("HTTP 503"))
            repeat(32) { send("clean-$it") }
            assertTrue(chat.state.value.omitted); assertEquals(32, chat.state.value.turns.size)
            assertTrue(chat.state.value.turns.all { it.toolFailures.isEmpty() })
            send("failure")
            assertTrue(chat.state.value.turns.last().toolFailures.isNotEmpty())
            snapshot = saved.copy(revision = "changed"); chat.refresh()
            assertTrue(chat.state.value.turns.isEmpty()); assertTrue(chat.state.value.toolFailures.isEmpty())
        } finally { chat.close(); scope.cancel() }
    }

    @Test fun `WEB-AUTH-04 revoked blocked search still applies process cooldown`() = runBlocking {
        for (status in listOf(200, 403, 429)) {
            val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true, assistantSearch = true) } }
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            settings.assistantChat.publicWeb = PublicFetcher { url, _, attempt ->
                admitSideEffect("public-dispatch"); attempt("public-dispatch"); entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                WebResponse(url, "text/html", "CAPTCHA".toByteArray(), status, "900")
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val chat = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, { AgentProvider {
                AgentReply(calls = listOf(ToolCall("search", "web_search", buildJsonObject { put("query", "q") })))
            } }, {}, AgentModule(true), runtime = settings.assistantChat)
            try {
                chat.edit("q"); chat.send(); entered.await(); chat.stop(); release.complete(Unit); idle(chat)
                assertEquals(WebFailure.BLOCKED, assertThrows(WebException::class.java) { settings.assistantChat.webSearch.check(WebBudget()) }.reason)
            } finally { release.complete(Unit); chat.close(); scope.cancel() }
        }
    }

}
