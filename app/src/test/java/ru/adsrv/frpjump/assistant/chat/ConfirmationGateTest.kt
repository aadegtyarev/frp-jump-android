package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.settings.*

/** [authorizeCall] with synthetic tools: the fail-closed rules, without any chat state. */
class ConfirmationGateTest {
    private class Described(val command: String?)
    private class Changing(private val describe: (Described) -> ChangeRequest?) : AppTool<Described>(AssistantToolId.SSH_COMMAND, "Synthetic changing tool", JsonObject(emptyMap())) {
        override fun parse(args: StrictArgs) = args.run { onlyKeys("command"); Described(optionalString("command", 100)) }
        override val invalidArguments = ToolOutput("invalid", ToolOutcome.FAILED)
        override suspend fun run(args: Described) = ToolOutput("ran")
        override fun failure(error: Exception) = ToolOutput("failed", ToolOutcome.FAILED)
        override fun describeChange(args: Described) = describe(args)
    }
    private class Reading : AppTool<Described>(AssistantToolId.WEB_SEARCH, "Synthetic read tool", JsonObject(emptyMap())) {
        override fun parse(args: StrictArgs) = args.run { onlyKeys("command"); Described(optionalString("command", 100)) }
        override val invalidArguments = ToolOutput("invalid", ToolOutcome.FAILED)
        override suspend fun run(args: Described) = ToolOutput("ran")
        override fun failure(error: Exception) = ToolOutput("failed", ToolOutcome.FAILED)
    }

    private val target = GrantTarget("p", "c")
    private fun described() = Changing { ChangeRequest(AssistantToolId.SSH_COMMAND, target, it.command.orEmpty()) }
    private val undescribed = Changing { null }
    private fun call(name: String, vararg args: Pair<String, JsonElement>) = ToolCall("id", name, JsonObject(mapOf(*args)))
    private val withCommand = arrayOf("command" to JsonPrimitive("uptime"))

    private class Rig(val answer: ConfirmationDecision = ConfirmationDecision.ONCE, policy: ConfirmationPolicy = ConfirmationPolicy(),
        val persistFails: Boolean = false, val persistThrows: Boolean = false, var enabled: Boolean = true) {
        val asked = ArrayList<ChangeRequest>()
        var persisted = 0
        var notSaved = 0
        var stamp: Any? = "s1"
        var livePolicy = policy
        val support = ConfirmationSupport(policy = { livePolicy },
            persistOff = { if (persistThrows) error("boom"); persisted++; !persistFails }, stamp = { stamp }, toolEnabled = { enabled })
        suspend fun run(tool: AppTool<*>, call: ToolCall) = authorizeCall(support, mapOf(call.name to tool), call,
            wait = { request, _ -> asked += request; answer }, onNotSaved = { notSaved++ }, io = Dispatchers.Unconfined)
    }

    @Test fun `a described changing call asks and each answer maps to the right result`() = runBlocking {
        val tool = described(); val c = call("ssh_command", *withCommand)
        for (answer in listOf(ConfirmationDecision.ONCE, ConfirmationDecision.SESSION, ConfirmationDecision.ALWAYS)) {
            val rig = Rig(answer)
            assertNull(answer.name, rig.run(tool, c)); assertEquals(1, rig.asked.size); assertEquals("uptime", rig.asked.single().command)
        }
        assertEquals("only Always commits the switch", 1, Rig(ConfirmationDecision.ALWAYS).also { it.run(tool, c) }.persisted)
        assertEquals(0, Rig(ConfirmationDecision.ONCE).also { it.run(tool, c) }.persisted)
        val denied = Rig(ConfirmationDecision.DENY).run(tool, c)!!
        assertEquals(ToolOutcome.DECLINED, denied.outcome); assertEquals(ConfirmationTexts.DECLINED, denied.text)
        val silent = Rig(ConfirmationDecision.NO_ANSWER).run(tool, c)!!
        assertEquals(ToolOutcome.DECLINED, silent.outcome); assertEquals(ConfirmationTexts.NO_ANSWER, silent.text)
    }

