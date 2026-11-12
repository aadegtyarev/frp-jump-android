package ru.adsrv.frpjump.assistant

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.chat.*
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.profiles.ProfileInformationState
import ru.adsrv.frpjump.profiles.SyntheticProfileInformation
import ru.adsrv.frpjump.settings.AssistantTools
import ru.adsrv.frpjump.ssh.*

/** Every tool whose catalog effect changes something must describe its change, so none can skip the confirmation hook. */
class AssistantChangeRequestTest {
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun tools(): Map<AssistantToolId, AppTool<*>> {
        val web = AssistantWebTools(AssistantTools(search = true, readPage = true, inspectImage = true),
            PublicFetcher { _, _, _ -> throw WebException(WebFailure.NETWORK) }, WebSearchState(), null, { }).tools
        val synthetic = SyntheticProfileInformation()
        val box = FakeKeystore().box("synthetic-ssh")
        val env = SshEnvironment(SshKeyStore(MemoryStorage(), box), SshPasswords(MemoryStorage(), box), { "synthetic" }, TmuxNames(MemoryStorage()),
            clientFactory = { throw AssertionError("must not construct a client") })
        val all = web + AssistantProfileInformationTool({ }, { ProfileInformationState() }) +
            AssistantSshCommandTool(AssistantSshCommands(SshTargets(synthetic.state, synthetic.hidden, env)))
        return all.associate { tool -> AssistantToolId.fromWire(tool.spec.name)!! to (tool as AppTool<*>) }
    }

    @Test fun `every catalog tool is an AppTool`() {
        assertEquals(AssistantToolId.entries.toSet(), tools().keys)
    }

    @Test fun `each changing tool describes valid arguments and read tools never do`() {
        val valid = mapOf(
            AssistantToolId.WEB_SEARCH to obj("query" to JsonPrimitive("q")),
            AssistantToolId.READ_PAGE to obj("url" to JsonPrimitive("https://127.0.0.1/")),
            AssistantToolId.INSPECT_IMAGE to obj("url" to JsonPrimitive("https://127.0.0.1/"), "question" to JsonPrimitive("q")),
            AssistantToolId.PROFILE_INFORMATION to obj(),
            AssistantToolId.SSH_COMMAND to obj("profile_id" to JsonPrimitive("p"), "connection_id" to JsonPrimitive("c"), "command" to JsonPrimitive("uptime")),
        )
        assertEquals(AssistantToolId.entries.toSet(), valid.keys)
        for ((id, tool) in tools()) {
            val request = tool.changeRequest(valid.getValue(id))
            if (id.effect.changes) {
                assertNotNull("$id changes but describes nothing", request)
                assertEquals(id, request!!.tool)
            } else assertNull("$id", request)
        }
    }

    @Test fun `ssh describes tool target and full command and invalid arguments describe nothing`() {
        val ssh = tools().getValue(AssistantToolId.SSH_COMMAND)
        val command = "printf 'a'\nuname -a"
        val request = ssh.changeRequest(obj("profile_id" to JsonPrimitive("p1"), "connection_id" to JsonPrimitive("c1"), "command" to JsonPrimitive(command)))!!
        assertEquals("p1/c1", request.target); assertEquals(command, request.command)
        assertFalse("toString must not print the command", request.toString().contains("uname"))
        for (bad in listOf(obj(), obj("profile_id" to JsonPrimitive("p"), "connection_id" to JsonPrimitive(" "), "command" to JsonPrimitive("x")),
            obj("profile_id" to JsonPrimitive("p"), "connection_id" to JsonPrimitive("c"), "command" to JsonPrimitive("x"), "extra" to JsonPrimitive(1)),
            obj("profile_id" to JsonPrimitive(1), "connection_id" to JsonPrimitive("c"), "command" to JsonPrimitive("x")))) assertNull(bad.toString(), ssh.changeRequest(bad))
    }

    @Test fun `a declined result is saved and restored and is not a tool failure`() {
        val call = SavedCall("c1", "ssh_command", obj("command" to JsonPrimitive("x")))
        val declined = SavedResult.of(ToolResult("c1", "ssh_command", ru.adsrv.frpjump.settings.ConfirmationTexts.DECLINED, ToolOutcome.DECLINED))
        val json = kotlinx.serialization.json.Json.encodeToString(SavedResult.serializer(), declined)
        assertTrue(json.contains("\"outcome\":\"DECLINED\""))
        val restored = kotlinx.serialization.json.Json.decodeFromString(SavedResult.serializer(), json)
        assertEquals(ToolOutcome.DECLINED, restored.value().outcome)
        val turn = SavedTurn("q", listOf(SavedExchange(SavedReply("", listOf(call)), listOf(restored)), SavedExchange(SavedReply("done"))), status = SavedTurnState.COMPLETE)
        val view = turn.view()
        assertFalse(view.toolFailed); assertTrue(view.toolFailures.isEmpty())
        assertEquals(ToolOutcome.DECLINED, view.receipts.single().result?.outcome)
        val failed = SavedTurn("q", listOf(SavedExchange(SavedReply("", listOf(call)), listOf(SavedResult.of(ToolResult("c1", "ssh_command", "x", ToolOutcome.FAILED))))), status = SavedTurnState.COMPLETE).view()
        assertTrue(failed.toolFailed)
    }
}
