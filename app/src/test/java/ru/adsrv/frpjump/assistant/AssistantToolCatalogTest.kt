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
    private val nonToolKeys = setOf("assistant_enabled", "assistant_confirmations", "assistant_owner_instructions", "assistant_offer_tools", "assistant_chat_text_size")
    private fun enabledFields(settings: AppSettings) = withDefaults.encodeToJsonElement(settings).jsonObject
        .filterKeys { it.startsWith("assistant_") && it !in nonToolKeys }

    @Test fun `catalog keeps the stable order wire names setting keys and effects`() {
        assertEquals(listOf("web_search", "read_page", "inspect_image", "profile_information", "terminal_tabs", "terminal_read", "terminal_rename", "terminal_input", "ssh_command"), AssistantToolId.entries.map { it.wire })
        // One switch, one entry: terminal_read follows terminal_tabs' switch and has none of its own.
        assertEquals(listOf("assistant_search", "assistant_read_page", "assistant_inspect_image", "assistant_profile_information", "assistant_terminal_tabs", "assistant_terminal_input", "assistant_ssh_command"),
            AssistantToolId.switches.map { it.settingKey })
        assertEquals(listOf(AssistantToolId.TERMINAL_READ, AssistantToolId.TERMINAL_RENAME), AssistantToolId.entries.filter { !it.ownsSwitch })
        assertEquals(AssistantToolId.TERMINAL_TABS.settingKey, AssistantToolId.TERMINAL_RENAME.settingKey)
        // An app-local label change: a changing tool, but a local one, and the only one that does not confirm.
        assertEquals(ru.adsrv.frpjump.ops.Effect.CHANGE_LOCAL, AssistantToolId.TERMINAL_RENAME.effect)
        assertEquals(listOf(AssistantToolId.TERMINAL_RENAME), AssistantToolId.entries.filter { it.effect.changes && !it.confirms })
        assertTrue(AssistantToolId.entries.filter { it != AssistantToolId.TERMINAL_RENAME }.all { it.confirms })
        assertEquals(AssistantToolId.TERMINAL_TABS.settingKey, AssistantToolId.TERMINAL_READ.settingKey)
        assertEquals(AssistantToolId.entries.size, AssistantToolId.entries.map { it.wire }.toSet().size)
        assertEquals(AssistantToolId.switches.size, AssistantToolId.switches.map { it.settingKey }.toSet().size)
        assertEquals(listOf(AssistantToolId.TERMINAL_RENAME, AssistantToolId.TERMINAL_INPUT, AssistantToolId.SSH_COMMAND), AssistantToolId.entries.filter { it.effect.changes })
        assertEquals(Effect.CHANGE_REMOTE, AssistantToolId.TERMINAL_INPUT.effect)
        assertEquals(setOf(Effect.READ_REMOTE), AssistantToolId.entries.filter { !it.effect.changes }.map { it.effect }.toSet())
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

    private val allOn = AssistantTools(search = true, readPage = true, inspectImage = true, profileInformation = true, sshCommand = true, terminalTabs = true, terminalInput = true)

    @Test fun `AssistantTools switch and with address exactly one switch each`() {
        for (id in AssistantToolId.switches) {
            val on = AssistantTools().with(id, true)
            for (other in AssistantToolId.switches) assertEquals("$id/$other", other == id, on.switch(other))
            assertEquals(AssistantTools(), on.with(id, false))
            // Turning the tabs off also turns typing off (see the test below); every other switch changes alone.
            val off = allOn.with(id, false)
            val together = if (id == AssistantToolId.TERMINAL_TABS) setOf(id, AssistantToolId.TERMINAL_INPUT) else setOf(id)
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
        for (id in AssistantToolId.entries - input) assertTrue("$id", AssistantTools().offerable(id))
        assertFalse(AppSettings().assistantTerminalInput)
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
            val expected = if (bad == AssistantToolId.TERMINAL_TABS) allOn.copy(terminalTabs = false) else allOn.with(bad, false)
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
     * The progress that the Calling event set, read at the next "progress" step (the FinishedTool update),
     * i.e. before a failed result can overwrite it. Works for known and unknown names alike.
     */
    private fun progressSetByCalling(toolName: String, arguments: JsonObject): ChatToolProgress? = runBlocking {
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
            // seen[0] is taken before Calling's own update (empty); seen[1] before FinishedTool's update.
            assertEquals(null, seen[0])
            seen[1]
        } finally { chat.close(); scope.cancel() }
    }

    @Test fun `a call of each tool shows that tool's progress when it starts`() {
        assertEquals(ChatToolProgress.Tool(AssistantToolId.WEB_SEARCH), progressSetByCalling("web_search", buildJsonObject { put("query", "q") }))
        assertEquals(ChatToolProgress.Tool(AssistantToolId.READ_PAGE), progressSetByCalling("read_page", buildJsonObject { put("url", "https://public.org/a") }))
        assertEquals(ChatToolProgress.Tool(AssistantToolId.INSPECT_IMAGE), progressSetByCalling("inspect_image", buildJsonObject { put("url", "https://public.org/a.png"); put("question", "q") }))
        // These two are not offered in this fixture (no owners); Calling still names the tool before the call is refused.
        assertEquals(ChatToolProgress.Tool(AssistantToolId.PROFILE_INFORMATION), progressSetByCalling("profile_information", buildJsonObject { }))
        assertEquals(ChatToolProgress.Tool(AssistantToolId.SSH_COMMAND), progressSetByCalling("ssh_command", buildJsonObject { }))
    }

    @Test fun `a call with an unknown tool name shows the failed progress when it starts`() {
        assertEquals(ChatToolProgress.FAILED, progressSetByCalling("not_a_tool", buildJsonObject { }))
        assertEquals(ChatToolProgress.FAILED, progressSetByCalling("WEB_SEARCH", buildJsonObject { put("query", "q") }))
    }

    @Test fun `progress values compare by tool`() {
        assertEquals(ChatToolProgress.Tool(AssistantToolId.READ_PAGE), ChatToolProgress.Tool(AssistantToolId.READ_PAGE))
        assertNotEquals(ChatToolProgress.Tool(AssistantToolId.READ_PAGE), ChatToolProgress.Tool(AssistantToolId.WEB_SEARCH))
        assertNotEquals(ChatToolProgress.FAILED as ChatToolProgress, ChatToolProgress.SUMMARY as ChatToolProgress)
    }
}