    @Test fun `a changing call with valid arguments and no description is asked never run unasked`() = runBlocking {
        for (args in listOf(emptyArray(), withCommand)) {
            val rig = Rig(ConfirmationDecision.DENY)
            val result = rig.run(undescribed, call("ssh_command", *args))
            assertEquals("asked", 1, rig.asked.size); assertNull(rig.asked.single().target); assertEquals("", rig.asked.single().command)
            assertEquals(ToolOutcome.DECLINED, result!!.outcome)
        }
        val throwing = Changing { error("describe failed") }
        val rig = Rig(ConfirmationDecision.DENY)
        assertEquals(ToolOutcome.DECLINED, rig.run(throwing, call("ssh_command"))!!.outcome); assertEquals(1, rig.asked.size)
    }

    @Test fun `an undescribed call cannot create a session grant`() = runBlocking {
        val rig = Rig(ConfirmationDecision.SESSION)
        assertNull(rig.run(undescribed, call("ssh_command")))
        assertTrue(rig.support.grants.snapshot().isEmpty())
        assertNull(rig.run(undescribed, call("ssh_command"))); assertEquals("asks again every time", 2, rig.asked.size)
    }

    @Test fun `read tools and invalid arguments are never asked`() = runBlocking {
        val rig = Rig(ConfirmationDecision.DENY)
        assertNull(rig.run(Reading(), call("web_search", *withCommand)))
        assertNull(rig.run(described(), call("ssh_command", "unexpected" to JsonPrimitive(1))))
        assertTrue(rig.asked.isEmpty())
    }

    @Test fun `the policy waives the question and is read at each call`() = runBlocking {
        val rig = Rig(ConfirmationDecision.ONCE, ConfirmationPolicy(global = ConfirmationMode.OFF))
        val tool = described(); val c = call("ssh_command", *withCommand)
        assertNull(rig.run(tool, c)); assertTrue(rig.asked.isEmpty())
        rig.livePolicy = ConfirmationPolicy()
        assertNull(rig.run(tool, c)); assertEquals(1, rig.asked.size)
        rig.livePolicy = ConfirmationPolicy(global = ConfirmationMode.OFF)
        assertNull(rig.run(tool, c)); assertEquals(1, rig.asked.size)
    }

    @Test fun `a session grant waives the question only for the same target and definition`() = runBlocking {
        val rig = Rig(ConfirmationDecision.ONCE); val tool = described(); val c = call("ssh_command", *withCommand)
        rig.support.grants.grant(GrantKey(AssistantToolId.SSH_COMMAND, target), "s1")
        assertNull(rig.run(tool, c)); assertTrue(rig.asked.isEmpty())
        rig.stamp = "s2"
        assertNull(rig.run(tool, c)); assertEquals("an edited target asks again", 1, rig.asked.size)
        rig.stamp = "s1"
        assertNull(rig.run(tool, c)); assertEquals("and the grant did not come back", 2, rig.asked.size)
        val other = Changing { ChangeRequest(AssistantToolId.SSH_COMMAND, GrantTarget("p", "other"), "x") }
        rig.support.grants.grant(GrantKey(AssistantToolId.SSH_COMMAND, target), "s1")
        assertNull(rig.run(other, c)); assertEquals(3, rig.asked.size)
    }

    @Test fun `Always commits off and an unsaved or failing commit is reported and still allows this call once`() = runBlocking {
        val ok = Rig(ConfirmationDecision.ALWAYS)
        assertNull(ok.run(described(), call("ssh_command", *withCommand))); assertEquals(1, ok.persisted); assertEquals(0, ok.notSaved)
        val unsaved = Rig(ConfirmationDecision.ALWAYS, persistFails = true)
        assertNull(unsaved.run(described(), call("ssh_command", *withCommand))); assertEquals(1, unsaved.notSaved)
        val throwing = Rig(ConfirmationDecision.ALWAYS, persistThrows = true)
        assertNull(throwing.run(described(), call("ssh_command", *withCommand))); assertEquals(1, throwing.notSaved)
    }

