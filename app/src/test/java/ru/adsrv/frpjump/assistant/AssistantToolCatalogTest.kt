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
    private val nonToolKeys = setOf("assistant_enabled", "assistant_confirmations")
    private fun enabledFields(settings: AppSettings) = withDefaults.encodeToJsonElement(settings).jsonObject
        .filterKeys { it.startsWith("assistant_") && it !in nonToolKeys }

    @Test fun `catalog keeps the stable order wire names setting keys and effects`() {
        assertEquals(listOf("web_search", "read_page", "inspect_image", "profile_information", "ssh_command"), AssistantToolId.entries.map { it.wire })
        assertEquals(listOf("assistant_search", "assistant_read_page", "assistant_inspect_image", "assistant_profile_information", "assistant_ssh_command"),
            AssistantToolId.entries.map { it.settingKey })
        assertEquals(AssistantToolId.entries.size, AssistantToolId.entries.map { it.wire }.toSet().size)
        assertEquals(AssistantToolId.entries.size, AssistantToolId.entries.map { it.settingKey }.toSet().size)
        assertEquals(listOf(AssistantToolId.SSH_COMMAND), AssistantToolId.entries.filter { it.effect.changes })
        assertEquals(setOf(Effect.READ_REMOTE), AssistantToolId.entries.filter { it != AssistantToolId.SSH_COMMAND }.map { it.effect }.toSet())
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
        assertEquals(AssistantToolId.entries.map { it.settingKey }.toSet(), enabledFields(AppSettings()).keys)
    }

    @Test fun `AssistantTools get and with address exactly one tool each`() {
        for (id in AssistantToolId.entries) {
            val on = AssistantTools().with(id, true)
            for (other in AssistantToolId.entries) assertEquals("$id/$other", other == id, on[other])
            assertEquals(AssistantTools(), on.with(id, false))
            assertTrue(AssistantTools(true, true, true, true, true).with(id, false).let { !it[id] && AssistantToolId.entries.filter { o -> o != id }.all { o -> it[o] } })
        }
    }

    @Test fun `setAssistantTool changes exactly the persisted field of that setting key`() {
        for (id in AssistantToolId.entries) {
            val file = MemoryStorage()
            val store = SettingsStore(file)
            store.setAssistantTool(id, true)
            val stored = Json.parseToJsonElement(file.text!!).jsonObject
            assertEquals("${id.settingKey} persisted", JsonPrimitive(true), stored[id.settingKey])
            assertEquals(setOf(id.settingKey), stored.keys.filter { it.startsWith("assistant_") }.toSet())
            assertEquals(AssistantTools().with(id, true), store.assistantAvailability.tools)
            assertEquals(AssistantTools().with(id, true), SettingsStore(file).assistantAvailability.tools)
            val fields = enabledFields(store.settings.value)
            for (other in AssistantToolId.entries) assertEquals("$id/$other", JsonPrimitive(other == id), fields[other.settingKey])
            store.setAssistantTool(id, false)
            assertEquals(AssistantTools(), SettingsStore(file).assistantAvailability.tools)
        }
    }

    @Test fun `stored tool flags of every catalog key are read independently and malformed ones default off`() {
        val all = AssistantToolId.entries.joinToString(",") { "\"${it.settingKey}\":true" }
        assertEquals(AssistantTools(true, true, true, true, true), SettingsStore(MemoryStorage("{$all}")).assistantAvailability.tools)
        for (bad in AssistantToolId.entries) {
            val text = AssistantToolId.entries.joinToString(",") { if (it == bad) "\"${it.settingKey}\":\"yes\"" else "\"${it.settingKey}\":true" }
            assertEquals(bad.name, AssistantTools(true, true, true, true, true).with(bad, false), SettingsStore(MemoryStorage("{$text}")).assistantAvailability.tools)
        }
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
        }, {}, AgentModule(true), runtime = settings.assistantChat, prompts = TestPrompts.placeholder, confirmations = null)
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
