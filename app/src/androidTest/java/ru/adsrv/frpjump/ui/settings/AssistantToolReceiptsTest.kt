package ru.adsrv.frpjump.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolResult
import ru.adsrv.frpjump.assistant.chat.ChatProfileInformation
import ru.adsrv.frpjump.assistant.chat.ChatToolReceipt
import ru.adsrv.frpjump.ui.FrpJumpTheme

@RunWith(AndroidJUnit4::class)
class AssistantToolReceiptsTest {
    @get:Rule val compose = createComposeRule()

    private fun receipt(id: String, name: String, vararg args: Pair<String, String>) =
        ChatToolReceipt(ToolCall(id, name, buildJsonObject { args.forEach { (k, v) -> put(k, v) } }))

    private val turn = listOf(
        receipt("1", "profile_information", "query" to "board1500"), receipt("2", "profile_information", "query" to "gate7"),
        receipt("3", "ssh_command", "command" to "uptime"), receipt("4", "ssh_command", "command" to "df -h /var/log/very-long-directory-name/more"),
        receipt("5", "ssh_command", "command" to "echo ‮hidden"))

    @Test fun groupsRenderWithCountersAndExpandToDetails() {
        compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Column { ChatToolReceipts(turn) } } }
        compose.onNodeWithTag("chat_tool_receipts").performClick()
        compose.onNodeWithTag("chat_tool_group_0").assertExists()
        compose.onNodeWithTag("chat_tool_group_1").assertExists()
        compose.onNodeWithTag("chat_tool_group_2").assertDoesNotExist()
        compose.onNodeWithTag("chat_tool_group_0").assertTextContains("×2", substring = true)
        compose.onNodeWithTag("chat_tool_group_1").assertTextContains("×3", substring = true)
        compose.onNodeWithTag("chat_tool_group_1_item_0").assertDoesNotExist()
        compose.onNodeWithTag("chat_tool_group_0").performClick()
        compose.onNodeWithTag("chat_tool_group_0_item_0").assertTextContains("board1500", substring = true)
        compose.onNodeWithTag("chat_tool_group_0_item_1").assertTextContains("gate7", substring = true)
        compose.onNodeWithTag("chat_tool_group_1").performClick()
        compose.onNodeWithTag("chat_tool_group_1_item_0").assertTextContains("uptime", substring = true)
        compose.onNodeWithTag("chat_tool_group_1_item_1").assertTextContains("df -h /var/log/very-long-directory-name/…", substring = true)
        compose.onNodeWithTag("chat_tool_group_1_item_2").assertTextContains("echo hidden", substring = true)
    }

    @Test fun groupReadsAsToolAndCallCount() {
        compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Column { ChatToolReceipts(turn) } } }
        compose.onNodeWithTag("chat_tool_receipts").performClick()
        val description = compose.onNodeWithTag("chat_tool_group_0").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assertTrue(description.orEmpty(), Regex(".*2.*").matches(description.orEmpty()) && !description.orEmpty().contains("×"))
    }

    @Test fun singleCallShowsDetailAndFullBody() {
        compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Column { ChatToolReceipts(listOf(receipt("1", "ssh_command", "command" to "uptime"))) } } }
        compose.onNodeWithTag("chat_tool_receipts").performClick()
        compose.onNodeWithTag("chat_tool_receipt_0").assertTextContains("uptime", substring = true)
        compose.onNodeWithTag("chat_tool_group_0").assertDoesNotExist()
    }

    @Test fun savedCallWithoutArgumentsShowsToolNameOnly() {
        compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Column { ChatToolReceipts(listOf(receipt("1", "ssh_command"))) } } }
        compose.onNodeWithTag("chat_tool_receipts").performClick()
        compose.onNodeWithTag("chat_tool_receipt_0").assertExists()
    }

    @Test fun finishedTurnIsOneQuietSummaryLineThatOpensAndCloses() {
        compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Column { ChatToolReceipts(turn) } } }
        val summary = compose.onNodeWithTag("chat_tool_receipts")
        summary.assertTextContains("×2", substring = true).assertTextContains("×3", substring = true).assertTextContains(" · ", substring = true)
        summary.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        fun state() = compose.onNodeWithTag("chat_tool_receipts").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        val collapsed = state()
        compose.onNodeWithTag("chat_tool_calls_list").assertDoesNotExist()
        compose.onNodeWithTag("chat_tool_receipts").performClick()
        compose.onNodeWithTag("chat_tool_calls_list").assertExists()
        assertNotEquals(collapsed, state())
        val spoken = compose.onNodeWithTag("chat_tool_receipts").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
        assertTrue(spoken, spoken.contains("2") && spoken.contains("3"))
        compose.onNodeWithTag("chat_tool_receipts").performClick()
        compose.onNodeWithTag("chat_tool_calls_list").assertDoesNotExist()
    }

    @Test fun runningTurnShowsOneLinePerCallAndNoSummary() {
        val calls = listOf(
            ChatToolReceipt(ToolCall("1", "ssh_command", buildJsonObject { put("command", "lsb_release -ds") }), ToolResult("1", "ssh_command", "ok", ToolOutcome.SUCCESS)),
            receipt("2", "ssh_command", "command" to "apt list --upgradable"))
        compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Column { ChatToolReceipts(calls, running = true) } } }
        compose.onNodeWithTag("chat_tool_call_0").assertTextContains("lsb_release -ds", substring = true)
        compose.onNodeWithTag("chat_tool_call_1").assertTextContains("apt list --upgradable", substring = true)
        compose.onNodeWithTag("chat_tool_receipts").assertDoesNotExist()
    }

    @Test fun profileInformationShowsItsFactsInsteadOfTheRawResult() {
        val call = receipt("1", "profile_information", "query" to "board1500")
        val result = ToolResult("1", "profile_information", "{\"results\":[],\"total_records\":0}", ToolOutcome.SUCCESS)
        val information = ChatProfileInformation(result, buildJsonObject { put("total_records", "0") })
        compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Column {
            ChatToolReceipts(listOf(call.copy(result = result)), listOf(information)) } } }
        compose.onNodeWithTag("chat_tool_receipts").performClick()
        compose.onNodeWithTag("chat_profile_information_facts").assertExists()
    }
}
