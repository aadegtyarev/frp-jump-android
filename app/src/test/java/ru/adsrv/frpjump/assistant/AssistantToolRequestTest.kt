package ru.adsrv.frpjump.assistant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.chat.*
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.settings.*
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.SecretBox

/**
 * Р56: the service tool `request_tool`, its fixed answers, the decline saved with the conversation and the sending of the owner's fixed
 * message. The production conversation, factory and kernel with a fake provider and a fetcher that fails the test when it is reached.
 */
class AssistantToolRequestTest {
    private class Box : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
        override fun destroy() = Unit
    }

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
    private fun ask(id: String, tool: String) = ToolCall(id, "request_tool", args("name" to tool, "why" to "synthetic reason"))

    private class Fx(
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        val storage: MemoryStorage = MemoryStorage(),
        val settings: SettingsStore = SettingsStore(MemoryStorage()).apply { update { it.copy(assistantEnabled = true) } },
        val awaitLoad: Boolean = true,
        val script: (AgentRequest, Int) -> AgentReply,
    ) : AutoCloseable {
        val requests = java.util.concurrent.CopyOnWriteArrayList<AgentRequest>()
        val network = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = AssistantProviderStore(MemoryStorage(), Box(), settings.assistantChat)
            .also { it.save(AssistantProviderDraft(AssistantProviderSettings(model = "m"), null, "synthetic")) }
        val history = AssistantChatHistory(storage, Box(), scope, Dispatchers.Unconfined)
        val module = bindAssistantLifecycle(settings, scope)
        init { settings.assistantChat.publicWeb = PublicFetcher { _, _, _ -> network.incrementAndGet(); throw WebException(WebFailure.NETWORK) } }
        val chat = AssistantConversation(scope, { settings.assistantAvailability }, { store.state.value }, {
            AgentProvider { request -> requests += request; script(request, request.turns.last().exchanges.size) }
        }, store::requireRevision, module, Dispatchers.Unconfined, historyStore = history, prompts = TestPrompts.lazy, confirmations = TestConfirmations.off)
        init { if (awaitLoad) runBlocking { withTimeout(3000) { chat.state.first { !it.loading } } } }
        suspend fun idle() = withTimeout(3000) { chat.state.first { !it.busy } }
        suspend fun send(text: String = "question"): ChatState { chat.edit(text); chat.send(); return idle() }
        fun tools() = settings.assistantAvailability.tools
        fun results() = chat.state.value.turns.last().receipts.map { it.result!! }
        override fun close() { chat.close(); scope.cancel() }
    }

    /** Asks for [tools] one after another, then answers. */
    private fun asking(vararg calls: ToolCall): (AgentRequest, Int) -> AgentReply = { _, done ->
        if (done < calls.size) AgentReply(calls = listOf(calls[done])) else AgentReply("done")
    }

    private fun Fx.on(vararg ids: AssistantToolId) { ids.forEach { settings.setAssistantTool(it, true) } }

    // ---- The tool in the request ----

    @Test fun `request_tool is offered only while some tool is off and its enum lists exactly the off tools`() = runBlocking {
        Fx(script = asking()).use { f ->
            f.send()
            assertEquals(listOf("request_tool"), f.requests.last().tools.map { it.name })
            val all = AssistantToolId.entries.map { it.wire }
            assertEquals(all, enum(f.requests.last().tools.single()))
            f.on(AssistantToolId.READ_PAGE, AssistantToolId.SSH_COMMAND)
            f.send()
            // ssh_command is on but has no SSH owner in this fixture, so it is not offered; it is on, so it is not in the enum.
            assertEquals(listOf("read_page", "request_tool"), f.requests.last().tools.map { it.name })
            assertEquals(all - "read_page" - "ssh_command", enum(f.requests.last().tools.last()))
            f.on(AssistantToolId.WEB_SEARCH, AssistantToolId.INSPECT_IMAGE, AssistantToolId.PROFILE_INFORMATION)
            f.send()
            assertEquals("every tool is on, so there is nothing to ask for", listOf("web_search", "read_page", "inspect_image"), f.requests.last().tools.map { it.name })
            f.settings.setAssistantTool(AssistantToolId.READ_PAGE, false)
            f.send()
            assertEquals("request_tool", f.requests.last().tools.last().name)
            assertEquals(listOf("read_page"), enum(f.requests.last().tools.last()))
        }
    }

    private fun enum(spec: ToolSpec) = spec.parameters.getValue("properties").jsonObject.getValue("name").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }

    @Test fun `the off tools appear to the model by name and one line, never with a schema`() {
        val tool = AssistantRequestTool(listOf(AssistantToolId.WEB_SEARCH, AssistantToolId.SSH_COMMAND), emptySet())
        val description = tool.spec.description
        assertEquals("Ask the owner to turn on one tool that is currently off, when the task cannot be done without it. Give the reason in one short sentence. " +
            "The tool stays off in this request: finish your answer and say what you would do with it. The owner may turn it on and continue. " +
            "Tools you can ask for: web_search - search the public web; ssh_command - run one command on a saved SSH connection.", description)
        for (word in listOf("query", "profile_id", "connection_id", "command\"", "additionalProperties", "required")) assertFalse(word, description.contains(word))
        assertEquals(setOf("name", "why"), tool.spec.parameters.getValue("properties").jsonObject.keys)
        assertEquals(listOf("web_search", "ssh_command"), enum(tool.spec))
        assertEquals(listOf("name", "why"), tool.spec.parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        // Never built for an empty list: with every tool on there is nothing to ask for.
        assertThrows(IllegalArgumentException::class.java) { AssistantRequestTool(emptyList(), emptySet()) }
    }

    @Test fun `the spec of request_tool matches its golden JSON`() {
        val tool = AssistantRequestTool(AssistantToolId.entries, emptySet())
        val golden = Json.parseToJsonElement(javaClass.getResource("/assistant/request-tool-spec.json")!!.readText())
        assertEquals(golden, buildJsonObject { put("name", tool.spec.name); put("description", tool.spec.description); put("parameters", tool.spec.parameters) })
    }

    @Test fun `a call of a tool that is off by name still gets the unknown-tool result and reaches no network`() = runBlocking {
        val calls = AssistantToolId.entries.map { ToolCall("c-${it.wire}", it.wire, args("query" to "x", "url" to "https://public.org/", "question" to "q")) }
        Fx(script = asking(*calls.toTypedArray())).use { f ->
            f.send()
            assertEquals(AssistantToolId.entries.map { ToolOutcome.UNKNOWN_TOOL }, f.results().map { it.outcome })
            assertEquals(AssistantToolId.entries.map { "Unknown tool" }, f.results().map { it.text })
            assertEquals(0, f.network.get())
        }
    }

    // ---- The three fixed answers ----

    @Test fun `first ask, repeat ask and an ask after a decline get their fixed texts and change nothing`() = runBlocking {
        Fx(script = asking(ask("a", "read_page"), ask("b", "read_page"), ask("c", "web_search"))).use { f ->
            val before = f.tools()
            f.send()
            assertEquals(listOf("The owner sees a button to turn on read_page. It is off in this request.", "Already asked in this request. Do not ask again.",
                "The owner sees a button to turn on web_search. It is off in this request."), f.results().map { it.text })
            assertTrue(f.results().all { it.outcome == ToolOutcome.SUCCESS })
            assertEquals(before, f.tools()); assertEquals(0, f.network.get())
            assertEquals(listOf("read_page", "web_search"), f.chat.state.value.turns.last().askedTools)
            // The tool did not become offered: the same tools in every request of the attempt.
            assertEquals(1, f.requests.map { r -> r.tools.map { it.name } }.distinct().size)
            // A new Send is a new attempt: the same tool can be asked for again.
            f.send()
            assertEquals("The owner sees a button to turn on read_page. It is off in this request.", f.results().first().text)
        }
        Fx(script = asking(ask("a", "read_page"))).use { f ->
            f.send()
            f.chat.declineTool(AssistantToolId.READ_PAGE)
            f.send()
            assertEquals("The owner declined read_page in this conversation. Do not ask again.", f.results().single().text)
            assertTrue("a declined ask shows no button", f.chat.state.value.turns.last().askedTools.isEmpty())
            assertEquals(AssistantTools(), f.tools()); assertEquals(0, f.network.get())
        }
    }

    @Test fun `an invalid request_tool call gets the fixed failure and an enabled tool cannot be asked for`() = runBlocking {
        val bad = listOf(
            ToolCall("1", "request_tool", args("name" to "nonsense", "why" to "x")),
            ToolCall("2", "request_tool", args("name" to "read_page")),
            ToolCall("3", "request_tool", args("name" to "read_page", "why" to "x", "extra" to "y")),
            ToolCall("4", "request_tool", args("name" to "web_search", "why" to "x")),
        )
        Fx(script = asking(*bad.toTypedArray())).use { f ->
            f.on(AssistantToolId.WEB_SEARCH)
            f.send()
            assertEquals(List(4) { "Provide name (one of the listed tools) and why (one short sentence) only." }, f.results().map { it.text })
            assertTrue(f.results().all { it.outcome == ToolOutcome.FAILED })
            assertTrue(f.chat.state.value.turns.last().askedTools.isEmpty())
        }
    }

    // ---- The decline is saved with the conversation ----

    @Test fun `a decline is stored with the history, survives a restart and goes with a clear and with turning the tool on`() = runBlocking {
        val storage = MemoryStorage()
        Fx(storage = storage, script = asking(ask("a", "read_page"))).use { f ->
            f.send()
            f.chat.declineTool(AssistantToolId.READ_PAGE)
            assertEquals(setOf("read_page"), f.chat.state.value.declinedTools)
            assertTrue(storage.text!!.contains("\"declinedTools\":[\"read_page\"]"))
        }
        Fx(storage = storage, script = asking()).use { f ->
            assertEquals("restored after restart", setOf("read_page"), f.chat.state.value.declinedTools)
            // Turning the tool on (the Tools screen's change reaches the conversation as toolsChanged) ends it, and it stays ended.
            f.chat.toolsChanged(AssistantTools(readPage = true))
            assertTrue(f.chat.state.value.declinedTools.isEmpty())
            assertFalse(storage.text!!.contains("declinedTools"))
            f.chat.toolsChanged(AssistantTools())
            assertTrue(f.chat.state.value.declinedTools.isEmpty())
        }
        Fx(storage = storage, script = asking(ask("a", "web_search"))).use { f ->
            f.send()
            f.chat.declineTool(AssistantToolId.WEB_SEARCH)
            f.chat.toolsChanged(AssistantTools(readPage = true))
            assertEquals("another tool's switch does not end this decline", setOf("web_search"), f.chat.state.value.declinedTools)
            assertTrue(f.chat.hideHistory()); f.chat.deleteHistory()
            assertTrue(f.chat.state.value.declinedTools.isEmpty())
            assertFalse(storage.text?.contains("declinedTools") ?: false)
        }
        Fx(storage = storage, script = asking()).use { f -> assertTrue(f.chat.state.value.declinedTools.isEmpty()) }
    }

    @Test fun `a chat saved before the decline existed reads with none and one without a decline is written as before`() = runBlocking {
        val old = """{"version":1,"binding":null,"records":[],"draft":"d","omitted":false,"contextStart":0,"summary":null}"""
        val storage = MemoryStorage(old)
        Fx(storage = storage, script = asking()).use { f ->
            assertEquals("d", f.chat.state.value.draft)
            assertTrue(f.chat.state.value.declinedTools.isEmpty())
            f.send("hello")
            assertFalse(storage.text!!.contains("declinedTools"))
        }
    }

    @Test fun `No thanks sends nothing and turns nothing on and is ignored while an attempt runs`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fx(dispatcher = Dispatchers.Default, script = { _, done ->
            if (done == 0) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            AgentReply("done")
        }).use { f ->
            f.chat.edit("q"); f.chat.send()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            f.chat.declineTool(AssistantToolId.READ_PAGE)
            assertTrue(f.chat.state.value.declinedTools.isEmpty())
            release.countDown(); f.idle()
            val requests = f.requests.size; val tools = f.tools()
            f.chat.declineTool(AssistantToolId.READ_PAGE)
            f.chat.declineTool(AssistantToolId.READ_PAGE)
            assertEquals(setOf("read_page"), f.chat.state.value.declinedTools)
            assertEquals("nothing is sent", requests, f.requests.size)
            assertEquals("nothing is turned on", tools, f.tools())
            assertEquals(1, f.chat.state.value.turns.size)
            assertFalse(f.chat.state.value.busy)
        }
    }

    // ---- The owner's fixed message ----

    @Test fun `the fixed message goes as a new Send with the tool on, and the typed draft stays`() = runBlocking {
        Fx(script = { request, done ->
            if (request.turns.size == 1 && done == 0) AgentReply(calls = listOf(ask("a", "read_page"))) else AgentReply("done")
        }).use { f ->
            f.send("first")
            assertEquals(listOf("read_page"), f.chat.state.value.turns.last().askedTools)
            f.chat.edit("my own draft")
            // What the Turn on button does: the switch first, then the fixed message.
            f.settings.setAssistantTool(AssistantToolId.READ_PAGE, true)
            f.chat.sendOwnerMessage("Turned on “Read page”")
            f.idle()
            val last = f.requests.last()
            assertEquals(listOf("first", "Turned on “Read page”"), last.turns.map { it.user })
            assertTrue("the tool is on in the new request", last.tools.any { it.name == "read_page" })
            assertFalse(enum(last.tools.last()).contains("read_page"))
            assertEquals(listOf("first", "Turned on “Read page”"), f.chat.state.value.turns.map { it.user })
            assertEquals("my own draft", f.chat.state.value.draft)
            assertTrue("no button remains for a tool that is on", f.chat.state.value.turns.last().askedTools.isEmpty())
        }
    }

    // ---- The web mark and what a turn asked, from saved records ----

    private fun turn(vararg exchanges: Pair<List<ToolCall>, List<ToolResult>>, status: SavedTurnState = SavedTurnState.COMPLETE) = SavedTurn("u",
        exchanges.map { (calls, results) -> SavedExchange(SavedReply("t", calls.map { SavedCall(it.id, it.name, it.arguments) }), results.map(SavedResult::of)) } +
            SavedExchange(SavedReply("final")), status = status)
    private fun result(call: ToolCall, text: String, outcome: ToolOutcome = ToolOutcome.SUCCESS) = ToolResult(call.id, call.name, text, outcome)
    private val firstAsk = "The owner sees a button to turn on ssh_command. It is off in this request."

    @Test fun `a turn tells what it asked and whether it read the internet`() {
        val a = ask("a", "ssh_command"); val search = ToolCall("s", "web_search", args("query" to "q")); val read = ToolCall("r", "read_page", args("url" to "https://public.org/"))
        val image = ToolCall("i", "inspect_image", args("url" to "https://public.org/i.png", "question" to "q"))
        val plain = turn(listOf(a) to listOf(result(a, firstAsk))).view()
        assertEquals(listOf("ssh_command"), plain.askedTools); assertFalse(plain.readWeb)
        for (call in listOf(search, read, image)) {
            val view = turn(listOf(call) to listOf(result(call, "x")), listOf(a) to listOf(result(a, firstAsk))).view()
            assertTrue(call.name, view.readWeb); assertEquals(listOf("ssh_command"), view.askedTools)
        }
        // A failed web call still marks the attempt: foreign text may have reached the model, or been tried for.
        assertTrue(turn(listOf(read) to listOf(result(read, "failed", ToolOutcome.FAILED)), listOf(a) to listOf(result(a, firstAsk))).view().readWeb)
        // A repeat, a declined or a failed ask, an unfinished attempt and a missing result give no button.
        assertTrue(turn(listOf(a) to listOf(result(a, "Already asked in this request. Do not ask again."))).view().askedTools.isEmpty())
        assertTrue(turn(listOf(a) to listOf(result(a, AssistantRequestTool.declinedText("ssh_command")))).view().askedTools.isEmpty())
        assertTrue(turn(listOf(a) to listOf(result(a, firstAsk, ToolOutcome.FAILED))).view().askedTools.isEmpty())
        assertTrue(turn(listOf(a) to listOf(result(a, firstAsk)), status = SavedTurnState.INTERRUPTED).view().askedTools.isEmpty())
        assertTrue(turn(listOf(a) to emptyList()).view().askedTools.isEmpty())
        // An ask for a name that is not a tool, or a text of another tool, is not one.
        val odd = ToolCall("o", "request_tool", args("name" to "nonsense", "why" to "x"))
        assertTrue(turn(listOf(odd) to listOf(result(odd, "The owner sees a button to turn on nonsense. It is off in this request."))).view().askedTools.isEmpty())
        val other = ToolCall("p", "web_search", args("name" to "ssh_command"))
        assertTrue(turn(listOf(other) to listOf(result(other, firstAsk))).view().askedTools.isEmpty())
    }

    // ---- "Offer to turn tools on" ----

    @Test fun `with the offer switch off request_tool is in no request, and on by default it is`() = runBlocking {
        Fx(script = asking()).use { f ->
            assertTrue("on by default", f.tools().offer)
            f.send()
            assertEquals(listOf("request_tool"), f.requests.last().tools.map { it.name })
            f.settings.setAssistantOfferTools(false)
            f.send()
            assertTrue("a plain chat request carries no tools at all", f.requests.last().tools.isEmpty())
            f.on(AssistantToolId.READ_PAGE)
            f.send()
            assertEquals(listOf("read_page"), f.requests.last().tools.map { it.name })
            f.settings.setAssistantOfferTools(true)
            f.send()
            assertEquals(listOf("read_page", "request_tool"), f.requests.last().tools.map { it.name })
        }
    }

    @Test fun `the offer switch is saved with the settings, defaults on and survives tool switches`() {
        val file = MemoryStorage()
        assertTrue(SettingsStore(file).assistantAvailability.tools.offer)
        SettingsStore(file).apply { setAssistantOfferTools(false); setAssistantTool(AssistantToolId.SSH_COMMAND, true) }
        val again = SettingsStore(file).assistantAvailability.tools
        assertFalse(again.offer); assertTrue(again.sshCommand)
        assertTrue("a file written before the switch existed reads as on", SettingsStore(MemoryStorage("""{"assistant_enabled":true}""")).assistantAvailability.tools.offer)
    }

    // ---- Progress ----

    @Test fun `a request_tool call shows the waiting line, not a failure`() = runBlocking {
        assertNull(progressOnCall("request_tool"))
        assertEquals(ChatToolProgress.Tool(AssistantToolId.READ_PAGE), progressOnCall("read_page"))
        assertEquals(ChatToolProgress.FAILED, progressOnCall("nonsense"))
        val seen = java.util.concurrent.CopyOnWriteArrayList<ChatToolProgress?>()
        lateinit var chat: AssistantConversation
        Fx(script = { _, done ->
            if (done == 0) AgentReply(calls = listOf(ask("a", "read_page"))) else { seen += chat.state.value.toolProgress; AgentReply("done") }
        }).use { f ->
            chat = f.chat
            f.send()
            assertEquals(listOf<ChatToolProgress?>(null), seen.toList())
        }
    }

    // ---- The fixed message is refused when a Send cannot start ----

    @Test fun `the fixed message is refused while a Send runs, while the history loads and while a clear waits`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fx(dispatcher = Dispatchers.Default, script = { _, done ->
            if (done == 0) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            AgentReply("done")
        }).use { f ->
            f.chat.edit("q"); f.chat.send()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            f.chat.sendOwnerMessage("Turned on x")
            release.countDown(); f.idle()
            assertEquals("a Send was running: the message was refused", 1, f.requests.size)
            assertEquals(listOf("q"), f.chat.state.value.turns.map { it.user })
            // A clear waits: refused, and the owner's draft is not touched.
            assertTrue(f.chat.hideHistory())
            f.chat.sendOwnerMessage("Turned on x")
            assertEquals(1, f.requests.size)
        }
        // The history is still loading.
        val gate = CountDownLatch(1)
        val slow = object : ru.adsrv.frpjump.store.TextStorage {
            override fun read(): String? { gate.await(5, TimeUnit.SECONDS); return null }
            override fun write(text: String) = Unit
            override fun delete() = Unit
        }
        val calls = AtomicInteger()
        val settings = SettingsStore(MemoryStorage()).apply { update { it.copy(assistantEnabled = true) } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = AssistantProviderStore(MemoryStorage(), Box(), settings.assistantChat)
                .also { it.save(AssistantProviderDraft(AssistantProviderSettings(model = "m"), null, "synthetic")) }
            val chat = AssistantConversation(scope, { settings.assistantAvailability }, { store.state.value }, {
                AgentProvider { calls.incrementAndGet(); AgentReply("done") }
            }, store::requireRevision, bindAssistantLifecycle(settings, scope), Dispatchers.Unconfined,
                historyStore = AssistantChatHistory(slow, Box(), scope, Dispatchers.IO), prompts = TestPrompts.lazy, confirmations = TestConfirmations.off)
            assertTrue(chat.state.value.loading)
            chat.sendOwnerMessage("Turned on x")
            assertEquals(0, calls.get())
            gate.countDown(); withTimeout(3000) { chat.state.first { !it.loading } }
            assertTrue(chat.state.value.turns.isEmpty())
            chat.close()
        } finally { gate.countDown(); scope.cancel() }
    }
}
