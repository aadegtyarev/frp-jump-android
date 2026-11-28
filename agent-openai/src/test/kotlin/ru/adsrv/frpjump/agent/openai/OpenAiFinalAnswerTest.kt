package ru.adsrv.frpjump.agent.openai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*

class OpenAiFinalAnswerTest {
    private val config = OpenAiConfig("https://example.invalid/v1", "model")
    private fun response(content: String) = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject {
            put("finish_reason", "stop"); putJsonObject("message") { put("role", "assistant"); put("content", content) }
        }) }
    }.toString()

    @Test fun `final instruction follows real results and reasoning without declarations`() {
        val owner = Any()
        val continuation = OpenAiCodec.restoreContinuation("opaque reasoning", owner)
        val call = ToolCall("id", "synthetic", buildJsonObject { put("value", "x") })
        val exchange = AgentExchange(AgentReply("working", listOf(call), continuation), listOf(ToolResult("id", "synthetic", "actual result <｜DSML｜ calls>", ToolOutcome.SUCCESS)))
        val base = AgentRequest("base", emptyList(), listOf(AgentTurn("original question", listOf(exchange))), 100)
        val normal = Json.parseToJsonElement(OpenAiCodec.encode(base, config, owner)).jsonObject
        val final = Json.parseToJsonElement(OpenAiCodec.encode(base.copy(finalSynthesis = true), config, owner)).jsonObject
        val messages = final.getValue("messages").jsonArray
        assertEquals(normal.getValue("messages").jsonArray, JsonArray(messages.dropLast(1)))
        assertNull(final["tools"])
        assertEquals("opaque reasoning", messages[2].jsonObject.getValue("reasoning_content").jsonPrimitive.content)
        assertTrue(messages[3].jsonObject.getValue("content").jsonPrimitive.content.contains("actual result <｜DSML｜ calls>"))
        assertEquals("user", messages.last().jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(FINAL_SYNTHESIS_INSTRUCTION, messages.last().jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(64 + FINAL_SYNTHESIS_INSTRUCTION.toByteArray().size.toLong(), Utf8TokenEstimate.count(base.copy(finalSynthesis = true)) - Utf8TokenEstimate.count(base))
    }

    @Test fun `raw or unfinished dsml frames are invalid but quoted examples remain prose`() {
        listOf("<｜DSML｜ calls>", "<|DSML|tool_calls>", "< | | DSML | | invoke name=\"test\"", "<｜DSML｜ parameter", "<|DSML|function_calls", "answer\n</｜DSML｜calls>").forEach {
            val ex = assertThrows(OpenAiProviderException::class.java) { OpenAiCodec.decode(response(it)) }
            assertEquals(ProviderFailure.INVALID_RESPONSE, ex.failure)
        }
        listOf("intro\r\n```xml\r\n<｜DSML｜ calls>\r\n```", "```xml\n```not-a-close\n<｜DSML｜ calls>\n```", "```xml\n<｜DSML｜ calls>\n```", "Inline `<|DSML|invoke>` example", "Example: ``literal ``` <｜DSML｜calls>``.", "`line one\n<｜DSML｜calls>`", "> <｜DSML｜ parameter>", "\"<｜DSML｜ calls>\"", "# Ordinary markdown\n- answer: fine").forEach {
            assertEquals(it, OpenAiCodec.decode(response(it)).text)
        }
        assertThrows(OpenAiProviderException::class.java) { OpenAiCodec.decode(response("`example` then <｜DSML｜ calls>")) }
        assertThrows(OpenAiProviderException::class.java) { OpenAiCodec.decode(response("`unclosed\n\n<｜DSML｜ calls>\n\n`")) }
        assertThrows(OpenAiProviderException::class.java) { OpenAiCodec.decode(response("escaped " + '\\' + '"' + " then <｜DSML｜ calls> " + '\\' + '"')) }
        assertThrows(OpenAiProviderException::class.java) { OpenAiCodec.decode(response("```bad`info\n<｜DSML｜ calls>")) }
    }
}
