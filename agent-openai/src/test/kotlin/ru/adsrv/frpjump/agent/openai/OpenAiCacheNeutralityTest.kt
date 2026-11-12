package ru.adsrv.frpjump.agent.openai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*

/** Prefix caching relies on identical leading bytes only; no provider-specific cache parameter is ever sent. */
class OpenAiCacheNeutralityTest {
    @Test fun `encoded requests carry no cache parameter and start with the system message`() {
        val tool = ToolSpec("t", "d", buildJsonObject { put("type", "object") })
        val request = AgentRequest("system text", listOf(tool), listOf(AgentTurn("question")), 100)
        val config = OpenAiConfig("https://example.org/v1/", "model")
        val encoded = OpenAiCodec.encode(request, config, null)
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertTrue(root.keys.none { it.contains("cache", ignoreCase = true) })
        assertFalse(encoded.contains("prompt_cache"))
        val first = root.getValue("messages").jsonArray.first().jsonObject
        assertEquals("system", first.getValue("role").jsonPrimitive.content)
        assertEquals("system text", first.getValue("content").jsonPrimitive.content)
        assertEquals(encoded, OpenAiCodec.encode(request, config, null))
    }
}
