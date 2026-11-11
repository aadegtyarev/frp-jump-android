package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
    private class Probe(var cancelAt: String? = null) {
        val categories = ArrayList<String>()
        val effects = AtomicInteger()
        val dispatch: WebDispatch = { category, start ->
            categories += category
            if (category == cancelAt) throw CancellationException()
            start()
        }
    }
    private class Case(val name: String, val tool: AgentTool, val valid: JsonObject, val invalid: JsonObject, val invalidText: String)

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
    private fun str(value: String) = JsonPrimitive(value)

    private fun cases(probe: Probe): List<Case> {
        val fetcher = PublicFetcher { _, _, _ -> probe.effects.incrementAndGet(); throw WebException(WebFailure.NETWORK) }
        val web = AssistantWebTools(AssistantTools(search = true, readPage = true, inspectImage = true), fetcher, WebSearchState(), null, probe.dispatch, { }).tools
        val webInvalid = "Tool failed: Invalid tool arguments: URL at most 2048, query 256, image question 1000 characters. No successful retrieval or image understanding is implied. Do not automatically repeat."
        val profile = AssistantProfileInformationTool({ probe.effects.incrementAndGet() }, { probe.effects.incrementAndGet(); ProfileInformationState() }, probe.dispatch)
        val synthetic = SyntheticProfileInformation()
        val box = FakeKeystore().box("synthetic-ssh")
        val env = SshEnvironment(SshKeyStore(MemoryStorage(), box), SshPasswords(MemoryStorage(), box), { "synthetic" }, TmuxNames(MemoryStorage()),
            clientFactory = { probe.effects.incrementAndGet(); throw AssertionError("must not construct a client") })
        val ssh = AssistantSshCommandTool(AssistantSshCommands(synthetic.state, synthetic.hidden, env), probe.dispatch)
        return listOf(
            Case("web_search", web[0], obj("query" to str("q")), obj("query" to JsonPrimitive(1)), webInvalid),
            Case("read_page", web[1], obj("url" to str("https://127.0.0.1/")), obj("url" to str("a"), "extra" to str("b")), webInvalid),
            Case("inspect_image", web[2], obj("url" to str("https://127.0.0.1/"), "question" to str("q")), obj("url" to str("a")), webInvalid),
            Case("profile_information", profile, obj(), obj("offset" to str("5")),
                "Invalid profile information arguments: optional query/profile_id strings up to 256 characters and a nonnegative integer offset."),
            Case("ssh_command", ssh, obj("profile_id" to str("p"), "connection_id" to str("c"), "command" to str("true")),
                obj("profile_id" to str("p"), "connection_id" to str(" "), "command" to str("true")),
                "Provide profile_id, connection_id and one nonempty command only."),
        )
    }

    @Test fun `every tool dispatches tool-start exactly once and first for valid and invalid arguments`() = runBlocking {
        for (valid in listOf(true, false)) {
            val probe = Probe()
            for (case in cases(probe)) {
                probe.categories.clear()
                case.tool.execute(if (valid) case.valid else case.invalid)
                assertEquals("${case.name} valid=$valid", 1, probe.categories.count { it == "tool-start" })
                assertEquals("${case.name} valid=$valid", "tool-start", probe.categories.first())
            }
        }
    }

    @Test fun `invalid arguments of an owned call return the fixed text without any effect`() = runBlocking {
        val probe = Probe()
        for (case in cases(probe)) {
            val result = case.tool.execute(case.invalid)
            assertEquals(case.name, ToolOutcome.FAILED, result.outcome)
            assertEquals(case.name, case.invalidText, result.text)
        }
        assertEquals(0, probe.effects.get())
    }

    @Test fun `a lost Send cancels valid and invalid calls alike before parsing or any effect`() = runBlocking {
        val probe = Probe(cancelAt = "tool-start")
        for (case in cases(probe)) for (arguments in listOf(case.valid, case.invalid)) {
            try { case.tool.execute(arguments); fail("${case.name} must cancel") } catch (_: CancellationException) { }
        }
        assertEquals(0, probe.effects.get())
        assertTrue(probe.categories.all { it == "tool-start" })
    }

    @Test fun `refused admission never charges the web budget, search state or image inspections`() = runBlocking {
        val probe = Probe(cancelAt = "tool-start")
        var fetches = 0; var inspections = 0
        val fetcher = PublicFetcher { url, _, dispatch -> fetches++; dispatch("public-dispatch") { }; WebResponse(url, "text/plain", ByteArray(0), 404, null) }
        val inspector = object : ru.adsrv.frpjump.agent.openai.ImageInspector {
            override suspend fun inspectImage(mime: String, bytes: ByteArray, question: String, dispatch: (() -> Unit) -> Unit): String { inspections++; return "x" }
        }
        val tools = AssistantWebTools(AssistantTools(search = true, readPage = true, inspectImage = true), fetcher, WebSearchState(), inspector, probe.dispatch, { }).tools
        val search = obj("query" to str("q")); val image = obj("url" to str("https://127.0.0.1/"), "question" to str("q"))
        repeat(4) { try { tools[0].execute(search); fail("must cancel") } catch (_: CancellationException) { } }
        repeat(3) { try { tools[2].execute(image); fail("must cancel") } catch (_: CancellationException) { } }
        assertEquals(0, fetches); assertEquals(0, inspections)
        // Owned calls afterwards still have their full budget: the refused ones used nothing.
        probe.cancelAt = null
        val owned = tools[0].execute(search)
        assertEquals(1, fetches)
        assertFalse(owned.text, owned.text.contains("budget reached"))
        val ownedImage = tools[2].execute(image)
        assertEquals(2, fetches)
        assertFalse(ownedImage.text, ownedImage.text.contains("budget reached"))
    }

    private class Echo(dispatch: WebDispatch, val action: suspend (String) -> ToolOutput) : AppTool<String>(
        AssistantToolId.WEB_SEARCH, "d", objectSchema { string("a") }, dispatch) {
        override fun parse(args: StrictArgs) = args.run { exactKeys("a"); string("a") }
        override val invalidArguments = ToolOutput("invalid", ToolOutcome.FAILED)
        override suspend fun run(args: String) = action(args)
        override fun failure(error: Exception) = ToolOutput("failed", ToolOutcome.FAILED)
    }

    @Test fun `CancellationException from run propagates and other exceptions become the fixed failure`() = runBlocking {
        val probe = Probe()
        val args = obj("a" to str("x"))
        try { Echo(probe.dispatch) { throw CancellationException("stop") }.execute(args); fail("must propagate") } catch (_: CancellationException) { }
        assertEquals(ToolOutput("failed", ToolOutcome.FAILED), Echo(probe.dispatch) { error("private-detail") }.execute(args))
        assertEquals(ToolOutput("ok"), Echo(probe.dispatch) { ToolOutput("ok") }.execute(args))
        assertEquals(ToolOutput("invalid", ToolOutcome.FAILED), Echo(probe.dispatch) { fail("must not run"); ToolOutput("") }.execute(obj("b" to str("x"))))
    }

    @Test fun `the compact serialized text of all five specs is pinned`() {
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
            "{\"name\":\"profile_information\",\"description\":\"Find app-known devices and saved connections across all profiles, refreshing device lists once per invocation. Optional query matches part of a device name/label, connection name or profile name (case/NFC insensitive); profile_id selects an exact profile. Empty arguments browse. Results are whole records: follow next_offset with the same query/profile_id when needed. Give a concise answer with all suitable variants, distinguished by profile/device/connection, not the whole catalogue or only the first match. Several matches are not a failure; ask for a choice only if needed for a subsequent action. Use exact profile.id and connection.id for saved targets. Disclose incomplete/stale sources; zero known matches is not absence from unknown sources. App state is not service readiness. Private information goes to the configured model.\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"query\":{\"type\":\"string\",\"maxLength\":256},\"profile_id\":{\"type\":\"string\",\"maxLength\":256},\"offset\":{\"type\":\"integer\",\"minimum\":0}}}}",
            "{\"name\":\"ssh_command\",\"description\":\"Run one noninteractive command on a saved SSH connection over a ready tunnel. Use profile_information IDs. FULL SSH ACCESS within account rights; command/output may contain secrets and go to the model. No retries.\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"profile_id\":{\"type\":\"string\"},\"connection_id\":{\"type\":\"string\"},\"command\":{\"type\":\"string\"}},\"required\":[\"profile_id\",\"connection_id\",\"command\"]}}",
        )
    }
}
