package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.agent.SideEffectAdmission
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolResult
import ru.adsrv.frpjump.assistant.chat.ChatToolReceipt
import ru.adsrv.frpjump.assistant.chat.ReceiptWords
import ru.adsrv.frpjump.assistant.chat.TabLabel
import ru.adsrv.frpjump.assistant.chat.receiptBody
import ru.adsrv.frpjump.assistant.chat.receiptDetail
import ru.adsrv.frpjump.assistant.chat.tabLabels
import ru.adsrv.frpjump.assistant.chat.tabRenames
import ru.adsrv.frpjump.ssh.ExecLease
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ssh.TAB_NAME_MAX

/** `terminal_rename` and the tab name in `terminal_tabs`, over fake tabs: no SSH client, no terminal, no network. */
class AssistantTerminalRenameTest {
    /** A tab that records every rename and every use of its connection or buffer. */
    private class Tab(
        override val id: Int, override val user: String = "pi", override val device: String = "wb7", override val number: Int = 1,
        override var state: SshState = SshState.Open, override var customName: String? = null,
    ) : TabHandle {
        override val profileId = "p1"
        override val port = 22
        override val tmuxName: String? = null
        val renames = ArrayList<String?>()
        var connectionUses = 0
        override fun rename(name: String?) { renames += name; customName = name }
        override fun exec(command: String, maxBytes: Int, lease: ExecLease): String? { connectionUses++; return "" }
        override fun bufferText(lines: Int): String { connectionUses++; return "" }
    }

    private fun tabs(vararg list: Tab, hidden: Set<String> = emptySet()) = AssistantTerminalTabs(
        tabs = { list.toList() }, hidden = { hidden }, profileName = { "Home" }, connectionName = { _, _, _ -> null },
        io = Dispatchers.IO, main = Dispatchers.Unconfined,
    )

    private fun rename(owner: AssistantTerminalTabs, id: Any, name: Any? = "logs", extra: Map<String, String> = emptyMap()) = runBlocking {
        AssistantTerminalRenameTool(owner).execute(buildJsonObject {
            put("tab_id", if (id is Int) JsonPrimitive(id) else JsonPrimitive(id as String))
            if (name != null) put("name", name as String)
            extra.forEach { (k, v) -> put(k, v) }
        })
    }

