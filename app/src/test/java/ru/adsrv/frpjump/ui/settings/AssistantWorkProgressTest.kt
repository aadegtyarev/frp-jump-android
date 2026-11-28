package ru.adsrv.frpjump.ui.settings

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.chat.ChatToolReceipt

class AssistantWorkProgressTest {
    private fun receipt(name: String, outcome: ToolOutcome?, started: Boolean? = null): ChatToolReceipt {
        val call = ToolCall("id", name, buildJsonObject {})
        return ChatToolReceipt(call, outcome?.let { ToolResult("id", name, "private output", it) }, started = started)
    }
    @Test fun `only actual steps are shown with outcomes and repeated calls counted`() {
        val calls = listOf(receipt("profile_information", ToolOutcome.SUCCESS, true), receipt("profile_information", ToolOutcome.SUCCESS, true),
            receipt("terminal_tabs", ToolOutcome.FAILED, true), receipt("ssh_command", ToolOutcome.DECLINED, false),
            receipt("web_search", ToolOutcome.FAILED, false), receipt("terminal_read", null), receipt("unknown", ToolOutcome.SUCCESS, true))
        assertEquals(listOf(WorkStep(AssistantToolId.PROFILE_INFORMATION, ToolOutcome.SUCCESS, 2), WorkStep(AssistantToolId.TERMINAL_TABS, ToolOutcome.FAILED)), workSteps(calls))
    }
    @Test fun `legacy successful results are retained but ambiguous failures are omitted`() {
        assertEquals(listOf(WorkStep(AssistantToolId.TERMINAL_TABS, ToolOutcome.SUCCESS)), workSteps(listOf(
            receipt("terminal_tabs", ToolOutcome.SUCCESS), receipt("ssh_command", ToolOutcome.FAILED), receipt("terminal_read", ToolOutcome.TIMEOUT))))
    }
}
