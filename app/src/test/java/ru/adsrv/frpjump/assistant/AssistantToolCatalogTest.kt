package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.assistant.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.settings.AssistantTools
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.ssh.MemoryStorage
import java.util.concurrent.atomic.AtomicInteger

class AssistantToolCatalogTest {
    private val withDefaults = Json { encodeDefaults = true }
    /** `assistant_*` settings that are not tool switches; list a new one here so it is not mistaken for a tool. */
    private val nonToolKeys = setOf("assistant_enabled", "assistant_confirmations", "assistant_owner_instructions", "assistant_offer_tools", "assistant_chat_text_size", "assistant_limits")
    private fun enabledFields(settings: AppSettings) = withDefaults.encodeToJsonElement(settings).jsonObject
        .filterKeys { it.startsWith("assistant_") && it !in nonToolKeys }

    @Test fun `catalog keeps the stable order wire names setting keys and effects`() {
        assertEquals(listOf("web_search", "read_page", "inspect_image", "profile_information", "http_get", "http_post", "mqtt_read", "mqtt_publish", "start_tunnel", "stop_tunnel", "terminal_tabs", "terminal_read", "terminal_rename", "terminal_input", "terminal_open", "terminal_show", "terminal_close", "read_skill", "skills_list", "skill_save", "skill_delete", "wait_for", "temp_file_create", "temp_file_list", "temp_file_read", "ssh_command"), AssistantToolId.entries.map { it.wire })
        // One switch, one entry: terminal_read follows terminal_tabs' switch and has none of its own.
        assertEquals(listOf("assistant_search", "assistant_read_page", "assistant_inspect_image", "assistant_profile_information", "assistant_http_get", "assistant_http_post", "assistant_mqtt_read", "assistant_mqtt_publish", "assistant_tunnels", "assistant_terminal_tabs", "assistant_terminal_input", "assistant_sessions", "assistant_skills", "assistant_waits", "assistant_temp_files", "assistant_ssh_command"),
            AssistantToolId.switches.map { it.settingKey })
        assertEquals(listOf(AssistantToolId.STOP_TUNNEL, AssistantToolId.TERMINAL_READ, AssistantToolId.TERMINAL_RENAME, AssistantToolId.TERMINAL_SHOW, AssistantToolId.TERMINAL_CLOSE, AssistantToolId.READ_SKILL, AssistantToolId.SKILL_SAVE, AssistantToolId.SKILL_DELETE, AssistantToolId.TEMP_FILE_LIST, AssistantToolId.TEMP_FILE_READ), AssistantToolId.entries.filter { !it.ownsSwitch })
        assertEquals(AssistantToolId.TERMINAL_OPEN.settingKey, AssistantToolId.TERMINAL_SHOW.settingKey); assertEquals(AssistantToolId.TERMINAL_OPEN.settingKey, AssistantToolId.TERMINAL_CLOSE.settingKey)
        assertEquals(AssistantToolId.START_TUNNEL.settingKey, AssistantToolId.STOP_TUNNEL.settingKey)
        assertEquals(AssistantToolId.TERMINAL_TABS.settingKey, AssistantToolId.TERMINAL_RENAME.settingKey)
        // An app-local label change: a changing tool, but a local one, and the only one that does not confirm.
        assertEquals(ru.adsrv.frpjump.ops.Effect.CHANGE_LOCAL, AssistantToolId.TERMINAL_RENAME.effect)
        // Opening a session and asking the owner change nothing the owner would be asked about; closing one asks like every changing tool.
        assertEquals(listOf(AssistantToolId.START_TUNNEL, AssistantToolId.TERMINAL_RENAME, AssistantToolId.TERMINAL_OPEN, AssistantToolId.TERMINAL_SHOW, AssistantToolId.WAIT_FOR, AssistantToolId.TEMP_FILE_CREATE), AssistantToolId.entries.filter { it.effect.changes && !it.confirms })
        assertTrue(AssistantToolId.entries.filter { it != AssistantToolId.TERMINAL_RENAME && it != AssistantToolId.START_TUNNEL && it != AssistantToolId.WAIT_FOR && it != AssistantToolId.TERMINAL_OPEN && it != AssistantToolId.TERMINAL_SHOW && it != AssistantToolId.TEMP_FILE_CREATE }.all { it.confirms })
        assertTrue(AssistantToolId.TERMINAL_CLOSE.effect.changes && AssistantToolId.TERMINAL_CLOSE.confirms)
        // Stopping a tunnel asks like every changing command.
        assertTrue(AssistantToolId.STOP_TUNNEL.effect.changes && AssistantToolId.STOP_TUNNEL.confirms)
        assertEquals(AssistantToolId.TERMINAL_TABS.settingKey, AssistantToolId.TERMINAL_READ.settingKey)
        assertEquals(AssistantToolId.entries.size, AssistantToolId.entries.map { it.wire }.toSet().size)
        assertEquals(AssistantToolId.switches.size, AssistantToolId.switches.map { it.settingKey }.toSet().size)
        assertEquals(listOf(AssistantToolId.HTTP_POST, AssistantToolId.MQTT_PUBLISH, AssistantToolId.START_TUNNEL, AssistantToolId.STOP_TUNNEL, AssistantToolId.TERMINAL_RENAME, AssistantToolId.TERMINAL_INPUT, AssistantToolId.TERMINAL_OPEN, AssistantToolId.TERMINAL_SHOW, AssistantToolId.TERMINAL_CLOSE, AssistantToolId.SKILL_SAVE, AssistantToolId.SKILL_DELETE, AssistantToolId.WAIT_FOR, AssistantToolId.TEMP_FILE_CREATE, AssistantToolId.SSH_COMMAND), AssistantToolId.entries.filter { it.effect.changes })
        assertEquals(Effect.CHANGE_REMOTE, AssistantToolId.TERMINAL_INPUT.effect)
        assertEquals(setOf(Effect.READ_REMOTE, Effect.READ_LOCAL), AssistantToolId.entries.filter { !it.effect.changes }.map { it.effect }.toSet())
        // Skills: reading needs no switch and no question; saving and deleting ask like every changing tool, locally.
        assertEquals(Effect.READ_LOCAL, AssistantToolId.READ_SKILL.effect); assertEquals(Effect.READ_LOCAL, AssistantToolId.SKILLS_LIST.effect)
        for (id in listOf(AssistantToolId.SKILL_SAVE, AssistantToolId.SKILL_DELETE)) { assertEquals(Effect.CHANGE_LOCAL, id.effect); assertTrue(id.confirms) }
        assertEquals(setOf("assistant_skills"), listOf(AssistantToolId.READ_SKILL, AssistantToolId.SKILLS_LIST, AssistantToolId.SKILL_SAVE, AssistantToolId.SKILL_DELETE).map { it.settingKey }.toSet())
        assertFalse(Effect.READ_LOCAL.changes); assertFalse(Effect.READ_REMOTE.changes)
        assertTrue(Effect.CHANGE_LOCAL.changes); assertTrue(Effect.CHANGE_REMOTE.changes)
    }

