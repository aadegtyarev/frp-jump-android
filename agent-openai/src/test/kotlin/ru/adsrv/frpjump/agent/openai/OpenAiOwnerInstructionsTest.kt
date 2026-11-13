package ru.adsrv.frpjump.agent.openai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*

/**
 * The owner's own text is appended to the one system message, after the unchanged base prompt (its exact byte prefix); there is never
 * a second system message. Without the text the request is the one the encoder produced before this feature.
 */
class OpenAiOwnerInstructionsTest {
    private val tool = ToolSpec("t", "d", buildJsonObject { put("type", "object") })
    private val config = OpenAiConfig("https://example.org/v1/", "model")
    private val turns = listOf(AgentTurn("question"))
    private fun messages(encoded: String) = Json.parseToJsonElement(encoded).jsonObject.getValue("messages").jsonArray
    private fun roles(encoded: String) = messages(encoded).map { it.jsonObject.getValue("role").jsonPrimitive.content }

    // Literal bytes of the encoder of the previous release (994ed2d) for this request.
    private val emptyGolden = """{"model":"model","stream":false,"max_tokens":100,"messages":[{"role":"system","content":"base prompt"},{"role":"user","content":"question"}],"tools":[{"type":"function","function":{"name":"t","description":"d","parameters":{"type":"object"}}}]}"""
    private val ownerGolden = """{"model":"model","stream":false,"max_tokens":100,"messages":[{"role":"system","content":"base prompt\n\n## Owner's additional instructions\nAnswer briefly.\nPrefer tmux."},{"role":"user","content":"question"}],"tools":[{"type":"function","function":{"name":"t","description":"d","parameters":{"type":"object"}}}]}"""

    @Test fun `empty owner text gives the literal bytes of the previous release`() {
        assertEquals(emptyGolden, OpenAiCodec.encode(AgentRequest("base prompt", listOf(tool), turns, 100), config, null))
        assertEquals(emptyGolden, OpenAiCodec.encode(AgentRequest("base prompt", listOf(tool), turns, 100, ownerInstructions = ""), config, null))
        assertEquals(listOf("system", "user"), roles(emptyGolden))
    }

    @Test fun `owner text follows the base prompt inside the one system message`() {
        val encoded = OpenAiCodec.encode(AgentRequest("base prompt", listOf(tool), turns, 100, ownerInstructions = "Answer briefly.\nPrefer tmux."), config, null)
        assertEquals(ownerGolden, encoded)
        assertEquals(listOf("system", "user"), roles(encoded))
        val content = messages(encoded)[0].jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(content.startsWith("base prompt"))
        assertEquals("base prompt\n\n## Owner's additional instructions\nAnswer briefly.\nPrefer tmux.", content)
    }

    @Test fun `the base prompt is the exact prefix of the first message in both cases`() {
        val base = "base prompt é中"
        val plain = OpenAiCodec.encode(AgentRequest(base, listOf(tool), turns, 100), config, null)
        val owned = OpenAiCodec.encode(AgentRequest(base, listOf(tool), turns, 100, ownerInstructions = "x"), config, null)
        assertEquals(base, messages(plain)[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertTrue(messages(owned)[0].jsonObject.getValue("content").jsonPrimitive.content.startsWith(base))
        // The rest of the request is untouched.
        assertEquals(messages(plain).drop(1), messages(owned).drop(1))
        assertEquals(Json.parseToJsonElement(plain).jsonObject.getValue("tools"), Json.parseToJsonElement(owned).jsonObject.getValue("tools"))
    }

    @Test fun `owner text counts in the token estimate with a framing allowance only when present`() {
        val plain = AgentRequest("base", emptyList(), turns, 100)
        val owner = plain.copy(ownerInstructions = "x".repeat(100))
        assertEquals(Utf8TokenEstimate.count(plain), Utf8TokenEstimate.count(plain.copy(ownerInstructions = "")))
        // "\n\n" + header + "\n" + the text, plus the allowance.
        assertEquals(2L + OWNER_INSTRUCTIONS_HEADER.length + 1 + 100 + OWNER_FRAMING_TOKENS, Utf8TokenEstimate.count(owner) - Utf8TokenEstimate.count(plain))
    }
}
