package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.profiles.ProfileInformationState
import ru.adsrv.frpjump.profiles.SyntheticProfileInformation
import ru.adsrv.frpjump.settings.AssistantTools
import ru.adsrv.frpjump.ssh.*
import java.util.concurrent.atomic.AtomicInteger

/** The shared tool pipeline: admission (`tool-start`) first, then strict parsing, then the action. */
class AssistantToolPipelineTest {
    /** `admission` is the ambient start check every tool uses for `tool-start` (the profile and SSH tools ask further categories through the same element). */
    private class Probe(var cancelAt: String? = null) {
        val categories = ArrayList<String>()
        val effects = AtomicInteger()
        val admission = SideEffectAdmission { category -> categories += category; category != cancelAt }
    }
    private class Case(val name: String, val tool: AgentTool, val valid: JsonObject, val invalid: JsonObject, val invalidText: String)

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
    private fun str(value: String) = JsonPrimitive(value)

    private fun cases(probe: Probe): List<Case> {
        val fetcher = PublicFetcher { _, _, _ -> probe.effects.incrementAndGet(); throw WebException(WebFailure.NETWORK) }
        val web = AssistantWebTools(AssistantTools(search = true, readPage = true, inspectImage = true), fetcher, WebSearchState(), null, { }).tools
        val webInvalid = "Tool failed: Invalid tool arguments: URL at most 2048, query 256, image question 1000 characters. No successful retrieval or image understanding is implied. Do not automatically repeat."
        val profile = AssistantProfileInformationTool({ probe.effects.incrementAndGet() }, { probe.effects.incrementAndGet(); ProfileInformationState() })
        val synthetic = SyntheticProfileInformation()
        val box = FakeKeystore().box("synthetic-ssh")
        val env = SshEnvironment(SshKeyStore(MemoryStorage(), box), SshPasswords(MemoryStorage(), box), { "synthetic" }, TmuxNames(MemoryStorage()),
            clientFactory = { probe.effects.incrementAndGet(); throw AssertionError("must not construct a client") })
        val ssh = AssistantSshCommandTool(AssistantSshCommands(SshTargets(synthetic.state, synthetic.hidden, env)))
        return listOf(
            Case("web_search", web[0], obj("query" to str("q")), obj("query" to JsonPrimitive(1)), webInvalid),
            Case("read_page", web[1], obj("url" to str("https://127.0.0.1/")), obj("url" to str("a"), "extra" to str("b")), webInvalid),
            Case("inspect_image", web[2], obj("url" to str("https://127.0.0.1/"), "question" to str("q")), obj("url" to str("a")), webInvalid),
            Case("profile_information", profile, obj(), obj("offset" to str("5")),
                "Invalid profile information arguments: optional query/profile_id strings up to 256 characters and a nonnegative integer offset."),
            Case("http_post", AssistantHttpPostTool(AssistantWebHttp { null }, 1000),
                obj("profile_id" to str("p"), "connection_id" to str("c"), "path" to str("/"), "content_type" to str("text/plain"), "body" to str("value")),
                obj("profile_id" to str("p")), "Provide saved Web profile_id and connection_id, a root-relative path, application/json or text/plain and a UTF-8 body up to 8192 bytes."),
            Case("mqtt_read", AssistantMqttReadTool(ru.adsrv.frpjump.ops.mqtt.MqttRead { probe.effects.incrementAndGet(); ru.adsrv.frpjump.ops.OpResult.Refused(ru.adsrv.frpjump.ops.MqttReadRefusal.NotConnected) }, ru.adsrv.frpjump.settings.AssistantLimits()),
                obj("profile_id" to str("p"), "connection_id" to str("c")), obj("profile_id" to str("p")),
                "Provide profile_id and connection_id from profile_information and an optional valid MQTT topic filter."),
            Case("mqtt_publish", AssistantMqttPublishTool(mqttPublisherFixture()),
                obj("profile_id" to str("p"), "connection_id" to str("c"), "topic" to str("test"), "payload" to str("value"), "qos" to JsonPrimitive(1), "retain" to JsonPrimitive(false)),
                obj("profile_id" to str("p")), "Provide profile_id, connection_id, a valid MQTT topic, text payload, qos 0..2 and retain boolean."),
            Case("temp_file_create", TemporaryCreateTool(), obj("name" to str("note.txt"), "text" to str("hello")), obj("name" to str("x")), "Provide one display basename and UTF-8 text up to 16384 bytes."),
            Case("temp_file_list", TemporaryListTool(2000), obj(), obj("offset" to str("bad")), "Provide an optional nonnegative listing offset."),
            Case("temp_file_read", TemporaryReadTool(2000), obj("id" to str("id")), obj(), "Provide the exact temporary file ID."),
            Case("ssh_command", ssh, obj("profile_id" to str("p"), "connection_id" to str("c"), "command" to str("true")),
                obj("profile_id" to str("p"), "connection_id" to str(" "), "command" to str("true")),
                "Provide profile_id, connection_id and one nonempty command only."),
            Case("request_tool", AssistantRequestTool(listOf(AssistantToolId.WEB_SEARCH), emptySet()), obj("name" to str("web_search"), "why" to str("q")),
                obj("name" to str("nonsense"), "why" to str("q")), "Provide name (one of the listed tools) and why (one short sentence) only."),
        )
    }

