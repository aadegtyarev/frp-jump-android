package ru.adsrv.frpjump.agent

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolReferencesTest {
    private val call = ToolCall("c", "terminal_tabs", buildJsonObject {})
    private val token = "[terminal:opaque]"
    private val row = "tab_id: 1 | $token"
    private val ref = ToolReference(token, row)

    @Test fun `only a complete emitted row authorizes its reference`() {
        assertEquals(listOf(ref), toolResult(call, ToolOutput(row, references = listOf(ref)), 200).references)
        val truncated = "x".repeat(60) + "\n" + row + "\n" + "y".repeat(60)
        assertEquals(emptyList<ToolReference>(), toolResult(call, ToolOutput(truncated, references = listOf(ref)), 60).references)
        assertEquals(emptyList<ToolReference>(), toolResult(call, ToolOutput(token, references = listOf(ref)), 200).references)
        assertEquals(emptyList<ToolReference>(), toolResult(call, ToolOutput(row, ToolOutcome.FAILED, listOf(ref)), 200).references)
    }
}