    @Test fun `fromWire finds every tool and rejects anything else`() {
        for (id in AssistantToolId.entries) assertSame(id, AssistantToolId.fromWire(id.wire))
        for (name in listOf("", "unknown", "WEB_SEARCH", "Web_Search", " web_search", "web_search ", "assistant_search")) assertNull(name, AssistantToolId.fromWire(name))
    }

    @Test fun `every setting key is a persisted AppSettings field`() {
        val keys = withDefaults.encodeToJsonElement(AppSettings()).jsonObject.keys
        for (id in AssistantToolId.entries) assertTrue("${id.settingKey} missing from AppSettings", id.settingKey in keys)
        // Every other assistant_* field must be a catalog key or be listed in nonToolKeys.
        assertEquals(AssistantToolId.switches.map { it.settingKey }.toSet(), enabledFields(AppSettings()).keys)
    }

    private val allOn = AssistantTools(search = true, readPage = true, inspectImage = true, profileInformation = true, sshCommand = true, terminalTabs = true, terminalInput = true, tunnels = true, waits = true, sessions = true, skills = true, mqttRead = true, mqttPublish = true, httpGet = true, httpPost = true, tempFiles = true)

    @Test fun `AssistantTools switch and with address exactly one switch each`() {
        for (id in AssistantToolId.switches) {
            val on = AssistantTools().with(id, true)
            for (other in AssistantToolId.switches) assertEquals("$id/$other", other == id, on.switch(other))
            assertEquals(AssistantTools(), on.with(id, false))
            // Turning the tabs off also turns typing off (see the test below); every other switch changes alone.
            val off = allOn.with(id, false)
            // Turning typing off turns the assistant's own sessions off with it.
            val together = when (id) {
                AssistantToolId.TERMINAL_TABS -> setOf(id, AssistantToolId.TERMINAL_INPUT, AssistantToolId.TERMINAL_OPEN)
                AssistantToolId.TERMINAL_INPUT -> setOf(id, AssistantToolId.TERMINAL_OPEN)
                else -> setOf(id)
            }
            assertTrue(AssistantToolId.switches.all { o -> off.switch(o) == (o !in together) })
        }
    }

