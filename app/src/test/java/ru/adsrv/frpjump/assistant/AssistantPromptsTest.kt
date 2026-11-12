package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.assistant.chat.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.settings.AssistantAvailability
import ru.adsrv.frpjump.settings.AssistantTools

/** The two prompt assets, pinned by file hash and version; JVM only, read from `src/main/assets`. */
class AssistantPromptsTest {
    private fun asset(path: String) = File("src/main/assets/$path").readBytes()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `asset files and versions are pinned together`() {
        // Changing a prompt without a new version and a new hash here fails this test.
        assertEquals(4, AssistantPrompts.SYSTEM_VERSION)
        assertEquals(2, AssistantPrompts.SUMMARY_VERSION)
        assertEquals("e344abe12ddce301ebfcd46b1daa4e028f567b4c20a8ab7008cf932c0bcfe778", sha256(asset("assistant/skill.md")))
        assertEquals("b3067ad69b79a9739893d2ba00a122baa327cd97daf086bafd366c13e65c8d38", sha256(asset("assistant/summary.md")))
    }

    @Test fun `system prompt fits 4096 bytes and has no volatile content`() {
        val bytes = asset("assistant/skill.md")
        assertTrue("skill.md is ${bytes.size} bytes", bytes.size <= 4096)
        assertFalse(bytes.any { it == '\r'.code.toByte() })
        val text = TestPrompts.real.system
        assertEquals(String(bytes, Charsets.UTF_8).trimEnd(), text)
        assertFalse(Regex("(?i)version|\\b20\\d\\d\\b|subagent|permission evaluator|summariz").containsMatchIn(text))
    }

    @Test fun `loader gives the same bytes for CRLF and LF and trims trailing whitespace once`() {
        fun load(skill: String, summary: String) = AssistantPrompts.load { path ->
            ByteArrayInputStream((if (path == AssistantPrompts.SYSTEM_ASSET) skill else summary).toByteArray()) }
        val lf = load("a\nb\n", "c\n")
        val crlf = load("a\r\nb\r\n", "c\r\n")
        assertEquals("a\nb", lf.system); assertEquals("c", lf.summary)
        assertEquals(lf.system, crlf.system); assertEquals(lf.summary, crlf.summary)
        assertEquals(TestPrompts.real.system, AssistantPrompts.load { File("src/main/assets/$it").inputStream() }.system)
    }

    private val snapshot = AssistantProviderSnapshot(AssistantProviderSettings(model = "m"), "r")
    private fun chat(scope: CoroutineScope, tools: AssistantTools, prompts: Lazy<AssistantPrompts>, provider: AgentProvider) =
        AssistantConversation(scope, { AssistantAvailability(true, tools = tools) }, { snapshot }, { provider }, {}, AgentModule(true),
            io = Dispatchers.Unconfined, prompts = prompts)
    private suspend fun AssistantConversation.sendAndWait(text: String): ChatState {
        edit(text); send(); return withTimeout(3000) { state.first { !it.busy } }
    }

    @Test fun `system text and tools are identical in every request of one and two Sends`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requests = CopyOnWriteArrayList<AgentRequest>()
        val provider = AgentProvider { request ->
            requests += request
            // The first request of each Send proposes an unknown tool: a second request in the same Send, without any network.
            if (request.turns.last().exchanges.isEmpty()) AgentReply(calls = listOf(ToolCall("c${requests.size}", "no_such_tool", buildJsonObject {})))
            else AgentReply("done")
        }
        val chat = chat(scope, AssistantTools(search = true, readPage = true), TestPrompts.lazy, provider)
        try {
            assertNull(chat.sendAndWait("one").problem); assertNull(chat.sendAndWait("two").problem)
            assertEquals(4, requests.size)
            assertTrue(requests.all { it.instructions == TestPrompts.real.system })
            assertEquals(listOf("web_search", "read_page"), requests.first().tools.map { it.name })
            assertTrue(requests.all { it.tools == requests.first().tools })
        } finally { chat.close(); scope.cancel() }
    }

    @Test fun `a missing asset fails the Send as a setup problem without any provider request`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val missing = lazy { AssistantPrompts.load { throw FileNotFoundException(it) } }
        val chat = chat(scope, AssistantTools(), missing, AgentProvider { calls++; AgentReply("must not run") })
        try {
            val state = chat.sendAndWait("kept")
            assertEquals(ChatProblem.SETUP, state.problem); assertEquals(0, calls); assertEquals("kept", state.draft)
        } finally { chat.close(); scope.cancel() }
    }
}