    @Test fun `the approved description, schema and switch`() {
        val tool = AssistantTerminalRenameTool(tabs())
        assertEquals("Rename one open terminal tab by tab_id from terminal_tabs, as the owner can from the tab's menu. `name`: up to 24 characters on one line; an empty name brings back the automatic one. Changes only the tab's label in this app, nothing on the device.", tool.spec.description)
        assertEquals("terminal_rename", tool.spec.name)
        val props = tool.spec.parameters.getValue("properties").jsonObject
        assertEquals(buildJsonObject { put("type", "integer"); put("minimum", 1) }, props.getValue("tab_id"))
        // The length in the schema and the text is the owner's rule's own, not a second number.
        assertEquals(TAB_NAME_MAX, props.getValue("name").jsonObject.getValue("maxLength").jsonPrimitive.content.toInt())
        assertEquals(listOf("tab_id", "name"), tool.spec.parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(ru.adsrv.frpjump.ops.Effect.CHANGE_LOCAL, tool.effect)
        assertFalse(AssistantToolId.TERMINAL_RENAME.confirms)
        // No confirmation card: a local label change that the catalog marks as not confirming.
        assertTrue(tool.changeCheck(buildJsonObject { put("tab_id", 1); put("name", "x") }) is ru.adsrv.frpjump.assistant.tools.ChangeCheck.ReadOnly)
    }

    @Test fun `the replies are the approved ones`() {
        val tab = Tab(2)
        val owner = tabs(tab)
        assertEquals("Tab 2 is now named \"logs\".", rename(owner, 2).text)
        assertEquals("logs", tab.customName)
        assertEquals("Tab 2 has its automatic name again.", rename(owner, 2, "").text)
        assertNull(tab.customName)
        val closed = rename(owner, 9)
        assertEquals("Tab 9 is closed. Nothing was renamed.", closed.text)
        assertEquals(ToolOutcome.FAILED, closed.outcome)
        val invalid = rename(owner, 2, "a|b")
        assertEquals("Provide tab_id from terminal_tabs and name: up to 24 characters, one line, no control characters.", invalid.text)
        assertEquals(ToolOutcome.FAILED, invalid.outcome)
    }

    @Test fun `the name goes through the owner's rule, trimmed`() {
        val tab = Tab(1)
        val owner = tabs(tab)
        assertEquals("Tab 1 is now named \"build box\".", rename(owner, 1, "  build box \t").text)
        assertEquals("build box", tab.customName)
        // The same refusals as the owner's dialog; nothing changes.
        for (bad in listOf("x".repeat(TAB_NAME_MAX + 1), "a\nb", "a​b", "a‮b", "a|b", "a\u0007b")) {
            val out = rename(owner, 1, bad)
            assertEquals(bad, ToolOutcome.FAILED, out.outcome)
            assertEquals(bad, "build box", tab.customName)
        }
        assertEquals(1, tab.renames.size)
        // 24 code points of an emoji is fine.
        val emoji = "😀".repeat(TAB_NAME_MAX)
        assertEquals("Tab 1 is now named \"$emoji\".", rename(owner, 1, emoji).text)
    }

    @Test fun `bad arguments rename nothing`() {
        val tab = Tab(1)
        val owner = tabs(tab)
        for (out in listOf(rename(owner, 1, null), rename(owner, "1"), rename(owner, 0), rename(owner, 1, "x", mapOf("lines" to "3")))) {
            assertEquals(ToolOutcome.FAILED, out.outcome)
            assertTrue(out.text, out.text.startsWith("Provide tab_id"))
        }
        assertTrue(tab.renames.isEmpty())
    }

    @Test fun `a closed or hidden tab is not renamed`() {
        val ended = Tab(1, state = SshState.Ended("gone"))
        val hidden = Tab(2)
        val owner = tabs(ended, hidden, hidden = setOf(ru.adsrv.frpjump.undo.PendingUndo.sessionKey(ru.adsrv.frpjump.tunnel.SessionKind.TERMINAL, 2)))
        assertEquals("Tab 1 is closed. Nothing was renamed.", rename(owner, 1).text)
        assertEquals("Tab 2 is closed. Nothing was renamed.", rename(owner, 2).text)
        assertTrue(ended.renames.isEmpty() && hidden.renames.isEmpty())
    }

    @Test fun `renaming never touches the connection or the screen`() {
        val tab = Tab(1)
        val owner = tabs(tab)
        rename(owner, 1, "logs"); rename(owner, 1, "")
        assertEquals(0, tab.connectionUses)
        assertEquals(listOf<String?>("logs", null), tab.renames)
    }

    @Test fun `terminal_tabs lists the name only when one is set`() {
        val list = listOf(
            TerminalTab(1, "p1", "Home", "wb7", "pi", 2, null, TabState.CONNECTED, false, name = "logs"),
            TerminalTab(2, "p1", "Home", "wb7", "pi", 3, null, TabState.CONNECTED, false),
        )
        val lines = tabsText(list, emptyMap()).lines()
        assertEquals("tab_id: 1 | name: logs | profile: Home (profile_id: p1) | device: wb7 | user: pi | tab: 2 | state: connected | tmux: no", lines[0])
        assertEquals("tab_id: 2 | profile: Home (profile_id: p1) | device: wb7 | user: pi | tab: 3 | state: connected | tmux: no", lines[1])
        // The listing of the tool itself carries it too.
        val out = runBlocking { AssistantTerminalTabsTool(tabs(Tab(7, customName = "logs"))).execute(buildJsonObject { }) }
        assertTrue(out.text, out.text.startsWith("tab_id: 7 | name: logs | profile: Home"))
    }

    // ---- what the owner sees ----

    private fun listing(text: String) = ChatToolReceipt(ToolCall("t", "terminal_tabs", buildJsonObject { }), ToolResult("t", "terminal_tabs", text, ToolOutcome.SUCCESS))
    private fun renameCall(id: String, tab: Int, name: String) = ToolCall(id, "terminal_rename", buildJsonObject { put("tab_id", tab); put("name", name) })
    private fun done(call: ToolCall) = ChatToolReceipt(call, ToolResult(call.id, call.name, "ok", ToolOutcome.SUCCESS))
    private val line = "tab_id: 2 | name: old | profile: Home (profile_id: p1) | device: wb7 | user: pi | tab: 2 | state: connected | tmux: no"

    @Test fun `a listed name is read back with the tab's user and device`() {
        val label = tabLabels(listOf(listing(line))).getValue(2)
        assertEquals(TabLabel("Home", "pi", "wb7", 2, "old"), label)
        // A name that tries to pass for another tab or to add fields stays one name: its record has no separator.
        val tricky = tabLabels(listOf(listing("tab_id: 2 | name: root@prod | profile: Home (profile_id: p1) | device: wb7 | user: pi | tab: 2 | state: connected | tmux: no"))).getValue(2)
        assertEquals("root@prod", tricky.name)
        assertEquals("pi", tricky.user)
        assertEquals("wb7", tricky.device)
    }

    @Test fun `the call line of terminal_read and terminal_rename always carries user and device with a name`() {
        val labels = tabLabels(listOf(listing(line)))
        val words = ReceiptWords("all", { "from $it" }, tabLabel = labels::get)
        val read = ToolCall("r", "terminal_read", buildJsonObject { put("tab_id", 2) })
        val input = ToolCall("i", "terminal_input", buildJsonObject { put("tab_id", 2); put("text", "ls") })
        // The identity first, the name last: bidi-isolated and in quotes.
        assertEquals("Home · pi@wb7 #2 · \"\u2068old\u2069\"", receiptDetail(read, words))
        assertEquals("Home · pi@wb7 #2 · \"\u2068old\u2069\"", receiptDetail(input, words))
        assertEquals("pi@wb7 #2 · \"\u2068logs\u2069\"", receiptDetail(renameCall("c", 2, "logs"), words))
        // The automatic name again: no name to show, the tab by profile, user and device.
        assertEquals("Home · pi@wb7 #2", receiptDetail(renameCall("c", 2, ""), words))
        // No listing names the tab: the id alone, never the model's name by itself.
        assertEquals("tab 9", receiptDetail(renameCall("c", 9, "root@prod"), words))
        assertEquals("tab 2", receiptDetail(renameCall("c", 2, "root@prod")))
    }

    @Test fun `a rename that finds the tab closed where it runs renames nothing`() {
        val tab = Tab(1)
        // The main dispatcher is where the rename runs: the tab ends between the lookup before and the work there.
        val main = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tab.state = SshState.Ended("gone"); block.run() }
        }
        val owner = AssistantTerminalTabs(tabs = { listOf(tab) }, hidden = { emptySet() }, profileName = { "Home" }, connectionName = { _, _, _ -> null }, io = Dispatchers.IO, main = main)
        assertEquals("Tab 1 is closed. Nothing was renamed.", rename(owner, 1).text)
        assertTrue(tab.renames.isEmpty())
    }

    @Test fun `the expanded rename shows before and after, following earlier renames of the turn`() {
        val receipts = listOf(listing(line), done(renameCall("a", 2, " first ")), done(renameCall("b", 2, "")), done(renameCall("c", 5, "elsewhere")))
        val changes = tabRenames(receipts)
        assertEquals("old", changes.getValue("a").before)
        assertEquals("first", changes.getValue("a").after)
        assertEquals("first", changes.getValue("b").before)
        assertNull(changes.getValue("b").after)
        assertEquals("pi", changes.getValue("a").label.user)
        // A tab no listing names has no line; a failed rename has none either.
        assertFalse("c" in changes)
        val failed = ChatToolReceipt(renameCall("d", 2, "x"), ToolResult("d", "terminal_rename", "Tab 2 is closed. Nothing was renamed.", ToolOutcome.FAILED))
        assertFalse("d" in tabRenames(listOf(listing(line), failed)))
        assertEquals("old → first\nTab 2 is now named \"first\".", receiptBody(renameCall("a", 2, "first"), null, "Tab 2 is now named \"first\".", renameLine = "old → first"))
    }
}