    @Test fun `turning the terminal tabs off turns typing off too and turning them on never brings it back`() {
        val both = AssistantTools(terminalTabs = true, terminalInput = true)
        val off = both.with(AssistantToolId.TERMINAL_TABS, false)
        assertFalse(off.terminalTabs); assertFalse("the raw typing switch is off, not just ineffective", off.terminalInput)
        val again = off.with(AssistantToolId.TERMINAL_TABS, true)
        assertTrue(again.terminalTabs); assertFalse(again.terminalInput); assertFalse(again[AssistantToolId.TERMINAL_INPUT])
        assertEquals(both.with(AssistantToolId.TERMINAL_READ, false), off)
        // Through the store, the persisted values.
        val file = MemoryStorage(); val store = SettingsStore(file)
        store.setAssistantTool(AssistantToolId.TERMINAL_TABS, true); store.setAssistantTool(AssistantToolId.TERMINAL_INPUT, true)
        store.setAssistantTool(AssistantToolId.TERMINAL_TABS, false)
        assertFalse(store.settings.value.assistantTerminalTabs); assertFalse(store.settings.value.assistantTerminalInput)
        store.setAssistantTool(AssistantToolId.TERMINAL_TABS, true)
        assertTrue(store.settings.value.assistantTerminalTabs); assertFalse(store.settings.value.assistantTerminalInput)
        assertFalse(SettingsStore(file).assistantAvailability.tools.terminalInput)
    }

    @Test fun `terminal_input is in effect only with both its switch and the terminal tabs on`() {
        val input = AssistantToolId.TERMINAL_INPUT
        assertFalse(AssistantTools()[input])
        assertFalse(AssistantTools(terminalInput = true)[input])
        assertTrue(AssistantTools(terminalInput = true).switch(input))
        assertFalse(AssistantTools(terminalTabs = true)[input])
        assertTrue(AssistantTools(terminalTabs = true, terminalInput = true)[input])
        // Turning one on turns on only that one.
        assertEquals(AssistantTools(terminalTabs = true, terminalInput = true), AssistantTools(terminalTabs = true).with(input, true))
        assertEquals(AssistantTools(terminalInput = true), AssistantTools().with(input, true))
        // Offered to the model only while the tabs are on; the other tools always.
        assertFalse(AssistantTools().offerable(input)); assertTrue(AssistantTools(terminalTabs = true).offerable(input))
        val sessions = setOf(AssistantToolId.TERMINAL_OPEN, AssistantToolId.TERMINAL_SHOW, AssistantToolId.TERMINAL_CLOSE)
        for (id in AssistantToolId.entries - input - sessions) assertTrue("$id", AssistantTools().offerable(id))
        // The assistant's own sessions are offered only while both the tabs and typing are on.
        for (id in sessions) { assertFalse("$id", AssistantTools().offerable(id)); assertFalse("$id", AssistantTools(terminalTabs = true).offerable(id)); assertTrue("$id", AssistantTools(terminalTabs = true, terminalInput = true).offerable(id)) }
        assertFalse(AppSettings().assistantTerminalInput)
    }

