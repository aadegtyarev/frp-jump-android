package ru.adsrv.frpjump.agent.openai

import org.junit.Assert.*
import kotlinx.serialization.json.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*

class OpenAiCheckpointTest {
    @Test fun `answer only request keeps tool exchange but omits tool declarations`() {
        val call = ToolCall("c1", "ssh_command", JsonObject(emptyMap()))
        val exchange = AgentExchange(AgentReply("working", listOf(call)), listOf(ToolResult("c1", "ssh_command", "partial", ToolOutcome.FAILED)))
        val request = AgentRequest("base", emptyList(), listOf(AgentTurn("question", listOf(exchange))), 100)
        val body = Json.parseToJsonElement(OpenAiCodec.encode(request, OpenAiConfig("https://example.org/v1/", "model"))).jsonObject
        assertFalse(body.containsKey("tools"))
        val messages = body.getValue("messages").jsonArray
        assertEquals("assistant", messages[2].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("tool", messages[3].jsonObject.getValue("role").jsonPrimitive.content)
        assertTrue(messages[3].jsonObject.getValue("content").jsonPrimitive.content.contains("partial"))
    }

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