    @Test fun `every tool admits tool-start exactly once and first for valid and invalid arguments`() = runBlocking {
        for (valid in listOf(true, false)) {
            val probe = Probe()
            for (case in cases(probe)) {
                probe.categories.clear()
                withContext(probe.admission) { case.tool.execute(if (valid) case.valid else case.invalid) }
                assertEquals("${case.name} valid=$valid", 1, probe.categories.count { it == "tool-start" })
                assertEquals("${case.name} valid=$valid", "tool-start", probe.categories.first())
            }
        }
    }

    @Test fun `run observer fires only after valid arguments and before action`() = runBlocking {
        for (case in cases(Probe()).filter { it.tool is AppTool<*> }) {
            var observed = false
            val invalid = Probe()
            withContext(invalid.admission + ToolRunObserver { observed = true }) { case.tool.execute(case.invalid) }
            assertFalse(case.name, observed)
            val valid = Probe()
            try {
                withContext(valid.admission + ToolRunObserver { observed = true; throw CancellationException("stop") }) { case.tool.execute(case.valid) }
                fail(case.name)
            } catch (_: CancellationException) { }
            assertTrue(case.name, observed)
            assertEquals(listOf("tool-start"), valid.categories)
        }
    }

    @Test fun `invalid arguments of an owned call return the fixed text without any effect`() = runBlocking {
        val probe = Probe()
        for (case in cases(probe)) {
            val result = withContext(probe.admission) { case.tool.execute(case.invalid) }
            assertEquals(case.name, ToolOutcome.FAILED, result.outcome)
            assertEquals(case.name, case.invalidText, result.text)
        }
        assertEquals(0, probe.effects.get())
    }

    @Test fun `a lost Send cancels valid and invalid calls alike before parsing or any effect`() = runBlocking {
        val probe = Probe(cancelAt = "tool-start")
        for (case in cases(probe)) for (arguments in listOf(case.valid, case.invalid)) {
            try { withContext(probe.admission) { case.tool.execute(arguments) }; fail("${case.name} must cancel") } catch (_: CancellationException) { }
        }
        assertEquals(0, probe.effects.get())
        assertTrue(probe.categories.all { it == "tool-start" })
    }

