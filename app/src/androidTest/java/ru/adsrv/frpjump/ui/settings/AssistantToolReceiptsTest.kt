package ru.adsrv.frpjump.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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

    @Test fun runningTurnShowsOneLinePerCallOfDifferentToolsAndNoSummary() {
        val calls = listOf(
            ChatToolReceipt(ToolCall("1", "ssh_command", buildJsonObject { put("command", "lsb_release -ds") }), ToolResult("1", "ssh_command", "ok", ToolOutcome.SUCCESS)),
            receipt("2", "profile_information", "query" to "gate7"))
        compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Column { ChatToolReceipts(calls, running = true) } } }
        compose.onNodeWithTag("chat_tool_call_0").assertTextContains("lsb_release -ds", substring = true)
        compose.onNodeWithTag("chat_tool_call_1").assertTextContains("gate7", substring = true)
        compose.onNodeWithTag("chat_tool_receipts").assertDoesNotExist()
    }

    private fun page(id: String, offset: Int) = ChatToolReceipt(ToolCall(id, "profile_information", buildJsonObject { put("offset", offset) }))

    @Test fun runningCallsEqualInToolAndDetailShowAsOneLineWithACounter() {
        val done = ChatToolReceipt(ToolCall("1", "profile_information", buildJsonObject { put("query", "a") }), ToolResult("1", "profile_information", "{}", ToolOutcome.SUCCESS))
        val calls = listOf(done, receipt("2", "profile_information", "query" to "a"), receipt("3", "ssh_command", "command" to "uptime"))
        compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Column { ChatToolReceipts(calls, running = true) } } }
        compose.onNodeWithTag("chat_tool_call_0").assertTextContains("×2", substring = true)
        compose.onNodeWithTag("chat_tool_call_1").assertTextContains("uptime", substring = true)
        compose.onNodeWithTag("chat_tool_call_2").assertDoesNotExist()
        val spoken = compose.onNodeWithTag("chat_tool_call_0").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
        assertTrue(spoken, spoken.contains("2") && !spoken.contains("×"))
    }

    @Test fun runningCallsWithDifferentParametersStayOnTheirOwnLines() {
        compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Column { ChatToolReceipts(listOf(page("1", 0), page("2", 13)), running = true) } } }
        compose.onNodeWithTag("chat_tool_call_1").assertTextContains("14", substring = true)
        compose.onNode(hasTestTag("chat_tool_call_0") and hasText("×", substring = true)).assertDoesNotExist()
    }

    private fun profileFacts(id: String): Pair<ToolResult, ChatProfileInformation> {
        val result = ToolResult(id, "profile_information", "{\"results\":[],\"total_records\":0}", ToolOutcome.SUCCESS)
        return result to ChatProfileInformation(result, buildJsonObject { put("total_records", "0") })
    }

    @Test fun oneGroupOfSeveralCallsShowsItsItemsAtOnceWithoutAWireName() {
        val (r1, i1) = profileFacts("1"); val (r2, i2) = profileFacts("2")
        val calls = listOf(page("1", 0).copy(result = r1), page("2", 13).copy(result = r2))
        compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Column { ChatToolReceipts(calls, listOf(i1, i2)) } } }
        compose.onNodeWithTag("chat_tool_receipts").performClick()
        // The summary line already named the group: no second header with the same text, the items are there after one tap.
        compose.onNodeWithTag("chat_tool_group_0").assertDoesNotExist()
        compose.onNodeWithTag("chat_tool_group_0_item_0").assertExists()
        compose.onNodeWithTag("chat_tool_group_0_item_1").assertExists()
        compose.onAllNodesWithTag("chat_profile_information_facts").assertCountEquals(2)
        compose.onAllNodesWithText("profile_information", substring = true).assertCountEquals(0)
        // Each item is told apart by its parameters: the second page says from which record it starts.
        // (the facts are separate lines, so the label is a child of the item)
        compose.onNode(hasAnyAncestor(hasTestTag("chat_tool_group_0_item_1")) and hasText("14", substring = true)).assertExists()
        compose.onNode(hasAnyAncestor(hasTestTag("chat_tool_group_0_item_0")) and hasText("14", substring = true)).assertDoesNotExist()
    }

    @Test fun itemsWithNothingToShowAreNumberedUnderTheirTitle() {
        compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Column { ChatToolReceipts(listOf(receipt("1", "ssh_command"), receipt("2", "ssh_command"))) } } }
        compose.onNodeWithTag("chat_tool_receipts").performClick()
        compose.onNodeWithTag("chat_tool_group_0").assertDoesNotExist()
        compose.onNodeWithTag("chat_tool_group_0_item_0").assertTextContains(" · 1", substring = true)
        compose.onNodeWithTag("chat_tool_group_0_item_1").assertTextContains(" · 2", substring = true)
    }

    @Test fun severalGroupsKeepTheirHeaders() {
        compose.setContent { FrpJumpTheme { androidx.compose.foundation.layout.Column { ChatToolReceipts(turn) } } }
        compose.onNodeWithTag("chat_tool_receipts").performClick()
        compose.onNodeWithTag("chat_tool_group_0").assertExists(); compose.onNodeWithTag("chat_tool_group_1").assertExists()
        compose.onNodeWithTag("chat_tool_group_0_item_0").assertDoesNotExist()
    }

    @Test fun profileIdShowsOnlyOnTheProfileRecord() {
        val uuid = "0b9f3c1e-5d2a-4e7b-9a41-7c6d8e2f1a30"
        val page = Json.parseToJsonElement("""{"total_records":3,"source_status":{"complete":true},"results":[
            {"profile":{"id":"$uuid","name":"Synthetic"}},
            {"profile":{"id":"$uuid","name":"Synthetic"},"device":{"name":"board1","label":"L","online":true}},
            {"profile":{"id":"$uuid","name":"Synthetic"},"device":{"name":"board2","online":false}}]}""").jsonObject
        val result = ToolResult("1", "profile_information", "{}", ToolOutcome.SUCCESS)
        compose.setContent { FrpJumpTheme { ChatProfileInformationFacts(ChatProfileInformation(result, page)) } }
        compose.onAllNodesWithText(uuid).assertCountEquals(1)
        compose.onNodeWithText("Synthetic · board1 · L").assertExists()
        compose.onNodeWithText("Synthetic · board2").assertExists()
    }

    @Test fun factsAreQuietLikeTheOtherToolOutput() {
        val page = Json.parseToJsonElement("""{"total_records":1,"results":[{"profile":{"id":"p","name":"Synthetic"},"device":{"name":"board1","online":true}}]}""").jsonObject
        val result = ToolResult("1", "profile_information", "{}", ToolOutcome.SUCCESS)
        var small = 0f
        compose.setContent { FrpJumpTheme { small = MaterialTheme.typography.bodySmall.fontSize.value; ChatProfileInformationFacts(ChatProfileInformation(result, page)) } }
        val sizes = compose.onAllNodes(hasAnyAncestor(hasTestTag("chat_profile_information_facts")) and hasText("Synthetic", substring = true)).fetchSemanticsNodes().map { node ->
            val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
            out.first().layoutInput.style.fontSize.value
        }
        assertTrue(sizes.isNotEmpty())
        assertTrue("facts at $sizes, small text is $small", sizes.all { it <= small })
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
