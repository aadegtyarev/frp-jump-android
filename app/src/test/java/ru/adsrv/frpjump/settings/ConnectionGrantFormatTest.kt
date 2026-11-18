package ru.adsrv.frpjump.settings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.ssh.MemoryStorage

/** The stored form of a persistent record in `assistant_confirmations.overrides` and its strict reader (fail closed, each field on its own). */
class ConnectionGrantFormatTest {
    private val on = """"assistant_enabled":true,"assistant_ssh_command":true,"assistant_terminal_tabs":true,"assistant_terminal_input":true"""
    private fun valid(scope: String = """{"kind":"target","id":"p","sub":"c"}""", cls: String = "\"class\":\"connection_change\"", mode: String = "\"mode\":\"off\"",
        fp: String = "\"fp\":\"v1:abc\"", tools: String = "\"tools\":[\"ssh_command\",\"terminal_input\"]", at: String = "\"at\":1763020800000") =
        listOf("\"scope\":$scope", cls, mode, fp, tools, at).filter { it.isNotEmpty() }.joinToString(",", "{", "}")
    private fun store(overrides: String, global: String = "off", version: String = "1", extra: String = on) =
        SettingsStore(MemoryStorage("""{"direct":false,$extra,"assistant_confirmations":{"version":$version,"global":"$global","overrides":$overrides}}"""))
    private fun records(overrides: String, global: String = "off", version: String = "1", extra: String = on) = store(overrides, global, version, extra).assistantConfirmations.overrides

    @Test fun `golden JSON round trip and an empty list leaves the file as it was`() {
        val file = MemoryStorage(); val s = SettingsStore(file)
        s.update { it.copy(assistantEnabled = true, assistantSshCommand = true) }
        val before = file.text!!
        s.setAssistantConfirmations(ConfirmationMode.ASK); assertEquals("with no record the file is unchanged", before, file.text)
        assertFalse("the empty array is omitted", before.contains("overrides"))
        val rec = ConfirmationOverride(ConfirmationScope("target", "p", "c"), "connection_change", ConfirmationMode.OFF, "v1:abc", listOf("ssh_command"), 1763020800000L)
        s.update { it.copy(assistantConfirmations = it.assistantConfirmations.withRecord(rec)) }
        assertTrue(file.text!!, file.text!!.contains(""""assistant_confirmations":{"version":1,"global":"ask","overrides":[{"scope":{"kind":"target","id":"p","sub":"c"},"class":"connection_change","mode":"off","fp":"v1:abc","tools":["ssh_command"],"at":1763020800000}]}"""))
        assertEquals("round trip", listOf(rec), SettingsStore(file).assistantConfirmations.overrides)
    }

    @Test fun `a valid record is read and global is read on its own`() {
        val read = records("[${valid()}]")
        assertEquals(1, read.size); assertEquals(ConfirmationScope("target", "p", "c"), read.single().scope); assertEquals(listOf("ssh_command", "terminal_input"), read.single().tools)
        assertEquals(1763020800000L, read.single().at)
        for (global in listOf("off", "ask", "nonsense")) assertEquals(global, if (global == "off") ConfirmationMode.OFF else ConfirmationMode.ASK, store("[${valid()}]", global).assistantConfirmations.global)
    }