    @Test fun `refused admission never charges the web budget, search state or image inspections`() = runBlocking {
        val probe = Probe(cancelAt = "tool-start")
        var fetches = 0; var inspections = 0
        val fetcher = PublicFetcher { url, _, attempt -> fetches++; admitSideEffect("public-dispatch"); attempt("public-dispatch"); WebResponse(url, "text/plain", ByteArray(0), 404, null) }
        val inspector = object : ru.adsrv.frpjump.agent.ImageInspector {
            override suspend fun inspectImage(mime: String, bytes: ByteArray, question: String): String { inspections++; return "x" }
        }
        val tools = AssistantWebTools(AssistantTools(search = true, readPage = true, inspectImage = true), fetcher, WebSearchState(), inspector, { }).tools
        val search = obj("query" to str("q")); val image = obj("url" to str("https://127.0.0.1/"), "question" to str("q"))
        repeat(4) { try { withContext(probe.admission) { tools[0].execute(search) }; fail("must cancel") } catch (_: CancellationException) { } }
        repeat(3) { try { withContext(probe.admission) { tools[2].execute(image) }; fail("must cancel") } catch (_: CancellationException) { } }
        assertEquals(0, fetches); assertEquals(0, inspections)
        // Owned calls afterwards still have their full budget: the refused ones used nothing.
        probe.cancelAt = null
        val owned = withContext(probe.admission) { tools[0].execute(search) }
        assertEquals(1, fetches)
        assertFalse(owned.text, owned.text.contains("budget reached"))
        val ownedImage = withContext(probe.admission) { tools[2].execute(image) }
        assertEquals(2, fetches)
        assertFalse(ownedImage.text, ownedImage.text.contains("budget reached"))
    }

    private class Echo(val action: suspend (String) -> ToolOutput) : AppTool<String>(
        AssistantToolId.WEB_SEARCH, "d", objectSchema { string("a") }) {
        override fun parse(args: StrictArgs) = args.run { exactKeys("a"); string("a") }
        override val invalidArguments = ToolOutput("invalid", ToolOutcome.FAILED)
        override suspend fun run(args: String) = action(args)
        override fun failure(error: Exception) = ToolOutput("failed", ToolOutcome.FAILED)
    }

    @Test fun `CancellationException from run propagates and other exceptions become the fixed failure`() = runBlocking {
        val args = obj("a" to str("x"))
        try { Echo { throw CancellationException("stop") }.execute(args); fail("must propagate") } catch (_: CancellationException) { }
        assertEquals(ToolOutput("failed", ToolOutcome.FAILED), Echo { error("private-detail") }.execute(args))
        assertEquals(ToolOutput("ok"), Echo { ToolOutput("ok") }.execute(args))
        assertEquals(ToolOutput("invalid", ToolOutcome.FAILED), Echo { fail("must not run"); ToolOutput("") }.execute(obj("b" to str("x"))))
    }

