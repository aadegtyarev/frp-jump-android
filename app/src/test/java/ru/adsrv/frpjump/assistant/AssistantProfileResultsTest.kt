package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.assistant.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.devices.*
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.*
import ru.adsrv.frpjump.tunnel.TunnelsState

class AssistantProfileResultsTest {
    private fun catalogue(): ProfileInformationState {
        val work = Profile("work", "Work", "green", "never-export-key-alias")
        val home = Profile("home", "Home", "blue", "never-export-other-alias")
        fun part(p: Profile, large: Boolean) = profileInformationPart(p,
            (if (large) listOf("root", "admin") else listOf("root")).map { user ->
                Connection("${p.id}-$user", "board1500", 22, PortKind.SSH, customName = "Service $user", user = user)
            }, TunnelsState(), emptySet(), DeviceList(if (large) List(3000) { i ->
                DeviceRow("board$i", true, null, false, label = if (i == 1500) "Щиток, гараж" else "Other device $i")
            } else listOf(DeviceRow("board1500", false, null, false, label = "Щиток, дача"))))
        return ProfileInformationState(loaded = true, profiles = listOf(part(work, true), part(home, false)))
    }
    private fun tool(state: ProfileInformationState) = AssistantProfileInformationTool({}, { state })

    @Test fun `partial query finds the full set in a large catalogue before the next model request`() = runBlocking {
        val expected = setOf("work-root", "work-admin", "home-root")
        var received: ToolResult? = null
        val provider = AgentProvider { request ->
            if (request.turns.last().exchanges.isEmpty()) AgentReply(calls = listOf(ToolCall("find", "profile_information", buildJsonObject { put("query", "  щИТОК  ") })))
            else {
                received = request.turns.last().exchanges.single().results.single()
                assertEquals("The selected facts must reach the consumer, not an argument failure", ToolOutcome.SUCCESS, received!!.outcome)
                val page = Json.parseToJsonElement(received!!.text).jsonObject
                val connections = page["results"]!!.jsonArray.map { it.jsonObject }.mapNotNull { it["connection"] as? JsonObject }
                assertEquals(expected, connections.map { it["id"]!!.jsonPrimitive.content }.toSet())
                AgentReply(connections.joinToString("\n") { "${it["id"]!!.jsonPrimitive.content}: ${it["device"]!!.jsonPrimitive.content}, ${it["user"]!!.jsonPrimitive.content}" })
            }
        }
        val run = AgentModule(true).run(provider, listOf(tool(catalogue())), "Return all suitable connections; do not silently choose one.", "Найди подключения к щитку")
        assertEquals(AgentFinish.COMPLETE, run.finish)
        assertEquals(0, received!!.omittedChars)
        for (id in expected) assertTrue(run.turn.exchanges.last().reply.text.contains(id))
        assertFalse(received!!.text.contains("never-export"))
    }

    @Test fun `empty invocation returns whole parseable records and a continuation instead of a clipped catalogue`() = runBlocking {
        val output = tool(catalogue()).execute(buildJsonObject { })
        assertEquals(ToolOutcome.SUCCESS, output.outcome)
        assertTrue("A page must fit the existing kernel budget before kernel clipping", output.text.length <= 8000)
        val page = Json.parseToJsonElement(output.text).jsonObject
        assertTrue(page["next_offset"]!!.jsonPrimitive.int > 0)
        assertTrue(page["total_records"]!!.jsonPrimitive.int > page["results"]!!.jsonArray.size)
        assertTrue(page["results"]!!.jsonArray.all { it.jsonObject["profile"]!!.jsonObject["id"]!!.jsonPrimitive.content.isNotBlank() })
    }

