package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.agent.openai.OpenAiProviderException
import ru.adsrv.frpjump.settings.AssistantAvailability
import ru.adsrv.frpjump.settings.AssistantTools

/** Pins how the chat maps neutral provider failures and optional provider capabilities, independent of the provider module. */
class AssistantProviderNeutralityTest {
    private val snapshot = AssistantProviderSnapshot(AssistantProviderSettings(model = "m"), "r")

    private fun chat(scope: CoroutineScope, tools: AssistantTools, runtime: AssistantChatRuntime, provider: AgentProvider) =
        AssistantConversation(scope, { AssistantAvailability(true, tools = tools) }, { snapshot }, { provider }, {}, AgentModule(true),
            io = Dispatchers.Unconfined, runtime = runtime)

    private suspend fun AssistantConversation.sendAndWait(text: String = "question"): ChatState {
        edit(text); send(); return withTimeout(3000) { state.first { !it.busy } }
    }

    @Test fun `every provider error kind maps to its chat problem`() = runBlocking {
        val expected = mapOf(
            ProviderErrorKind.TIMEOUT to ChatProblem.TIMEOUT,
            ProviderErrorKind.INVALID_RESPONSE to ChatProblem.INVALID_REPLY,
            ProviderErrorKind.INCOMPLETE_RESPONSE to ChatProblem.INVALID_REPLY,
            ProviderErrorKind.RESPONSE_TOO_LARGE to ChatProblem.INVALID_REPLY,
            ProviderErrorKind.HTTP to ChatProblem.PROVIDER,
            ProviderErrorKind.NETWORK to ChatProblem.PROVIDER,
            ProviderErrorKind.INCOMPATIBLE_CONTEXT to ChatProblem.PROVIDER,
            ProviderErrorKind.REQUEST_TOO_LARGE to ChatProblem.PROVIDER,
        )
        assertEquals("Every kind must be pinned", ProviderErrorKind.entries.toSet(), expected.keys)
        for ((kind, problem) in expected) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val state = chat(scope, AssistantTools(), AssistantChatRuntime(), AgentProvider { throw OpenAiProviderException(kind, 400) }).sendAndWait()
                assertEquals("$kind", problem, state.problem)
                assertEquals("$kind", "question", state.draft)
                assertTrue("$kind", state.turns.isEmpty())
            } finally { scope.cancel() }
        }
    }

    /** Counts fetches: an image tool call fetches only when the provider offers an inspector. */
    private suspend fun fetchesFor(model: (AgentRequest) -> AgentReply, withInspector: Boolean): Int {
        var fetches = 0
        val runtime = AssistantChatRuntime().apply {
            publicWeb = PublicFetcher { url, _, attempt -> fetches++; admitSideEffect("public-dispatch"); attempt("public-dispatch"); WebResponse(url, "text/plain", ByteArray(0), 404, null) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val provider: AgentProvider = if (withInspector) object : AgentProvider, ImageInspector {
                override suspend fun complete(request: AgentRequest) = model(request).also { calls++ }
                override suspend fun inspectImage(mime: String, bytes: ByteArray, question: String) = "x"
            } else AgentProvider { model(it).also { calls++ } }
            chat(scope, AssistantTools(inspectImage = true), runtime, provider).sendAndWait()
            assertEquals(2, calls)
        } finally { scope.cancel() }
        return fetches
    }

    @Test fun `image inspection is offered only when the provider is an image inspector`() = runBlocking {
        var first = true
        val args = buildJsonObject { put("url", "https://public.org/i.png"); put("question", "q") }
        val model = { _: AgentRequest -> if (first) { first = false; AgentReply(calls = listOf(ToolCall("i", "inspect_image", args))) } else AgentReply("done") }
        assertEquals(1, fetchesFor(model, withInspector = true))
        first = true
        assertEquals(0, fetchesFor(model, withInspector = false))
    }
}
