package ru.adsrv.frpjump.agent.openai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*

/** The owner's own text is a separate system message after the untouched base message; without it the request is unchanged. */
class OpenAiOwnerInstructionsTest {
    private val tool = ToolSpec("t", "d", buildJsonObject { put("type", "object") })
    private val config = OpenAiConfig("https://example.org/v1/", "model")
    private val turns = listOf(AgentTurn("question"))
    private fun messages(encoded: String) = Json.parseToJsonElement(encoded).jsonObject.getValue("messages").jsonArray

    @Test fun `empty owner text gives the same bytes as a request built without the field`() {
        val without = OpenAiCodec.encode(AgentRequest("base prompt", listOf(tool), turns, 100), config, null)
        val empty = OpenAiCodec.encode(AgentRequest("base prompt", listOf(tool), turns, 100, ownerInstructions = ""), config, null)
        assertEquals(without, empty)
        assertEquals(listOf("system", "user"), messages(empty).map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertFalse(empty.contains("Owner's additional instructions"))
    }

    @Test fun `owner text follows the unchanged base message under the fixed header`() {
        val base = OpenAiCodec.encode(AgentRequest("base prompt", listOf(tool), turns, 100), config, null)
        val withOwner = OpenAiCodec.encode(AgentRequest("base prompt", listOf(tool), turns, 100, ownerInstructions = "Answer briefly.\nPrefer tmux."), config, null)
        val all = messages(withOwner)
        assertEquals(3, all.size)
        assertEquals(messages(base)[0], all[0])
        assertEquals(buildJsonObject { put("role", "system"); put("content", "## Owner's additional instructions\nAnswer briefly.\nPrefer tmux.") }, all[1])
        assertEquals(messages(base)[1], all[2])
        // Everything but the extra message is identical, so the provider's cache of the base part is kept.
        val root = Json.parseToJsonElement(withOwner).jsonObject
        val rest = JsonObject(root + ("messages" to JsonArray(listOf(all[0], all[2]))))
        assertEquals(Json.parseToJsonElement(base), rest)
        assertTrue(withOwner.startsWith(base.substringBefore("\"messages\"")))
    }

    @Test fun `owner text counts in the token estimate only when present`() {
        val plain = AgentRequest("base", emptyList(), turns, 100)
        val owner = plain.copy(ownerInstructions = "x".repeat(100))
        assertEquals(Utf8TokenEstimate.count(plain), Utf8TokenEstimate.count(plain.copy(ownerInstructions = "")))
        assertEquals(100L + OWNER_INSTRUCTIONS_HEADER.length + 1, Utf8TokenEstimate.count(owner) - Utf8TokenEstimate.count(plain))
    }
}
