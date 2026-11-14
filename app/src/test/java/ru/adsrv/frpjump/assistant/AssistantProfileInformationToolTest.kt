package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.api.PROTOCOL_VERSION
import ru.adsrv.frpjump.errors.DomainError
import ru.adsrv.frpjump.errors.ProtocolMismatch
import ru.adsrv.frpjump.assistant.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.connections.*
import ru.adsrv.frpjump.devices.*
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.*
import ru.adsrv.frpjump.settings.*
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.tunnel.*
import ru.adsrv.frpjump.ui.devices.devicesUiState

class AssistantProfileInformationToolTest {
    private val empty = buildJsonObject { }
    private class Chat(val f: SyntheticProfileInformation, val provider: AgentProvider, val limits: AgentLimits = AgentLimits()) : AutoCloseable {
        val settings = SettingsStore(MemoryStorage()).apply { update { it.copy(assistantEnabled = true) } }
        val store = AssistantProviderStore(MemoryStorage(), object : SecretBox {
            override fun seal(plain: String) = "sealed"
            override fun open(sealed: String): String = error("No real provider credentials")
            override fun destroy() = Unit
        }, settings.assistantChat).apply { save(AssistantProviderDraft(AssistantProviderSettings(model = "synthetic"), null, "synthetic-key")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val module = bindAssistantLifecycle(settings, scope)
        val chat = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, { provider },
            store::requireRevision, module, Dispatchers.Unconfined, { limits }, settings.assistantChat, f.information, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
        suspend fun idle() = withTimeout(5000) { chat.state.first { !it.busy } }
        suspend fun send() { chat.edit("question"); chat.send(); idle() }
        override fun close() { chat.close(); scope.cancel() }
    }
    @Test fun `fake provider invocation refreshes once only when enabled and retains existing Send capture`() = runBlocking {
        SyntheticProfileInformation().use { f ->
            val requests = mutableListOf<AgentRequest>()
            var held: CompletableDeferred<Unit>? = null
            Chat(f, AgentProvider { request ->
                requests += request
                if (request.turns.last().exchanges.isEmpty()) {
                    held?.await()
                    AgentReply(calls = listOf(ToolCall("call-${requests.size}", "profile_information", empty)))
                } else AgentReply("Complete")
            }).use { c ->
                assertEquals(0, f.deviceRequests.get())
                c.send() // Disabled proposal follows ordinary unknown-tool path.
                assertEquals(listOf("request_tool"), requests.first().tools.map { it.name })
                assertEquals(ToolOutcome.UNKNOWN_TOOL, requests.last().turns.last().exchanges.single().results.single().outcome)
                assertEquals(0, f.deviceRequests.get())
                c.settings.setAssistantTool(AssistantToolId.PROFILE_INFORMATION, true)
                assertEquals(0, f.deviceRequests.get())
                held = CompletableDeferred()
                c.chat.edit("enabled invocation"); c.chat.send()
                c.settings.setAssistantTool(AssistantToolId.PROFILE_INFORMATION, false)
                held!!.complete(Unit); c.idle(); held = null
                assertEquals(2, f.deviceRequests.get()); assertEquals(2, f.versionRequests.get())
                val withResult = requests.last()
                assertEquals(listOf("profile_information", "request_tool"), withResult.tools.map { it.name })
                val output = withResult.turns.last().exchanges.single().results.single()
                assertEquals(ToolOutcome.SUCCESS, output.outcome)
                assertTrue(output.text.contains("no-connections")); assertTrue(output.text.contains("Work")); assertTrue(output.text.contains("Home"))
                assertFalse(output.text.contains("secret-"))
                assertTrue(c.chat.state.value.turns.size == 2)
                c.send(); assertEquals(listOf("request_tool"), requests.last().tools.map { it.name }); assertEquals(2, f.deviceRequests.get())
            }
        }
    }
    @Test fun `projection keeps typed app facts but excludes every raw diagnostic and credential field`() {
        val raw = "private-secret-exception-server-frpc"
        val p = Profile("p", "Work", "blue", "secret-alias")
        val visitor = VisitorState(TunnelStatus.RELAYED, detail = raw, directFailed = true, waitingForPeer = true,
            confirming = true, waitingForDevice = true, deviceMissing = false)
        val tunnels = TunnelsState(listOf(Tunnel("same", 22, "secret-grant", 40001, visitor)),
            pending = mapOf("same:22" to PendingOp.CONNECTING), errors = mapOf("same:22" to DomainError.ServerError(raw)),
            syncError = DomainError.Unreachable(raw, raw), mismatch = ProtocolMismatch(PROTOCOL_VERSION + 1), waiting = Waiting.NETWORK)
        val devices = DeviceList(listOf(DeviceRow("same", true, null, false, stale = true, checking = true)),
            stale = true, error = DomainError.Redirected(raw, raw), unreachable = true)
        val part = profileInformationPart(p, listOf(Connection("c", "same", 22, PortKind.SSH)), tunnels, emptySet(), devices)
        val state = ProfileInformationState(true, failure = raw, current = "p", profiles = listOf(part,
            ProfileInformationPart("unavailable", "Unavailable", "grey", true, false)))
        val text = profileInformationText(state)
        assertFalse(text.contains(raw)); assertFalse(text.contains("secret-grant")); assertFalse(text.contains(p.keyAlias))
        assertFalse(text.contains("detail")); assertTrue(text.contains("127.0.0.1:40001"))
        val result = Json.parseToJsonElement(text).jsonObject
        assertEquals(buildJsonObject { put("kind", "PROFILES_LOAD_FAILED") }, result["source_status"]!!.jsonObject["load_error"])
        val records = result["results"]!!.jsonArray.map { it.jsonObject }
        val known = records.first { it["connection"] is JsonObject }
        val tunnel = known["connection"]!!.jsonObject["tunnel"]!!.jsonObject
        assertEquals("RELAYED", tunnel["status"]!!.jsonPrimitive.content)
        for (key in listOf("direct_failed", "waiting_for_peer", "confirming", "waiting_for_device")) assertTrue(tunnel[key]!!.jsonPrimitive.boolean)
        assertFalse(tunnel["device_missing"]!!.jsonPrimitive.boolean)
        val unavailable = records.last()
        assertEquals(JsonNull, unavailable["profile"]!!.jsonObject["device_list"])
        assertEquals(JsonNull, unavailable["connection"])
        // The kinds, and only the kinds, in every error field.
        val profile = records.first { it["connection"] is JsonObject }["profile"]!!.jsonObject
        fun kind(name: String) = buildJsonObject { put("kind", name) }
        assertEquals(kind("REDIRECTED"), profile["device_list"]!!.jsonObject["error"])
        assertEquals(kind("UNREACHABLE"), profile["tunnel_state"]!!.jsonObject["sync_error"])
        assertEquals(kind("PROTOCOL_UPDATE_APP"), profile["tunnel_state"]!!.jsonObject["mismatch"])
        assertEquals(kind("SERVER_ERROR"), known["connection"]!!.jsonObject["error"])
        // The owner's screens keep the payload.
        assertEquals(DomainError.Redirected(raw, raw), devicesUiState(state, false).error)
        assertEquals(DomainError.ServerError(raw), part.rows.single().error)
    }
    @Test fun `failed ordinary refresh is returned as stale known data not fresh success`() = runBlocking {
        SyntheticProfileInformation().use { f ->
            f.information.refreshAll(); f.fail = true
            val result = AssistantProfileInformationTool(f.information).execute(empty)
            assertEquals(4, f.deviceRequests.get()); assertEquals(4, f.versionRequests.get())
            assertEquals(ToolOutcome.SUCCESS, result.outcome) // Successfully read known state, not a successful refresh.
            assertTrue(result.text.contains("\"error\":{\"kind\":\"SERVER_ERROR\"}")); assertTrue(result.text.contains("\"stale\":true"))
            assertFalse(result.text.contains("private-server-secret"))
        }
    }
    @Test fun `arguments errors and thrown read errors are fixed failures and cancellation propagates`() = runBlocking {
        var refreshes = 0
        val tool = AssistantProfileInformationTool({ refreshes++ }, { error("secret-read-error") })
        assertEquals(ToolOutcome.FAILED, tool.execute(buildJsonObject { put("profile", "work") }).outcome)
        assertEquals(0, refreshes)
        val failed = tool.execute(empty)
        assertEquals(1, refreshes); assertEquals(ToolOutcome.FAILED, failed.outcome); assertFalse(failed.text.contains("secret-read-error"))
        val entered = CompletableDeferred<Unit>(); var exited = false
        val suspended = AssistantProfileInformationTool({ entered.complete(Unit); try { awaitCancellation() } finally { exited = true } },
            { error("Not read after cancellation") })
        val job = launch { suspended.execute(empty); fail("Cancellation swallowed") }
        entered.await(); job.cancelAndJoin(); assertTrue(exited)
    }
    @Test fun `ordinary kernel result bounds and Chat Stop also cover profile information`() = runBlocking {
        val tool = AssistantProfileInformationTool({}, { ProfileInformationState(profiles = List(100) {
            ProfileInformationPart("$it", "Long profile $it", "green", false, false)
        }) })
        var result: ToolResult? = null
        val provider = AgentProvider { request ->
            if (request.turns.last().exchanges.isEmpty()) AgentReply(calls = listOf(ToolCall("bounded", tool.spec.name, empty)))
            else { result = request.turns.last().exchanges.single().results.single(); AgentReply("bounded answer") }
        }
        AgentModule(true).run(provider, listOf(tool), "Read enabled information", "question", limits = AgentLimits(maxToolChars = 200))
        assertTrue(result!!.omittedChars > 0); assertTrue(result!!.text.length <= 200)
        assertTrue(result!!.text.contains("\n[…]\n"))
        SyntheticProfileInformation().use { f ->
            val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
            f.beforeResponse = { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            Chat(f, AgentProvider { AgentReply(calls = listOf(ToolCall("stop", "profile_information", empty))) }).use { c ->
                c.settings.setAssistantTool(AssistantToolId.PROFILE_INFORMATION, true)
                c.chat.edit("stop read"); c.chat.send()
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(ChatToolProgress.Tool(AssistantToolId.PROFILE_INFORMATION), c.chat.state.value.toolProgress)
                c.chat.stop(); release.countDown(); c.idle()
                assertTrue(c.chat.state.value.turns.isEmpty()); assertEquals("stop read", c.chat.state.value.draft)
            }
        }
    }
    @Test fun `AUTH-1 revoked owner cannot enqueue either ordinary endpoint in any profile`() = runBlocking {
        SyntheticProfileInformation().use { f ->
            val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
            val tool = AssistantProfileInformationTool(f.information)
            // Commit wins immediately after the first admitted start (`tool-start`): every request start check then fails.
            try { withContext(SideEffectAdmission { category -> asked += category; category == "tool-start" }) { tool.execute(empty) }; fail("must cancel") } catch (_: CancellationException) { }
            assertEquals("tool-start", asked.first())
            assertTrue(asked.drop(1).isNotEmpty() && asked.drop(1).all { it == "profile-version-dispatch" || it == "profile-devices-dispatch" })
            assertEquals("No device request after revocation", 0, f.deviceRequests.get())
            assertEquals("No version request after revocation", 0, f.versionRequests.get())
            assertEquals("No call was created", emptyList<String>(), f.enqueues)
        }
    }

    @Test fun `OWN-EXIT-1 Stop retains Chat slot while admitted interceptors have not exited`() = runBlocking {
        SyntheticProfileInformation().use { f ->
            val entered = java.util.concurrent.CountDownLatch(4)
            val release = java.util.concurrent.CountDownLatch(1)
            f.beforeResponse = { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            try {
                Chat(f, AgentProvider { AgentReply(calls = listOf(ToolCall("held", "profile_information", empty))) }).use { c ->
                    c.settings.setAssistantTool(AssistantToolId.PROFILE_INFORMATION, true)
                    c.chat.edit("held information"); c.chat.send()
                    assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    c.chat.stop()
                    assertTrue("Admitted HTTP work is still held; global slot must remain busy", c.settings.assistantChat.busy.value)
                    release.countDown(); c.idle()
                    assertTrue(c.chat.state.value.turns.isEmpty())
                }
            } finally { release.countDown() }
        }
    }

}