    @Test fun `the assistant's own sessions are in effect only with their switch, the tabs and typing, and turn off with either`() {
        val open = AssistantToolId.TERMINAL_OPEN
        for (id in listOf(open, AssistantToolId.TERMINAL_SHOW, AssistantToolId.TERMINAL_CLOSE)) {
            assertFalse(AssistantTools()[id]); assertFalse(AssistantTools(sessions = true)[id]); assertFalse(AssistantTools(sessions = true, terminalTabs = true)[id])
            assertFalse(AssistantTools(sessions = true, terminalInput = true)[id])
            assertTrue(AssistantTools(sessions = true, terminalTabs = true, terminalInput = true)[id])
            assertFalse(AssistantTools(terminalTabs = true, terminalInput = true)[id])
        }
        val all = AssistantTools(terminalTabs = true, terminalInput = true, sessions = true)
        assertFalse("typing off turns them off", all.with(AssistantToolId.TERMINAL_INPUT, false).sessions)
        assertFalse("tabs off turns them off", all.with(AssistantToolId.TERMINAL_TABS, false).sessions)
        // Turning typing back on never brings them back by itself.
        assertFalse(all.with(AssistantToolId.TERMINAL_INPUT, false).with(AssistantToolId.TERMINAL_INPUT, true)[open])
        assertFalse(AppSettings().assistantSessions)
    }

    @Test fun `terminal_read is on and off with the terminal_tabs switch`() {
        val on = AssistantTools().with(AssistantToolId.TERMINAL_TABS, true)
        assertTrue(on[AssistantToolId.TERMINAL_TABS]); assertTrue(on[AssistantToolId.TERMINAL_READ]); assertTrue(on.terminalTabs)
        assertEquals(on, AssistantTools().with(AssistantToolId.TERMINAL_READ, true))
        assertEquals(AssistantTools(), on.with(AssistantToolId.TERMINAL_READ, false))
        assertFalse(AssistantTools().terminalTabs)
        assertFalse(AppSettings().assistantTerminalTabs)
    }

    @Test fun `setAssistantTool changes exactly the persisted field of that setting key`() {
        for (id in AssistantToolId.switches) {
            val file = MemoryStorage()
            val store = SettingsStore(file)
            store.setAssistantTool(id, true)
            val stored = Json.parseToJsonElement(file.text!!).jsonObject
            assertEquals("${id.settingKey} persisted", JsonPrimitive(true), stored[id.settingKey])
            assertEquals(setOf(id.settingKey), stored.keys.filter { it.startsWith("assistant_") }.toSet())
            assertEquals(AssistantTools().with(id, true), store.assistantAvailability.tools)
            assertEquals(AssistantTools().with(id, true), SettingsStore(file).assistantAvailability.tools)
            val fields = enabledFields(store.settings.value)
            for (other in AssistantToolId.switches) assertEquals("$id/$other", JsonPrimitive(other == id), fields[other.settingKey])
            store.setAssistantTool(id, false)
            assertEquals(AssistantTools(), SettingsStore(file).assistantAvailability.tools)
        }
    }

    @Test fun `stored tool flags of every catalog key are read independently and malformed ones default off`() {
        val all = AssistantToolId.switches.joinToString(",") { "\"${it.settingKey}\":true" }
        assertEquals(allOn, SettingsStore(MemoryStorage("{$all}")).assistantAvailability.tools)
        for (bad in AssistantToolId.switches) {
            val text = AssistantToolId.switches.joinToString(",") { if (it == bad) "\"${it.settingKey}\":\"yes\"" else "\"${it.settingKey}\":true" }
            // A stored value is read as stored (a malformed tabs flag does not rewrite the typing flag); only the setter ties them.
            val expected = when (bad) {
                AssistantToolId.TERMINAL_TABS -> allOn.copy(terminalTabs = false)
                AssistantToolId.TERMINAL_INPUT -> allOn.copy(terminalInput = false)
                else -> allOn.with(bad, false)
            }
            assertEquals(bad.name, expected, SettingsStore(MemoryStorage("{$text}")).assistantAvailability.tools)
        }
        // A missing or malformed terminal switch is off; nothing else is reset.
        assertFalse(SettingsStore(MemoryStorage("{}")).assistantAvailability.tools.terminalTabs)
        assertFalse(SettingsStore(MemoryStorage("{\"assistant_terminal_tabs\":1,\"assistant_search\":true}")).assistantAvailability.tools.terminalTabs)
        assertFalse(SettingsStore(MemoryStorage("{}")).assistantAvailability.tools.terminalInput)
        assertFalse(SettingsStore(MemoryStorage("{\"assistant_terminal_input\":\"true\",\"assistant_terminal_tabs\":true}")).assistantAvailability.tools.terminalInput)
        assertTrue(SettingsStore(MemoryStorage("{\"assistant_terminal_input\":true,\"assistant_terminal_tabs\":true}")).assistantAvailability.tools[AssistantToolId.TERMINAL_INPUT])
    }

