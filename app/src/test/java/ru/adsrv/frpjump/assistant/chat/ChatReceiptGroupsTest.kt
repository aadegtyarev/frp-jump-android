package ru.adsrv.frpjump.assistant.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.ToolCall
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
            "profile_information" to emptyMap(), "ssh_command" to mapOf("profile_id" to "p", "connection_id" to "c", "command" to "uptime"),
            AssistantRequestTool.WIRE to mapOf("name" to "web_search", "why" to "x"))
        val catalogue = AssistantToolId.entries.map { it.wire } + AssistantRequestTool.WIRE
        catalogue.forEach { wire ->
            val args = typical[wire] ?: error("no typical arguments for $wire: add them here and a detail in receiptDetail")
            val text = receiptDetail(call(wire, *args.map { it.key to it.value }.toTypedArray()).call, title)
            assertTrue("$wire has no detail", !text.isNullOrBlank())
            assertFalse("$wire detail shows the wire name: $text", text!!.contains(wire))
        }
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
