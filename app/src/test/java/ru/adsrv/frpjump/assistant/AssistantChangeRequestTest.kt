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
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.ssh.*

/** Every tool whose catalog effect changes something must describe its change, so none can skip the confirmation hook. */
class AssistantChangeRequestTest {
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    /** The tools exactly as a Send gets them: through the production factory with every switch on. */
    private fun tools(): Map<AssistantToolId, AppTool<*>> {
        val synthetic = SyntheticProfileInformation()
        val box = FakeKeystore().box("synthetic-ssh")
        val env = SshEnvironment(SshKeyStore(MemoryStorage(), box), SshPasswords(MemoryStorage(), box), { "synthetic" }, TmuxNames(MemoryStorage()),
            clientFactory = { throw AssertionError("must not construct a client") })
        val all = AssistantToolFactory.create(AssistantTools(true, true, true, true, true), AssistantChatRuntime(), AgentProvider { AgentReply("x") },
            synthetic.information, AssistantSshCommands(SshTargets(synthetic.state, synthetic.hidden, env)), { })
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
            val check = tool.changeCheck(valid.getValue(id))
            if (id.effect.changes) {
                assertTrue("$id changes but is not a Change: $check", check is ChangeCheck.Change)
                assertEquals(id, (check as ChangeCheck.Change).request.tool)
                assertNotNull("$id must describe its target", check.request.target)
            } else assertSame("$id", ChangeCheck.ReadOnly, check)
        }
    }

    @Test fun `ssh describes tool target and full command and invalid arguments describe nothing`() {
        val ssh = tools().getValue(AssistantToolId.SSH_COMMAND)
        val command = "printf 'a'\nuname -a"
        val request = (ssh.changeCheck(obj("profile_id" to JsonPrimitive("p1"), "connection_id" to JsonPrimitive("c1"), "command" to JsonPrimitive(command))) as ChangeCheck.Change).request
        assertEquals(GrantTarget("p1", "c1"), request.target); assertEquals(command, request.command)
        assertFalse("toString must not print the command", request.toString().contains("uname"))
        // A lone surrogate would run as "?" (a glob): the call is invalid, so there is no card and nothing runs.
        for (c in listOf("rm -rf /\uD800\uD800\uD800", "x\uDC00")) assertSame(c, ChangeCheck.Invalid,
            ssh.changeCheck(obj("profile_id" to JsonPrimitive("p"), "connection_id" to JsonPrimitive("c"), "command" to JsonPrimitive(c))))
        assertSame(ChangeCheck.Invalid, ssh.changeCheck(obj("profile_id" to JsonPrimitive("p\uD800"), "connection_id" to JsonPrimitive("c"), "command" to JsonPrimitive("x"))))
        for (bad in listOf(obj(), obj("profile_id" to JsonPrimitive("p"), "connection_id" to JsonPrimitive(" "), "command" to JsonPrimitive("x")),
            obj("profile_id" to JsonPrimitive("p"), "connection_id" to JsonPrimitive("c"), "command" to JsonPrimitive("x"), "extra" to JsonPrimitive(1)),
            obj("profile_id" to JsonPrimitive(1), "connection_id" to JsonPrimitive("c"), "command" to JsonPrimitive("x")))) assertSame(bad.toString(), ChangeCheck.Invalid, ssh.changeCheck(bad))
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

    /** A changing tool whose description is missing or fails, and whose optional field is absent, must still look like a change. */
    private class OptionalFieldTool(private val describe: (Optional) -> ChangeRequest?) : AppTool<OptionalFieldTool.Optional>(
        AssistantToolId.SSH_COMMAND, "Synthetic changing tool", JsonObject(emptyMap())) {
        class Optional(val command: String?)
        override fun parse(args: StrictArgs) = args.run { onlyKeys("command"); Optional(optionalString("command", 100, allowBlank = false)) }
        override val invalidArguments = ToolOutput("invalid", ToolOutcome.FAILED)
        override suspend fun run(args: Optional) = ToolOutput("ran")
        override fun failure(error: Exception) = ToolOutput("failed", ToolOutcome.FAILED)
        override fun describeChange(args: Optional) = describe(args)
    }

    @Test fun `a changing tool without a description or with an absent optional field is a Change never read-only`() {
        val undescribed = OptionalFieldTool { null }
        val absent = undescribed.changeCheck(obj())
        assertTrue("optional field absent, no description: $absent", absent is ChangeCheck.Change)
        assertNull((absent as ChangeCheck.Change).request.target); assertEquals("", absent.request.command)
        assertFalse("an undescribed change says so", absent.request.described)
        assertTrue(undescribed.changeCheck(obj("command" to JsonPrimitive("x"))) is ChangeCheck.Change)
        val failing = OptionalFieldTool { throw IllegalStateException("boom") }
        assertTrue(failing.changeCheck(obj()) is ChangeCheck.Change)
        val described = OptionalFieldTool { ChangeRequest(AssistantToolId.SSH_COMMAND, null, it.command.orEmpty()) }
        assertEquals("", (described.changeCheck(obj()) as ChangeCheck.Change).request.command)
        assertSame(ChangeCheck.Invalid, undescribed.changeCheck(obj("other" to JsonPrimitive(1))))
    }

    @Test fun `progress after a tool is cleared for success and decline and failed otherwise`() {
        assertNull(progressAfter(ToolOutcome.SUCCESS)); assertNull(progressAfter(ToolOutcome.DECLINED))
        for (o in listOf(ToolOutcome.FAILED, ToolOutcome.TIMEOUT, ToolOutcome.UNKNOWN_TOOL)) assertEquals(o.name, ChatToolProgress.FAILED, progressAfter(o))
    }
}