    // ---- Progress ----

    private val saved = AssistantProviderSnapshot(AssistantProviderSettings(model = "m"), "r")

    /**
     * The tool progress observed during a call, after authorization and argument validation.
     */
    private fun progressSetDuringCall(toolName: String, arguments: JsonObject): ChatToolProgress? = runBlocking {
        val settings = SettingsStore(MemoryStorage()).apply { update { it.copy(assistantEnabled = true, assistantSearch = true, assistantReadPage = true, assistantInspectImage = true) } }
        val calls = AtomicInteger()
        settings.assistantChat.publicWeb = PublicFetcher { _, _, _ -> throw WebException(WebFailure.NETWORK) }
        val seen = java.util.concurrent.CopyOnWriteArrayList<ChatToolProgress?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var chat: AssistantConversation
        settings.assistantChat.probes = ChatProbes(before = { point -> if (point == "progress") seen += chat.state.value.toolProgress })
        chat = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, {
            AgentProvider { if (calls.incrementAndGet() == 1) AgentReply(calls = listOf(ToolCall("call", toolName, arguments))) else AgentReply("complete") }
        }, {}, AgentModule(true), runtime = settings.assistantChat, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
        try {
            chat.edit("q"); chat.send()
            withTimeout(4000) { chat.state.first { !it.busy } }
            // The proposal itself shows no action; a started tool is observed before its result replaces it.
            assertEquals(null, seen.first())
            seen.filterIsInstance<ChatToolProgress.Tool>().singleOrNull()
        } finally { chat.close(); scope.cancel() }
    }

    @Test fun `a call of each tool shows that tool's progress when it starts`() {
        assertEquals(ChatToolProgress.Tool(AssistantToolId.WEB_SEARCH), progressSetDuringCall("web_search", buildJsonObject { put("query", "q") }))
        assertEquals(ChatToolProgress.Tool(AssistantToolId.READ_PAGE), progressSetDuringCall("read_page", buildJsonObject { put("url", "https://public.org/a") }))
        assertEquals(ChatToolProgress.Tool(AssistantToolId.INSPECT_IMAGE), progressSetDuringCall("inspect_image", buildJsonObject { put("url", "https://public.org/a.png"); put("question", "q") }))
        // These tools are not offered in this fixture; their proposals do not start.
        assertNull(progressSetDuringCall("profile_information", buildJsonObject { }))
        assertNull(progressSetDuringCall("ssh_command", buildJsonObject { }))
        assertNull(progressSetDuringCall("read_page", buildJsonObject { }))
    }

    @Test fun `unknown proposals do not show running progress`() {
        assertNull(progressSetDuringCall("not_a_tool", buildJsonObject { }))
        assertNull(progressSetDuringCall("WEB_SEARCH", buildJsonObject { put("query", "q") }))
    }

    @Test fun `progress values compare by tool`() {
        assertEquals(ChatToolProgress.Tool(AssistantToolId.READ_PAGE), ChatToolProgress.Tool(AssistantToolId.READ_PAGE))
        assertNotEquals(ChatToolProgress.Tool(AssistantToolId.READ_PAGE), ChatToolProgress.Tool(AssistantToolId.WEB_SEARCH))
        assertNotEquals(ChatToolProgress.FAILED as ChatToolProgress, ChatToolProgress.SUMMARY as ChatToolProgress)
    }
}