    @Test fun `Always still commits when Stop races the answer and a cancelled wait propagates`() = runBlocking {
        val rig = Rig(ConfirmationDecision.ALWAYS)
        val tool = described(); val c = call("ssh_command", *withCommand)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            authorizeCall(rig.support, mapOf("ssh_command" to tool), c, wait = { _, _ -> currentCoroutineContext().job.cancel(); ConfirmationDecision.ALWAYS },
                onNotSaved = {}, io = Dispatchers.Unconfined)
        }
        job.join()
        assertEquals("the owner's choice stands", 1, rig.persisted)
        var thrown: Throwable? = null
        try { authorizeCall(rig.support, mapOf("ssh_command" to tool), c, wait = { _, _ -> throw CancellationException("stopped") }, onNotSaved = {}, io = Dispatchers.Unconfined) }
        catch (e: CancellationException) { thrown = e }
        assertNotNull(thrown)
    }

    /** A tool that is in the registry but is not an AppTool: nothing can describe its change. */
    private class Bare(name: String) : AgentTool {
        override val spec = ToolSpec(name, "Synthetic bare tool", JsonObject(emptyMap()))
        override suspend fun execute(arguments: JsonObject) = ToolOutput("ran")
    }

    @Test fun `a catalog tool that changes something but is not an AppTool is refused and never asked or run`() = runBlocking {
        val rig = Rig(ConfirmationDecision.ONCE)
        val result = authorizeCall(rig.support, mapOf("ssh_command" to Bare("ssh_command")), call("ssh_command", *withCommand),
            wait = { r, _ -> rig.asked += r; ConfirmationDecision.ONCE }, onNotSaved = {}, io = Dispatchers.Unconfined)
        assertNotNull("a null result would let the call run", result)
        assertEquals(ToolOutcome.FAILED, result!!.outcome); assertEquals("Tool was not authorized; nothing was executed.", result.text)
        assertTrue(rig.asked.isEmpty())
    }

    @Test fun `a bare read tool and an unknown name are not refused`() = runBlocking {
        val rig = Rig(ConfirmationDecision.DENY)
        for (name in AssistantToolId.entries.filter { !it.effect.changes }.map { it.wire }) {
            assertNull(name, authorizeCall(rig.support, mapOf(name to Bare(name)), call(name), wait = { r, _ -> rig.asked += r; ConfirmationDecision.DENY }, onNotSaved = {}, io = Dispatchers.Unconfined))
        }
        assertNull(authorizeCall(rig.support, emptyMap(), call("ssh_command"), wait = { r, _ -> rig.asked += r; ConfirmationDecision.DENY }, onNotSaved = {}, io = Dispatchers.Unconfined))
        assertTrue(rig.asked.isEmpty())
    }

    @Test fun `the decision follows the registered tool identity not the identity a description claims`() = runBlocking {
        val lying = Changing { ChangeRequest(AssistantToolId.WEB_SEARCH, target, "x") }
        val rig = Rig(ConfirmationDecision.DENY)
        assertEquals(ToolOutcome.DECLINED, rig.run(lying, call("ssh_command", *withCommand))!!.outcome)
        assertEquals(1, rig.asked.size)
        val session = Rig(ConfirmationDecision.SESSION)
        session.run(lying, call("ssh_command", *withCommand))
        assertTrue(session.support.grants.has(GrantKey(AssistantToolId.SSH_COMMAND, target), "s1"))
    }

    @Test fun `a session answer is not recorded for a tool that was switched off while the owner was asked`() = runBlocking {
        val rig = Rig(ConfirmationDecision.SESSION, enabled = false)
        assertNull(rig.run(described(), call("ssh_command", *withCommand)))
        assertTrue(rig.support.grants.snapshot().isEmpty())
        val on = Rig(ConfirmationDecision.SESSION)
        on.run(described(), call("ssh_command", *withCommand)); assertEquals(1, on.support.grants.snapshot().size)
    }
}