    @Test fun `an invalid record asks and is ignored whatever is wrong with it, and never resets global or another setting`() {
        val bad = mapOf(
            "no class" to valid(cls = ""), "unknown class" to valid(cls = "\"class\":\"nope\""), "class without a question" to valid(cls = "\"class\":\"app_read\""),
            "class that is not grantable" to valid(cls = "\"class\":\"app_change\""), "read class" to valid(cls = "\"class\":\"public_read\""), "class not a string" to valid(cls = "\"class\":1"),
            "mode ask" to valid(mode = "\"mode\":\"ask\""), "no mode" to valid(mode = ""), "mode not a string" to valid(mode = "\"mode\":true"),
            "kind device" to valid(scope = """{"kind":"device","id":"p","sub":"c"}"""), "no kind" to valid(scope = """{"id":"p","sub":"c"}"""),
            "no id" to valid(scope = """{"kind":"target","sub":"c"}"""), "empty id" to valid(scope = """{"kind":"target","id":"","sub":"c"}"""),
            "no sub" to valid(scope = """{"kind":"target","id":"p"}"""), "empty sub" to valid(scope = """{"kind":"target","id":"p","sub":""}"""), "id not a string" to valid(scope = """{"kind":"target","id":1,"sub":"c"}"""),
            "no fp" to valid(fp = ""), "fp of another version" to valid(fp = "\"fp\":\"v2:abc\""), "fp not a string" to valid(fp = "\"fp\":1"), "fp without a prefix" to valid(fp = "\"fp\":\"abc\""),
            "no tools" to valid(tools = ""), "empty tools" to valid(tools = "\"tools\":[]"), "tools not an array" to valid(tools = "\"tools\":\"ssh_command\""),
            "only terminal_close" to valid(tools = "\"tools\":[\"terminal_close\"]"), "only stop_tunnel" to valid(tools = "\"tools\":[\"stop_tunnel\"]"),
            "only skill_save" to valid(tools = "\"tools\":[\"skill_save\"]"), "only skill_delete" to valid(tools = "\"tools\":[\"skill_delete\"]"), "only unknown names" to valid(tools = "\"tools\":[\"nonsense\",1]"),
            "no at" to valid(at = ""), "at a string" to valid(at = "\"at\":\"1\""), "at a boolean" to valid(at = "\"at\":true"), "not an object" to "1", "null" to "null", "an array" to "[]")
        for ((what, record) in bad) {
            val s = store("[$record]"); val read = s.assistantConfirmations
            assertTrue(what, read.overrides.isEmpty()); assertEquals("$what: global is not reset", ConfirmationMode.OFF, read.global)
            assertFalse("$what: a sibling setting is not reset", s.settings.value.direct); assertTrue(what, s.settings.value.assistantSshCommand)
            assertTrue(what, mustAsk(AssistantToolId.SSH_COMMAND, GrantKey(ru.adsrv.frpjump.ops.ActionClass.CONNECTION_CHANGE, GrantTarget("p", "c")), ConfirmationPolicy(), SessionGrants(), null))
        }
        // One bad record does not hide the good one beside it.
        assertEquals(listOf("c2"), records("[${valid(fp = "")},${valid(scope = """{"kind":"target","id":"p","sub":"c2"}""")}]").map { it.scope.sub })
    }

    @Test fun `a broken overrides array reads as empty and every other shape too`() {
        for (shape in listOf("1", "\"x\"", "{}", "true", "null")) assertTrue(shape, records(shape).isEmpty())
        val s = SettingsStore(MemoryStorage("""{"direct":false,$on,"assistant_confirmations":{"version":1,"global":"off","overrides":{"a":1}}}"""))
        assertEquals(ConfirmationMode.OFF, s.assistantConfirmations.global); assertFalse(s.settings.value.direct)
    }

    @Test fun `names that are unknown or not grantable are dropped and the rest is kept`() {
        val read = records("[${valid(tools = "\"tools\":[\"ssh_command\",\"nonsense\",\"terminal_close\",\"stop_tunnel\",\"ssh_command\",7]")}]")
        assertEquals(listOf("ssh_command"), read.single().tools)
    }

    @Test fun `duplicates by class and connection keep the last and the first 500 are read`() {
        val read = records("[${valid(fp = "\"fp\":\"v1:first\"")},${valid(fp = "\"fp\":\"v1:last\"")}]")
        assertEquals(1, read.size); assertEquals("v1:last", read.single().fp)
        val many = (1..600).joinToString(",", "[", "]") { valid(scope = """{"kind":"target","id":"p","sub":"c$it"}""") }
        val readMany = records(many)
        assertEquals(MAX_CONNECTION_RECORDS, readMany.size); assertEquals("c1", readMany.first().scope.sub); assertEquals("c500", readMany.last().scope.sub)
    }

    @Test fun `no record is read for another version with the assistant off or for a tool that is not in effect`() {
        assertTrue("version 2", records("[${valid()}]", version = "2").isEmpty())
        assertTrue("the assistant off in the file", records("[${valid()}]", extra = """"assistant_enabled":false,"assistant_ssh_command":true""").isEmpty())
        val typingOff = records("[${valid()}]", extra = """"assistant_enabled":true,"assistant_ssh_command":true,"assistant_terminal_tabs":false""")
        assertEquals("a recorded tool that is not in effect is dropped at load", listOf("ssh_command"), typingOff.single().tools)
        assertTrue("an emptied record is removed", records("[${valid(tools = "\"tools\":[\"terminal_input\"]")}]", extra = """"assistant_enabled":true,"assistant_ssh_command":true""").isEmpty())
    }

    @Serializable private data class EarlierBuild(val version: Int = 1, val global: ConfirmationMode = ConfirmationMode.ASK)

    @Test fun `an earlier build that reads global alone ignores the records and asks`() {
        val json = """{"version":1,"global":"ask","overrides":[${valid()}]}"""
        val old = WireJson.decodeFromString<EarlierBuild>(json)
        assertEquals(ConfirmationMode.ASK, old.global)
        assertEquals(1, old.version)
    }

    @Test fun `the reader itself, before any switch narrows it, drops non-grantable names and refuses an empty record`() {
        fun read(tools: String) = readConnectionRecords(kotlinx.serialization.json.Json.parseToJsonElement("[${valid(tools = tools)}]"))
        assertTrue(read("\"tools\":[]").isEmpty()); assertTrue(read("\"tools\":[\"nonsense\"]").isEmpty())
        for (name in listOf("terminal_close", "stop_tunnel", "skill_save", "skill_delete", "web_search", "terminal_open", "start_tunnel")) assertTrue(name, read("\"tools\":[\"$name\"]").isEmpty())
        assertEquals(listOf("ssh_command"), read("\"tools\":[\"terminal_close\",\"ssh_command\",\"web_search\"]").single().tools)
    }
}
