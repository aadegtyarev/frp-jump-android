package ru.adsrv.frpjump.assistant.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.ToolCall

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
        assertEquals("board1500", detail("profile_information", "query" to "board1500"))
        assertEquals("board · p1", detail("profile_information", "query" to "board", "profile_id" to "p1"))
        assertEquals("p1", detail("profile_information", "profile_id" to "p1"))
        assertNull(detail("profile_information", "query" to ""))
        assertNull(detail("unknown_tool", "command" to "x"))
        assertNull("an older turn without arguments", detail("ssh_command"))
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
