package ru.adsrv.frpjump.agent.openai

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*

class OpenAiCheckpointTest {
    @Test fun `restored opaque state belongs only to the new provider owner`() {
        val previous = Any(); val next = Any()
        val state = OpenAiCodec.restoreContinuation("private reasoning", previous)
        val saved = OpenAiCodec.saveContinuation(state, previous)
        assertThrows(OpenAiProviderException::class.java) { OpenAiCodec.saveContinuation(state, next) }
        val restored = OpenAiCodec.restoreContinuation(saved, next)
        val request = AgentRequest("base", emptyList(), listOf(AgentTurn("question", listOf(AgentExchange(AgentReply("answer", continuation = restored))))), 100)
        val encoded = OpenAiCodec.encode(request, OpenAiConfig("https://example.org/v1/", "model"), next)
        assertTrue(encoded.contains("\"reasoning_content\":\"private reasoning\""))
        assertFalse(restored.toString().contains("private reasoning"))
    }
}
