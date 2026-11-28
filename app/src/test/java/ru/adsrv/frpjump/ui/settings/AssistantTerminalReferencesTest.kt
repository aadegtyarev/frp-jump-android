package ru.adsrv.frpjump.ui.settings

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.chat.ChatToolReceipt

class AssistantTerminalReferencesTest {
    private val token = "[terminal:123]"
    private val ref = ToolReference(token, label = "1 · me@host")
    private fun receipt(name: String = "terminal_tabs", outcome: ToolOutcome = ToolOutcome.SUCCESS) =
        ChatToolReceipt(ToolCall("c", name, buildJsonObject {}), ToolResult("c", name, "row", outcome, references = listOf(ref)))

    @Test fun `only an issued token in its own paragraph becomes a button`() {
        val literal = "<pre>\n$token\n</pre>\n\n```\n$token\n```\n\n> $token\n\n[$token](https://example.com)\n[terminal:other]"
        val result = referenceAnswer("hello\n\n$token\n\n$literal", listOf(receipt()))
        assertEquals(listOf(ref), result.references)
        assertEquals("hello\n\n\n\n$literal", result.text)
        assertEquals(emptyList<ToolReference>(), referenceAnswer(literal, listOf(receipt())).references)
    }

    @Test fun `failed unrelated and legacy results do not issue buttons`() {
        assertEquals(emptyList<ToolReference>(), referenceAnswer(token, listOf(receipt(outcome = ToolOutcome.FAILED))).references)
        assertEquals(emptyList<ToolReference>(), referenceAnswer(token, listOf(receipt(name = "terminal_read"))).references)
        assertEquals(emptyList<ToolReference>(), referenceAnswer(token, emptyList()).references)
    }
    @Test fun `only the profile result can offer a saved tunnel`() {
        val tunnel = ToolReference(
            "[tunnel:123]", label = "Work · router · Web",
            tunnel = TunnelReference("profile", "connection", "router", 443, "WEB", true),
        )
        val calls = listOf("profile_information", "terminal_tabs")
        for (name in calls) {
            val call = ToolCall("c", name, buildJsonObject {})
            val result = ToolResult("c", name, "row", ToolOutcome.SUCCESS, references = listOf(tunnel))
            val answer = referenceAnswer("Dashboard\n\n${tunnel.token}", listOf(ChatToolReceipt(call, result)))
            assertEquals(if (name == "profile_information") listOf(tunnel) else emptyList(), answer.references)
        }
    }
    @Test fun `only attached same turn references become actions`() {
        val item = ru.adsrv.frpjump.assistant.chat.ChatAttachment("ssh_session", "Work · me@host", profileId = "p", device = "host", sessionId = 1, reference = token)
        assertEquals(listOf(ToolReference(token, label = item.label)), referenceAnswer("Here\n\n$token", emptyList(), listOf(item)).references)
        assertEquals(emptyList<ToolReference>(), referenceAnswer("Here\n\n$token", emptyList()).references)
    }

}
