package ru.adsrv.frpjump.assistant.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolResult
import ru.adsrv.frpjump.assistant.AssistantRequestTool
import ru.adsrv.frpjump.assistant.AssistantToolId

class ChatReceiptGroupsTest {
    private fun call(name: String, vararg args: Pair<String, String>) =
        ChatToolReceipt(ToolCall("c", name, buildJsonObject { args.forEach { (k, v) -> put(k, v) } }))
    private fun detail(name: String, vararg args: Pair<String, String>) = receiptDetail(call(name, *args).call)

    @Test fun `consecutive calls of one tool form one group in order`() {
        val groups = groupReceipts(listOf(call("profile_information"), call("profile_information"), call("ssh_command"), call("ssh_command"), call("ssh_command")))
        assertEquals(listOf("profile_information" to 2, "ssh_command" to 3), groups.map { it.tool to it.items.size })
    }

    @Test fun `calls interleaved with another tool stay separate`() {
        val groups = groupReceipts(listOf(call("ssh_command"), call("web_search"), call("ssh_command"), call("ssh_command")))
        assertEquals(listOf("ssh_command" to 1, "web_search" to 1, "ssh_command" to 2), groups.map { it.tool to it.items.size })
        assertTrue(groupReceipts(emptyList()).isEmpty())
    }

    @Test fun `details per tool`() {
        assertEquals("uptime", detail("ssh_command", "command" to "uptime"))
        assertEquals("kernel news", detail("web_search", "query" to "kernel news"))
        assertEquals("example.org/a/b", detail("read_page", "url" to "https://user:pw@example.org/a/b?token=1#x"))
        assertEquals("example.org", detail("read_page", "url" to "https://example.org/"))
        assertEquals("cat.png", detail("inspect_image", "url" to "https://example.org/img/cat.png", "question" to "what"))
        assertEquals("example.org", detail("inspect_image", "url" to "https://example.org/"))
        assertNull(detail("unknown_tool", "command" to "x"))
        assertNull("an older turn without arguments", detail("ssh_command"))
    }