    @Test fun `continuation visits every unchanged record once and exact profile scope preserves variants`() = runBlocking {
        val original = catalogue()
        val state = original.copy(profiles = original.profiles.map { it.copy(devices = it.devices!!.copy(devices = it.devices.devices!!.take(40))) })
        val expected = state.profiles.sumOf { 1 + it.devices!!.devices!!.size + it.rows.size }
        val seen = mutableSetOf<String>(); var offset = 0
        do {
            val output = tool(state).execute(buildJsonObject { put("offset", offset) })
            assertEquals(ToolOutcome.SUCCESS, output.outcome); assertTrue(output.text.length <= 8000)
            val page = Json.parseToJsonElement(output.text).jsonObject
            assertEquals(expected, page["total_records"]!!.jsonPrimitive.int)
            for (record in page["results"]!!.jsonArray.map { it.jsonObject }) {
                val identity = record["record_type"].toString() + record["profile"]!!.jsonObject["id"] +
                    (record["connection"] as? JsonObject)?.get("id") + (record["device"] as? JsonObject)?.get("name")
                assertTrue("Repeated record $identity", seen.add(identity))
            }
            val next = (page["next_offset"] as? JsonPrimitive)?.intOrNull
            assertTrue(next == null || next > offset)
            offset = next ?: -1
        } while (offset >= 0)
        assertEquals(expected, seen.size)
        val scoped = Json.parseToJsonElement(tool(original).execute(buildJsonObject { put("query", "Service"); put("profile_id", "work") }).text).jsonObject
        val matches = scoped["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("work-root", "work-admin"), matches.map { it["connection"]!!.jsonObject["id"]!!.jsonPrimitive.content }.toSet())
    }

    @Test fun `profile operation facts survive paging even without a saved or visible connection row`() = runBlocking {
        val part = ProfileInformationPart("work", "Work", "green", false, true,
            devices = DeviceList(emptyList()), tunnels = TunnelsState(pending = mapOf("orphan:22" to ru.adsrv.frpjump.tunnel.PendingOp.CONNECTING),
                errors = mapOf("orphan:22" to "private-raw-error")))
        val output = tool(ProfileInformationState(loaded = true, profiles = listOf(part))).execute(buildJsonObject { put("query", "orphan") })
        val records = Json.parseToJsonElement(output.text).jsonObject["results"]!!.jsonArray
        assertEquals(1, records.size)
        val operation = records.single().jsonObject["operation"]!!.jsonObject
        assertEquals("orphan:22", operation["key"]!!.jsonPrimitive.content)
        assertEquals("CONNECTING", operation["pending"]!!.jsonPrimitive.content)
        assertEquals("Connection operation failed", operation["error"]!!.jsonPrimitive.content)
        assertFalse(output.text.contains("private-raw-error"))
    }

    @Test fun `unknown sources are not no matches and invalid selection never refreshes`() = runBlocking {
        val unknown = ProfileInformationState(profiles = listOf(ProfileInformationPart("p", "Unavailable", "grey", false, false)))
        val page = Json.parseToJsonElement(tool(unknown).execute(buildJsonObject { put("query", "absent") }).text).jsonObject
        assertTrue(page["results"]!!.jsonArray.isEmpty())
        assertFalse(page["source_status"]!!.jsonObject["complete"]!!.jsonPrimitive.boolean)
        assertEquals(1, page["source_status"]!!.jsonObject["unknown_device_lists"]!!.jsonPrimitive.int)
        var refreshed = 0
        val checked = AssistantProfileInformationTool({ refreshed++ }, { unknown })
        for (args in listOf(buildJsonObject { put("query", "x".repeat(257)) }, buildJsonObject { put("offset", -1) },
            buildJsonObject { put("offset", "1") }, buildJsonObject { put("profile_id", " ") }, buildJsonObject { put("extra", true) }))
            assertEquals(ToolOutcome.FAILED, checked.execute(args).outcome)
        assertEquals(0, refreshed)
    }

    @Test fun `production Chat retains received facts during failure then clears by revision next Send and turn eviction`() = runBlocking {
        SyntheticProfileInformation().use { f ->
            for ((id, runtime) in f.runtimes) runtime.connections.save(Connection("$id-root", "same", 22, PortKind.SSH, customName = "SSH root", user = "root"))
            val settings = ru.adsrv.frpjump.settings.SettingsStore(ru.adsrv.frpjump.ssh.MemoryStorage()).apply {
                update { it.copy(assistantEnabled = true, assistantProfileInformation = true) }
            }
            var saved = AssistantProviderSnapshot(AssistantProviderSettings(model = "synthetic"), "r")
            var fail = true
            val received = CompletableDeferred<ToolResult>(); val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val provider = AgentProvider { request ->
                val turn = request.turns.last()
                if (turn.user == "clean") AgentReply("Clean answer")
                else if (turn.exchanges.isEmpty()) AgentReply(calls = listOf(ToolCall("find", "profile_information", buildJsonObject { put("query", "same") })))
                else {
                    val result = turn.exchanges.single().results.single()
                    val connections = Json.parseToJsonElement(result.text).jsonObject["results"]!!.jsonArray.mapNotNull { (it.jsonObject["connection"] as? JsonObject)?.get("id")?.jsonPrimitive?.content }
                    assertEquals(setOf("work-root", "home-root"), connections.toSet())
                    if (fail) { received.complete(result); release.await(); error("private provider message") }
                    AgentReply(connections.joinToString("\n"))
                }
            }
            val chat = AssistantConversation(scope, { settings.assistantAvailability }, { saved }, { provider }, {}, AgentModule(true),
                runtime = settings.assistantChat, profileInformation = f.information, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
            suspend fun idle() = withTimeout(5000) { chat.state.first { !it.busy } }
            suspend fun send(text: String) { chat.edit(text); chat.send(); idle() }
            try {
                chat.edit("Find same"); chat.send()
                val result = withTimeout(5000) { received.await() }
                assertTrue(chat.state.value.busy)
                assertSame(result, chat.state.value.information.single().result)
                assertEquals(result.text.length, chat.state.value.information.single().presentationChars)
                release.complete(Unit); idle()
                assertEquals(ChatProblem.PROVIDER, chat.state.value.problem)
                assertSame(result, chat.state.value.information.single().result)
                assertTrue(chat.state.value.turns.isEmpty())
                send("clean"); assertTrue(chat.state.value.information.isEmpty())
                send("Find same"); assertEquals(1, chat.state.value.information.size)
                saved = saved.copy(revision = "new"); chat.refresh()
                assertTrue(chat.state.value.information.isEmpty())
                fail = false; send("Find same")
                assertTrue(chat.state.value.information.isEmpty())
                assertEquals("work-root\nhome-root", chat.state.value.turns.single().answer)
                assertEquals(1, chat.state.value.turns.single().information.size)
                repeat(32) { send("clean") }
                assertTrue(chat.state.value.omitted); assertEquals(32, chat.state.value.turns.size)
                assertTrue(chat.state.value.turns.all { it.information.isEmpty() })
                chat.close(); assertEquals(ChatState(), chat.state.value)
            } finally { release.complete(Unit); chat.close(); scope.cancel() }
        }
    }

    @Test fun `long unicode fields are searched before shortening and cannot break a page or action ids`() = runBlocking {
        val original = catalogue().profiles.first()
        val device = original.devices!!.devices!![1500].copy(label = "🌍".repeat(3000) + "needle")
        val part = original.copy(name = "🌍".repeat(3000), devices = original.devices.copy(devices = listOf(device)))
        val pageText = tool(ProfileInformationState(loaded = true, profiles = listOf(part))).execute(buildJsonObject { put("query", "needle") }).text
        assertTrue(pageText.length <= 8000)
        val records = Json.parseToJsonElement(pageText).jsonObject["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("work-root", "work-admin"), records.mapNotNull { (it["connection"] as? JsonObject)?.get("id")?.jsonPrimitive?.content }.toSet())
        assertTrue(records.all { it["shortened_fields"]!!.jsonArray.isNotEmpty() })
        for (record in records) {
            val name = record["profile"]!!.jsonObject["name"]!!.jsonPrimitive.content
            assertFalse(name.lastOrNull()?.isHighSurrogate() == true)
        }
        val huge = part.copy(id = "x".repeat(20000))
        val skipped = Json.parseToJsonElement(tool(ProfileInformationState(profiles = listOf(huge))).execute(buildJsonObject { }).text).jsonObject
        assertTrue(skipped["results"]!!.jsonArray.isNotEmpty())
        assertTrue(skipped["results"]!!.jsonArray.all { it.jsonObject["record_type"]!!.jsonPrimitive.content == "unavailable" })
    }
}
