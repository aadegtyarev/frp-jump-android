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
}
