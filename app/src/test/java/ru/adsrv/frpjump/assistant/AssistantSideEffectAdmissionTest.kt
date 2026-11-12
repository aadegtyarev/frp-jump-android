package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.assistant.chat.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.store.TextStorage

/** The chat installs the ambient start admission around the whole run; transports and tools only ask it. */
class AssistantSideEffectAdmissionTest {
    private class Memory : TextStorage {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
        override fun delete() { text = null }
    }
    private val saved = AssistantProviderSnapshot(AssistantProviderSettings(model = "m"), "r")
    private suspend fun idle(chat: AssistantConversation) = withTimeout(4000) { chat.state.first { !it.busy } }
    private fun reply() = AgentReply(calls = listOf(ToolCall("call", "read_page", buildJsonObject { put("url", "https://public.org/doc") })))
    private fun document() = WebResponse("https://public.org/doc", "text/html", "<title>Fetched guide</title><p>Static documentation with enough useful content for the reader.</p>".toByteArray(), 200, null)

    @Test fun `the chat installs one admission whose check runs under the chat gate for every category`() = runBlocking {
        val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true, assistantReadPage = true) } }
        val checked = CopyOnWriteArrayList<Pair<String, Boolean>>(); val before = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val providerHeldGate = CopyOnWriteArrayList<Boolean>(); var calls = 0
        settings.assistantChat.publicWeb = PublicFetcher { _, _, attempt ->
            providerHeldGate += Thread.holdsLock(settings.assistantChat.gate)
            admitSideEffect("public-dispatch"); attempt("public-dispatch"); document()
        }
        settings.assistantChat.probes = ChatProbes(
            before = { category -> before += category to Thread.holdsLock(settings.assistantChat.gate) },
            checked = { category -> checked += category to Thread.holdsLock(settings.assistantChat.gate) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, {
            AgentProvider { providerHeldGate += Thread.holdsLock(settings.assistantChat.gate); if (++calls == 1) reply() else AgentReply("complete") }
        }, {}, AgentModule(true), runtime = settings.assistantChat, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
        try {
            chat.edit("q"); chat.send(); idle(chat)
            val categories = checked.map { it.first }.toSet()
            for (category in listOf("dispatch", "tool-start", "public-dispatch", "source", "progress", "history", "answer"))
                assertTrue("$category in $categories", category in categories)
            assertTrue("every checked category runs under the gate: $checked", checked.all { it.second })
            assertTrue("probes before the guard never hold it: $before", before.none { it.second })
            assertEquals("each check has its pre-guard probe", before.map { it.first }.sorted(), checked.map { it.first }.sorted())
            assertTrue("a neutral provider and a transport never run under the gate: $providerHeldGate", providerHeldGate.isNotEmpty() && providerHeldGate.none { it })
            assertEquals(1, chat.state.value.turns.size)
        } finally { chat.close(); scope.cancel() }
    }

    @Test fun `a neutral provider is admitted before each call`() = runBlocking {
        val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true, assistantReadPage = true) } }
        val dispatches = AtomicInteger(); val providerCalls = AtomicInteger()
        settings.assistantChat.publicWeb = PublicFetcher { _, _, attempt -> admitSideEffect("public-dispatch"); attempt("public-dispatch"); document() }
        settings.assistantChat.probes = ChatProbes(checked = { category -> if (category == "dispatch") dispatches.incrementAndGet() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, {
            // This provider never calls any admission API.
            AgentProvider { if (providerCalls.incrementAndGet() == 1) reply() else AgentReply("complete") }
        }, {}, AgentModule(true), runtime = settings.assistantChat, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
        try {
            chat.edit("q"); chat.send(); idle(chat)
            assertEquals(2, providerCalls.get()); assertEquals(providerCalls.get(), dispatches.get())
        } finally { chat.close(); scope.cancel() }
    }

    @Test fun `a refused dispatch starts no provider call`() = runBlocking {
        val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true) } }
        val providerCalls = AtomicInteger()
        var stop: (() -> Unit)? = null
        // Stop commits between the pre-guard probe and the check of the first provider start.
        settings.assistantChat.probes = ChatProbes(before = { category -> if (category == "dispatch") stop?.invoke() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, {
            AgentProvider { providerCalls.incrementAndGet(); AgentReply("complete") }
        }, {}, AgentModule(true), runtime = settings.assistantChat, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
        stop = { chat.stop() }
        try {
            chat.edit("q"); chat.send(); idle(chat)
            assertEquals(0, providerCalls.get()); assertTrue(chat.state.value.turns.isEmpty())
        } finally { chat.close(); scope.cancel() }
    }

    @Test fun `image tool admits image-dispatch right before inspecting and a refusal inspects nothing`() = runBlocking {
        var inspections = 0
        val events = CopyOnWriteArrayList<String>()
        val inspector = object : ImageInspector {
            override suspend fun inspectImage(mime: String, bytes: ByteArray, question: String): String { inspections++; events += "inspect"; return "unverified text" }
        }
        val fetcher = PublicFetcher { url, _, attempt ->
            admitSideEffect("public-dispatch"); attempt("public-dispatch")
            WebResponse(url, "image/png", byteArrayOf(1, 2, 3), 200, null)
        }
        fun tools() = AssistantWebTools(ru.adsrv.frpjump.settings.AssistantTools(inspectImage = true), fetcher, WebSearchState(), inspector, { }, { _, _ -> }).tools.single()
        val arguments = buildJsonObject { put("url", "https://public.org/i.png"); put("question", "q") }
        try { withContext(SideEffectAdmission { events += "admit:$it"; it != "image-dispatch" }) { tools().execute(arguments) }; fail("must cancel") }
        catch (_: CancellationException) { }
        assertEquals(0, inspections)
        assertEquals(listOf("admit:tool-start", "admit:public-dispatch", "admit:image-dispatch"), events.toList())
        events.clear()
        val result = withContext(SideEffectAdmission { events += "admit:$it"; true }) { tools().execute(arguments) }
        assertEquals(ToolOutcome.SUCCESS, result.outcome); assertEquals(1, inspections)
        assertEquals(listOf("admit:tool-start", "admit:public-dispatch", "admit:image-dispatch", "inspect"), events.toList())
    }
}
