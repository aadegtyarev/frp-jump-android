package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.chat.*
import ru.adsrv.frpjump.settings.AssistantAvailability
import ru.adsrv.frpjump.store.*
import java.util.Base64

/** A terminal quote goes through the same draft as typing: kept with what is there, limited, saved with the chat. */
class AssistantQuoteDraftTest {
    private class Memory : TextStorage {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
        override fun delete() { text = null }
    }
    private class Box : SecretBox {
        override fun seal(plain: String) = Base64.getEncoder().encodeToString(plain.toByteArray())
        override fun open(sealed: String) = String(Base64.getDecoder().decode(sealed))
        override fun destroy() = Unit
    }
    private val snapshot = AssistantProviderSnapshot(AssistantProviderSettings(model = "test"), "same-revision")
    private fun chat(scope: CoroutineScope, file: Memory) =
        AssistantConversation(scope, { AssistantAvailability(true) }, { snapshot }, { AgentProvider { AgentReply("ok") } }, {}, AgentModule(true),
            io = Dispatchers.Unconfined, historyStore = AssistantChatHistory(file, Box(), scope, Dispatchers.Unconfined),
            prompts = TestPrompts.lazy, confirmations = TestConfirmations.off)
    private suspend fun AssistantConversation.ready() = withTimeout(3000) { state.first { !it.loading && !it.busy } }

    @Test fun `a quote goes into an empty draft`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = chat(scope, Memory()); c.ready()
        assertTrue(c.appendToDraft("```\nx\n```\n"))
        assertEquals("```\nx\n```\n", c.state.value.draft)
        c.close(); scope.cancel()
    }

    @Test fun `an existing draft is kept, also from an earlier visit, and the quote follows it`() = runBlocking {
        val file = Memory(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = chat(scope, file); first.ready(); first.edit("why does this fail?"); first.close()
        val second = chat(scope, file); second.ready()
        assertEquals("why does this fail?", second.state.value.draft)
        assertTrue(second.appendToDraft("```\nboom\n```\n"))
        assertEquals("why does this fail?\n\n```\nboom\n```\n", second.state.value.draft)
        second.close()
        val third = chat(scope, file); third.ready()
        assertEquals("why does this fail?\n\n```\nboom\n```\n", third.state.value.draft)
        third.close(); scope.cancel()
    }

    @Test fun `a result over the message limit is not applied and the draft stays`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = chat(scope, Memory()); c.ready()
        c.edit("a".repeat(AssistantConversation.MAX_INPUT - 5))
        assertFalse(c.appendToDraft("```\nxxxxxxxx\n```\n"))
        assertEquals(AssistantConversation.MAX_INPUT - 5, c.state.value.draft.length)
        assertEquals(ChatProblem.TOO_LONG, c.state.value.problem)
        c.close(); scope.cancel()
    }

    @Test fun `a full-size quote fits an empty draft`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = chat(scope, Memory()); c.ready()
        assertTrue(c.appendToDraft(ChatQuote.block("z".repeat(9_000), "[truncated]")!!))
        assertTrue(c.state.value.draft.length <= ChatQuote.LIMIT)
        c.close(); scope.cancel()
    }

    @Test fun `a closed chat takes nothing`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = chat(scope, Memory()); c.ready(); c.close()
        assertFalse(c.appendToDraft("```\nx\n```\n"))
        scope.cancel()
    }
}