    private fun info(vararg args: Pair<String, Any>) = ToolCall("c", "profile_information", buildJsonObject {
        args.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v as String) } })
    private val names = mapOf("0b9f3c1e-5d2a-4e7b-9a41-7c6d8e2f1a30" to "Дача")
    private val ru = ReceiptWords("все", { "с $it-й записи" }, toolTitle = { if (it == "web_search") "Поиск в интернете" else null },
        profileName = names::get, connectionName = { p, c -> if (p == "work" && c == "work-root") "Щиток" else null })

    @Test fun `profile information detail reads query, profile and page`() {
        assertEquals("«board1500»", receiptDetail(info("query" to "board1500")))
        assertEquals("all", receiptDetail(info()))
        assertEquals("all", receiptDetail(info("query" to "  ")))
        assertEquals("все", receiptDetail(info(), ru))
        assertEquals("все · с 14-й записи", receiptDetail(info("offset" to 13), ru))
        assertEquals("all · from record 14", receiptDetail(info("offset" to 13)))
        assertEquals("все", receiptDetail(info("offset" to 0), ru))
        assertEquals("«щиток» · с 14-й записи", receiptDetail(info("query" to "щиток", "offset" to 13), ru))
        // A profile shows by its name when the screen knows it, else by the first eight characters of its id.
        assertEquals("Дача", receiptDetail(info("profile_id" to "0b9f3c1e-5d2a-4e7b-9a41-7c6d8e2f1a30"), ru))
        assertEquals("0b9f3c1e", receiptDetail(info("profile_id" to "0b9f3c1e-5d2a-4e7b-9a41-7c6d8e2f1a30")))
        assertEquals("«q» · Дача · с 3-й записи", receiptDetail(info("query" to "q", "profile_id" to "0b9f3c1e-5d2a-4e7b-9a41-7c6d8e2f1a30", "offset" to 2), ru))
        // The page part survives a long query.
        assertEquals("«" + "a".repeat(24) + "…» · from record 14", receiptDetail(info("query" to "a".repeat(60), "offset" to 13)))
    }

    @Test fun `ssh detail names the target only where it is known`() {
        val ssh = ToolCall("c", "ssh_command", buildJsonObject { put("profile_id", "work"); put("connection_id", "work-root"); put("command", "uptime") })
        assertEquals("Щиток · uptime", receiptDetail(ssh, ru))
        assertEquals("uptime", receiptDetail(ssh))
        assertEquals("uptime", receiptDetail(ToolCall("c", "ssh_command", buildJsonObject { put("profile_id", "x"); put("connection_id", "y"); put("command", "uptime") }), ru))
    }

    @Test fun `a request to turn a tool on shows the tool's title, never its wire name`() {
        assertEquals("Поиск в интернете", receiptDetail(ToolCall("c", "request_tool", buildJsonObject { put("name", "web_search"); put("why", "x") }), ru))
        assertNull(receiptDetail(ToolCall("c", "request_tool", buildJsonObject { put("name", "web_search") })))
        assertNull(receiptDetail(ToolCall("c", "request_tool", buildJsonObject { put("name", "nonsense") }), ru))
    }

    /** A tool added to the catalogue without a detail fails here: its calls would show as anonymous lines. */
    @Test fun `every tool of the catalogue has a detail for typical arguments`() {
        val title = ReceiptWords("all", { "from $it" }, toolTitle = { AssistantToolId.fromWire(it)?.name })
        val typical = mapOf(
            "web_search" to mapOf("query" to "kernel news"), "read_page" to mapOf("url" to "https://example.org/a"),
            "inspect_image" to mapOf("url" to "https://example.org/cat.png", "question" to "what"),
            "mqtt_publish" to mapOf("profile_id" to "p", "connection_id" to "c", "topic" to "sensors/x", "payload" to "private"),
            "http_post" to mapOf("profile_id" to "p", "connection_id" to "c", "path" to "/status", "content_type" to "text/plain", "body" to "private"),
            "http_get" to mapOf("profile_id" to "p", "connection_id" to "c", "path" to "/status"),
            "mqtt_read" to mapOf("profile_id" to "p", "connection_id" to "c", "filter" to "sensors/+"),
            "profile_information" to emptyMap(), "read_skill" to mapOf("name" to "restart-nginx"), "skill_save" to mapOf("name" to "restart-nginx"), "skill_delete" to mapOf("name" to "restart-nginx"), "ssh_command" to mapOf("profile_id" to "p", "connection_id" to "c", "command" to "uptime"),
            AssistantRequestTool.WIRE to mapOf("name" to "web_search", "why" to "x"))
        // terminal_tabs takes no arguments, so there is nothing to tell its calls apart by; terminal_read has its own test below.
        val catalogue = AssistantToolId.entries.map { it.wire } + AssistantRequestTool.WIRE - "terminal_tabs" - "terminal_read" - "terminal_rename" - "terminal_input" - "start_tunnel" - "stop_tunnel" - "wait_for" - "terminal_open" - "terminal_show" - "terminal_close" - "skills_list"
        catalogue.forEach { wire ->
            val args = typical[wire] ?: error("no typical arguments for $wire: add them here and a detail in receiptDetail")
            val text = receiptDetail(call(wire, *args.map { it.key to it.value }.toTypedArray()).call, title)
            assertTrue("$wire has no detail", !text.isNullOrBlank())
            assertFalse("$wire detail shows the wire name: $text", text!!.contains(wire))
        }
    }

    @Test fun `the call line of a tunnel tool is the tunnel's name alone, whole, and nothing before it is known`() {
        val start = ToolCall("s1", "start_tunnel", buildJsonObject { put("profile_id", "p1"); put("connection_id", "c1") })
        val stop = ToolCall("s2", "stop_tunnel", buildJsonObject { put("profile_id", "p1"); put("connection_id", "c1") })
        // Nothing names the tunnel yet: the title alone (no profile id cut to eight characters).
        assertNull(receiptDetail(start)); assertNull(receiptDetail(stop))
        // The received facts of profile_information name it.
        val facts = ReceiptWords("all", { "from $it" }, connectionName = { p, c -> if (p == "p1" && c == "c1") "pi ssh" else null })
        assertEquals("\"\u2068pi ssh\u2069\"", receiptDetail(start, facts))
        // Else its own reply does.
        val replies = tunnelNames(listOf(ChatToolReceipt(start, ToolResult("s1", "start_tunnel", "Tunnel pi ssh is on. The device takes connections.", ToolOutcome.SUCCESS)),
            ChatToolReceipt(stop, ToolResult("s2", "stop_tunnel", "Tunnel pi ssh is off. Its terminal and MQTT sessions closed; tmux sessions on the device stay.", ToolOutcome.SUCCESS)),
            ChatToolReceipt(ToolCall("s3", "stop_tunnel", buildJsonObject { }), ToolResult("s3", "stop_tunnel", "Tunnel a b was not on. Nothing was stopped.", ToolOutcome.FAILED))))
        assertEquals(mapOf("s1" to "pi ssh", "s2" to "pi ssh", "s3" to "a b"), replies)
        assertEquals("\"\u2068pi ssh\u2069\"", receiptDetail(stop, ReceiptWords("all", { "from $it" }, tunnelName = replies::get)))
        // A long name is shown up to the call-line limit, not cut to a few characters.
        val long = ReceiptWords("all", { "from $it" }, tunnelName = { "n".repeat(30) })
        assertEquals("\"\u2068${"n".repeat(30)}\u2069\"", receiptDetail(start, long))
    }

    private fun read(id: Int) = ToolCall("c", "terminal_read", buildJsonObject { put("tab_id", id) })
    private fun tabsResult(text: String, outcome: ToolOutcome = ToolOutcome.SUCCESS) =
        ChatToolReceipt(ToolCall("t", "terminal_tabs", buildJsonObject { }), ToolResult("t", "terminal_tabs", text, outcome))

    @Test fun `terminal_read names the tab this turn's terminal_tabs result names, else its id`() {
        val result = tabsResult("tab_id: 3 | profile: Дача (profile_id: p1) | device: щиток | tunnel: SSH | user: root | tab: 2 | state: connected | tmux: no\n" +
            "tab_id: 4 | profile: Home | A (profile_id: p2) | device: pi | user: pi | tab: 1 | state: closed | tmux: yes | program: vim")
        val labels = tabLabels(listOf(result))
        assertEquals(setOf(3, 4), labels.keys)
        assertEquals(TabLabel("Дача", "root", "щиток", 2), labels[3])
        val ru = ReceiptWords("все", { "с $it" }, tabLabel = labels::get,
            tabNamed = { p, u, d, n -> "$p · $u@$d №$n" }, tabPlain = { "вкладка $it" })
        assertEquals("Дача · root@щиток №2", receiptDetail(read(3), ru))
        assertEquals("вкладка 9", receiptDetail(read(9), ru))
        // Without a terminal_tabs result in the turn, and with the default words.
        assertEquals("tab 3", receiptDetail(read(3)))
        assertEquals("tab 4", receiptDetail(read(4), ReceiptWords("all", { "from $it" }, tabLabel = tabLabels(listOf(tabsResult("x", ToolOutcome.FAILED)))::get)))
        // No usable tab_id: no detail.
        assertNull(receiptDetail(ToolCall("c", "terminal_read", buildJsonObject { })))
        assertNull(receiptDetail(ToolCall("c", "terminal_read", buildJsonObject { put("tab_id", "3") })))
        // A failed or foreign result names nothing; garbage lines are skipped.
        assertTrue(tabLabels(listOf(tabsResult("No open terminal tabs."), tabsResult("tab_id: x | user: y"))).isEmpty())
        // Each part is cut on its own, and the number is never the part lost.
        val long = tabLabels(listOf(tabsResult("tab_id: 5 | profile: ${"P".repeat(60)} (profile_id: p) | device: ${"d".repeat(60)} | user: ${"u".repeat(60)} | tab: 7 | state: connected | tmux: no")))
        val text = receiptDetail(read(5), ReceiptWords("all", { "from $it" }, tabLabel = long::get))!!
        assertEquals("${"P".repeat(24)}… · ${"u".repeat(24)}…@${"d".repeat(24)}… #7", text)
    }

    @Test fun `terminal_read receipts of different tabs stay on their own running lines`() {
        val reads = listOf(read(3), read(3), read(4)).map { ChatToolReceipt(it) }
        assertEquals(listOf(2, 1), groupReceipts(reads, sameDetail = true).map { it.items.size })
        assertEquals(listOf(3), groupReceipts(reads).map { it.items.size })
    }

    @Test fun `the expanded body is the result alone, with the full command for ssh and a word for a non-success`() {
        val ssh = ToolCall("c", "ssh_command", buildJsonObject { put("profile_id", "p"); put("connection_id", "c"); put("command", "uptime && echo ‮x\nsecond " + "y".repeat(80)) })
        val shown = receiptBody(ssh, null, "up 3 days")
        assertTrue(shown, shown.startsWith("$ uptime && echo "))
        assertTrue(shown.endsWith("\nup 3 days"))
        assertFalse(shown, shown.contains("ssh_command") || shown.contains("profile_id") || shown.contains('\u202E'))
        assertTrue("the whole command, not the label's cut", shown.contains("y".repeat(80)))
        assertEquals("$ uptime\nFailed\nboom", receiptBody(ToolCall("c", "ssh_command", buildJsonObject { put("command", "uptime") }), "Failed", "boom"))
        // Other tools: no wire name, no arguments, no outcome for a success.
        assertEquals("tab 1: x", receiptBody(read(1), null, "tab 1: x"))
        assertEquals("Failed\nCould not read tab 1.", receiptBody(read(1), "Failed", "Could not read tab 1."))
        assertEquals("", receiptBody(ToolCall("c", "terminal_tabs", buildJsonObject { }), null, ""))
        assertEquals("a result", receiptBody(call("web_search", "query" to "q").call, null, "a result"))
        // An ssh call without a command (an older saved call) shows no command line.
        assertEquals("r", receiptBody(call("ssh_command").call, null, "r"))
    }

    private fun input(id: Int, text: String? = null, vararg keys: String) = ToolCall("c", "terminal_input", buildJsonObject {
        put("tab_id", id)
        if (text != null) put("text", text)
        if (keys.isNotEmpty()) put("keys", kotlinx.serialization.json.JsonArray(keys.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    })

    @Test fun `terminal_input names its tab like terminal_read, by the same turn's terminal_tabs result`() {
        val labels = tabLabels(listOf(tabsResult("tab_id: 3 | profile: Дача (profile_id: p1) | device: щиток | user: root | tab: 2 | state: connected | tmux: no")))
        val ru = ReceiptWords("все", { "с $it" }, tabLabel = labels::get, tabNamed = { p, u, d, n -> "$p · $u@$d №$n" }, tabPlain = { "вкладка $it" })
        assertEquals("Дача · root@щиток №2", receiptDetail(input(3, "ls"), ru))
        assertEquals("вкладка 9", receiptDetail(input(9, "ls"), ru))
        assertEquals("tab 3", receiptDetail(input(3, "ls")))
        assertNull(receiptDetail(ToolCall("c", "terminal_input", buildJsonObject { put("text", "x") })))
    }

    @Test fun `terminal_input receipts differ by what was typed`() {
        val calls = listOf(input(3, "ls"), input(3, "ls"), input(3, "pwd"), input(3, null, "enter"), input(4, "ls")).map { ChatToolReceipt(it) }
        assertEquals(listOf(2, 1, 1, 1), groupReceipts(calls, sameDetail = true).map { it.items.size })
    }

    @Test fun `the terminal_input body shows the typed text with visible escapes, then the keys, then the outcome and the text of a failure`() {
        // A successful input shows the input only, not the cursor it gave the model; a refusal or failure shows its text.
        assertEquals("ls -l${ENTER_GLYPH}", receiptBody(input(3, "ls -l\n"), null, "Typed into tab 3. cursor: 3.0badf00d"))
        assertEquals("ls -l${ENTER_GLYPH}\nFailed\nTab 3 is not open. Nothing was typed.", receiptBody(input(3, "ls -l\n"), "Failed", "Tab 3 is not open. Nothing was typed."))
        assertEquals("echo a${ENTER_GLYPH}\necho b\nKeys: Enter, Ctrl+C\nFailed\nTab 3 is not open.",
            receiptBody(input(3, "echo a\r\necho b", "enter", "ctrl_c"), "Failed", "Tab 3 is not open."))
        // Hidden characters are escapes, never invisible; keys alone give only the keys line.
        assertEquals("a${displayCommand("\u202e")}b", receiptBody(input(3, "a\u202eb"), null, ""))
        assertEquals("Keys: Up, PgDn", receiptBody(input(3, null, "up", "page_down"), null, ""))
        assertEquals("Клавиши: Enter", receiptBody(input(3, null, "enter"), null, "", keysLine = { "Клавиши: " + it.joinToString(", ") }))
        // A typed ⏎ is not mistaken for our mark; an unknown key name is left out.
        assertEquals("a⟨U+23CE⟩b", receiptBody(input(3, "a⏎b"), null, ""))
        assertEquals("Keys: Tab", receiptBody(input(3, null, "tab", "f5"), null, ""))
        // Other tools never show typed text.
        assertEquals("r", receiptBody(call("terminal_read", "text" to "x").call, null, "r"))
    }

    @Test fun `running grouping joins only calls equal in tool and detail`() {
        val pages = listOf(info("offset" to 0), info("offset" to 13), info("offset" to 13), info("query" to "x")).map { ChatToolReceipt(it) }
        assertEquals(listOf(1, 2, 1), groupReceipts(pages, sameDetail = true).map { it.items.size })
        assertEquals(listOf(4), groupReceipts(pages).map { it.items.size })
        val ssh = listOf("uptime", "uptime", "df").map { call("ssh_command", "command" to it) }
        assertEquals(listOf(2, 1), groupReceipts(ssh, sameDetail = true).map { it.items.size })
    }

    @Test fun `cut at forty characters with an ellipsis`() {
        val text = detail("ssh_command", "command" to "a".repeat(60))!!
        assertEquals("a".repeat(40) + "…", text)
        assertEquals("a".repeat(40), detail("ssh_command", "command" to "a".repeat(40)))
    }

    @Test fun `cut never splits a surrogate pair`() {
        val smile = "😀"
        val text = receiptText("a".repeat(39) + smile + "tail", 40)
        assertEquals("a".repeat(39) + smile + "…", text)
        assertEquals("a".repeat(39) + "…", receiptText("a".repeat(39) + smile + "x", 39))
        assertEquals("a".repeat(39) + smile, receiptText("a".repeat(39) + smile, 40))
    }

    @Test fun `control bidi and invisible characters are removed and lines joined`() {
        assertEquals("ls -la", receiptText("ls\n\r\t  -la", 40))
        assertEquals("abc", receiptText("a‮b​\u0000c⁦", 40))
        assertEquals("a b", receiptText("a b", 40))
        assertEquals("a b", receiptText("  a  b  ", 40))
        assertEquals("", receiptText("‮\u0007", 40))
        assertEquals("ab", receiptText("a\uD800b", 40))
    }
}
