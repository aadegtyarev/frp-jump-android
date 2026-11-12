package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.chat.*
import ru.adsrv.frpjump.settings.AssistantAvailability

/** A single reply above the saved-turn size cap ends the Send as CONTEXT_FULL instead of being saved. */
class AssistantHistoryFullTest {
    @Test fun `an oversized reply ends the Send as context full`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val snapshot = AssistantProviderSnapshot(AssistantProviderSettings(model = "m", contextTokens = 1_000_000, outputTokens = 1000), "r")
        val chat = AssistantConversation(scope, { AssistantAvailability(true) }, { snapshot }, { AgentProvider { AgentReply("x".repeat(ChatLimits.MAX_HISTORY_CHARS + 1)) } },
            {}, AgentModule(true), io = Dispatchers.Unconfined,
            limits = { AgentLimits(contextTokens = 1_000_000, outputReserveTokens = 1000, maxReplyChars = 600_000) }, prompts = TestPrompts.placeholder)
        try {
            chat.edit("question"); chat.send()
            val state = withTimeout(5000) { chat.state.first { !it.busy && it.problem != null } }
            assertEquals(ChatProblem.CONTEXT_FULL, state.problem)
            assertTrue(state.turns.isEmpty())
        } finally { chat.close(); scope.cancel() }
    }
}