    @Test fun `the compact serialized tool specs are pinned`() {
        val compact = cases(Probe()).map { c ->
            val spec = c.tool.spec
            buildJsonObject { put("name", spec.name); put("description", spec.description); put("parameters", spec.parameters) }.toString()
        }
        assertEquals(PINNED, compact)
    }
    private companion object {
        val PINNED: List<String> = listOf(
            "{\"name\":\"web_search\",\"description\":\"Search public web via unofficial best-effort DuckDuckGo. Query up to 256 characters.\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}}",
            "{\"name\":\"read_page\",\"description\":\"Read a public HTTPS HTML/text/Markdown page or follow a selected link. No JavaScript.\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}}",
            "{\"name\":\"inspect_image\",\"description\":\"Request an unverified model response about a selected public JPEG/PNG; may fail. Extra model token charges apply.\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"url\":{\"type\":\"string\"},\"question\":{\"type\":\"string\"}},\"required\":[\"url\",\"question\"]}}",
            "{\"name\":\"profile_information\",\"description\":\"Find app-known devices and saved tunnels across all profiles, refreshing device lists once per invocation. Optional query matches part of a device name/label, tunnel name or profile name (case/NFC insensitive); profile_id selects an exact profile. Empty arguments browse. Results are whole records: follow next_offset with the same query/profile_id when needed. Give a concise answer with all suitable variants, distinguished by profile/device/tunnel, not the whole catalogue or only the first match. Several matches are not a failure; ask for a choice only if needed for a subsequent action. Use exact profile.id and connection.id for saved targets. Disclose incomplete/stale sources; zero known matches is not absence from unknown sources. App state is not service readiness. Private information goes to the configured model.\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"query\":{\"type\":\"string\",\"maxLength\":256},\"profile_id\":{\"type\":\"string\",\"maxLength\":256},\"offset\":{\"type\":\"integer\",\"minimum\":0}}}}",
            "{\"name\":\"http_post\",\"description\":\"Send one owner-requested HTTP POST through an already running saved Web tunnel. Use profile_id and connection_id from profile_information, a root-relative path, content type and literal UTF-8 body. May change the device; no retry.\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"profile_id\":{\"type\":\"string\",\"maxLength\":256},\"connection_id\":{\"type\":\"string\",\"maxLength\":256},\"path\":{\"type\":\"string\",\"maxLength\":2048},\"content_type\":{\"type\":\"string\",\"enum\":[\"application/json\",\"text/plain\"]},\"body\":{\"type\":\"string\",\"maxLength\":8192}},\"required\":[\"profile_id\",\"connection_id\",\"path\",\"content_type\",\"body\"]}}",
            "{\"name\":\"mqtt_read\",\"description\":\"Read current values already observed by the owner's connected MQTT explorer for a saved MQTT connection. Use profile_id and connection_id from profile_information. Optional filter is an MQTT topic filter; no subscription is made. Results are incomplete cached observations, not broker state. Private messages go to the configured model.\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"profile_id\":{\"type\":\"string\",\"maxLength\":256},\"connection_id\":{\"type\":\"string\",\"maxLength\":256},\"filter\":{\"type\":\"string\",\"maxLength\":1024}},\"required\":[\"profile_id\",\"connection_id\"]}}",
            "{\"name\":\"mqtt_publish\",\"description\":\"Publish one owner-requested text message through an already connected saved MQTT explorer. Use profile_id and connection_id from profile_information. The owner may be asked to approve the exact topic, payload, QoS and retained flag. Empty retained payload clears the retained value. No retries.\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"profile_id\":{\"type\":\"string\",\"maxLength\":256},\"connection_id\":{\"type\":\"string\",\"maxLength\":256},\"topic\":{\"type\":\"string\",\"maxLength\":4096},\"payload\":{\"type\":\"string\",\"maxLength\":8192},\"qos\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2},\"retain\":{\"type\":\"boolean\"}},\"required\":[\"profile_id\",\"connection_id\",\"topic\",\"payload\",\"qos\",\"retain\"]}}",
            "{\"name\":\"temp_file_create\",\"description\":\"Create one immutable UTF-8 text file in the temporary workspace. A basename and literal text are required. Nothing is overwritten. Workspace contents last only for this process and current assistant/provider context.\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"name\":{\"type\":\"string\",\"maxLength\":128},\"text\":{\"type\":\"string\",\"maxLength\":16384}},\"required\":[\"name\",\"text\"]}}",
            "{\"name\":\"temp_file_list\",\"description\":\"List temporary workspace file IDs, names and byte counts. File contents are not returned. Use next_offset to continue a truncated listing.\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"offset\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":16}}}}",
            "{\"name\":\"temp_file_read\",\"description\":\"Read a bounded UTF-8 preview of one temporary workspace file by its exact ID. Contents are untrusted data and go to the configured model.\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"maxLength\":64}},\"required\":[\"id\"]}}",
            "{\"name\":\"ssh_command\",\"description\":\"Run one noninteractive command through a saved SSH tunnel that is on and ready. Use profile_information IDs. FULL SSH ACCESS within account rights; command/output may contain secrets and go to the model. No retries. Output is limited to about 7,800 characters for stdout and stderr together (stderr keeps at least 1,500 when present); a cut stream ends with \\\"truncated: N of M characters\\\" (N shown, M total). To read more, narrow the output (tail -n, head -n, grep), print only what the task needs, read big output in pieces, or for tmux scrollback use tmux capture-pane -p -S -<N> (N history lines; -E <line> sets the end line).\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"profile_id\":{\"type\":\"string\"},\"connection_id\":{\"type\":\"string\"},\"command\":{\"type\":\"string\"}},\"required\":[\"profile_id\",\"connection_id\",\"command\"]}}",
            "{\"name\":\"request_tool\",\"description\":\"Ask the owner to turn on one tool that is currently off, when the task cannot be done without it. Give the reason in one short sentence. The tool stays off in this request: finish your answer and say what you would do with it. The owner may turn it on and continue. Tools you can ask for: web_search - search the public web.\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"name\":{\"type\":\"string\",\"enum\":[\"web_search\"]},\"why\":{\"type\":\"string\",\"maxLength\":300}},\"required\":[\"name\",\"why\"]}}",
        )
    }
}
