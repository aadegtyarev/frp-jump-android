package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.ops.ActionClass
import ru.adsrv.frpjump.settings.*

/** [authorizeCall] with synthetic tools: the fail-closed rules, without any chat state. */
class ConfirmationGateTest {
    private class Described(val command: String?)
    private class Changing(id: AssistantToolId = AssistantToolId.SSH_COMMAND, private val describe: (Described) -> ChangeRequest?) : AppTool<Described>(id, "Synthetic changing tool", JsonObject(emptyMap())) {
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
        val world = FakeGrantWorld().also { if (!enabled) it.off += AssistantToolId.SSH_COMMAND }
        var livePolicy = policy
        val support = ConfirmationSupport(policy = { livePolicy },
            persistOff = { if (persistThrows) error("boom"); persisted++; !persistFails }, grants = SessionGrants(world))
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

    @Test fun `a changing call with valid arguments and no description is refused never shown with an empty command and never run`() = runBlocking {
        for (args in listOf(emptyArray(), withCommand)) {
            val rig = Rig(ConfirmationDecision.ONCE)
            val result = rig.run(undescribed, call("ssh_command", *args))
            assertNotNull("a null result would let the call run", result)
            assertEquals(ToolOutcome.FAILED, result!!.outcome); assertEquals("Tool was not authorized; nothing was executed.", result.text)
            assertTrue("no card for what cannot be described", rig.asked.isEmpty())
        }
        val throwing = Changing { error("describe failed") }
        val rig = Rig(ConfirmationDecision.ONCE)
        assertEquals(ToolOutcome.FAILED, rig.run(throwing, call("ssh_command"))!!.outcome); assertTrue(rig.asked.isEmpty())
        // Even with the question turned off an undescribed change is refused: nobody can say what runs.
        val off = Rig(ConfirmationDecision.ONCE, ConfirmationPolicy(global = ConfirmationMode.OFF))
        assertEquals(ToolOutcome.FAILED, off.run(undescribed, call("ssh_command"))!!.outcome)
    }

    @Test fun `without any support every changing call is refused and reads and invalid arguments are not`() = runBlocking {
        val asked = ArrayList<ChangeRequest>()
        suspend fun run(tool: AppTool<*>, c: ToolCall) = authorizeCall(null, mapOf(c.name to tool), c, wait = { r, _ -> asked += r; ConfirmationDecision.ONCE }, onNotSaved = {}, io = Dispatchers.Unconfined)
        val refused = run(described(), call("ssh_command", *withCommand))
        assertNotNull(refused); assertEquals(ToolOutcome.FAILED, refused!!.outcome); assertEquals(NOT_AUTHORIZED, refused.text)
        assertNull(run(Reading(), call("web_search", *withCommand)))
        assertNull(run(described(), call("ssh_command", "unexpected" to JsonPrimitive(1))))
        assertTrue(asked.isEmpty())
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

    private val key get() = GrantKey(ActionClass.CONNECTION_CHANGE, target)
    private fun Rig.grant(tool: AssistantToolId = AssistantToolId.SSH_COMMAND) = support.grants.record(key, tool, support.grants.stamp(target)!!, support.grants.coverableBy(tool))

    @Test fun `a session grant waives the question only for the same connection and while its period and fingerprint hold`() = runBlocking {
        val rig = Rig(ConfirmationDecision.ONCE); val tool = described(); val c = call("ssh_command", *withCommand)
        assertTrue(rig.grant())
        assertNull(rig.run(tool, c)); assertTrue(rig.asked.isEmpty())
        rig.world.fingerprint = "f2"
        assertNull(rig.run(tool, c)); assertEquals("an edited connection asks again", 1, rig.asked.size)
        rig.world.fingerprint = "f1"
        assertNull(rig.run(tool, c)); assertEquals("and the grant did not come back", 2, rig.asked.size)
        assertTrue(rig.grant()); rig.world.period = 2
        assertNull(rig.run(tool, c)); assertEquals("a new period of the tunnel asks again", 3, rig.asked.size)
        rig.world.period = 1
        assertNull(rig.run(tool, c)); assertEquals("and the old period does not come back", 4, rig.asked.size)
        val other = Changing { ChangeRequest(AssistantToolId.SSH_COMMAND, GrantTarget("p", "other"), "x") }
        assertTrue(rig.grant())
        assertNull(rig.run(other, c)); assertEquals("another connection asks", 5, rig.asked.size)
        assertNull(rig.run(tool, c)); assertEquals("the granted one does not", 5, rig.asked.size)
    }

    @Test fun `a grant on ssh_command also covers terminal_input of the same connection if it was in effect then and is now and nothing else of the class`() = runBlocking {
        fun typing(id: AssistantToolId) = Changing(id) { ChangeRequest(id, target, "x") }
        val rig = Rig(ConfirmationDecision.ONCE)
        assertTrue(rig.grant())
        assertNull(rig.run(typing(AssistantToolId.TERMINAL_INPUT), call("terminal_input", *withCommand))); assertTrue("typing shares the grant", rig.asked.isEmpty())
        for (other in listOf(AssistantToolId.TERMINAL_CLOSE, AssistantToolId.STOP_TUNNEL, AssistantToolId.SKILL_SAVE, AssistantToolId.SKILL_DELETE)) {
            val before = rig.asked.size
            assertNull(other.name, rig.run(typing(other), call(other.wire, *withCommand))); assertEquals("${other.name} is never covered", before + 1, rig.asked.size)
        }
        // Typing out of effect at the answer: a later terminal_input asks, ssh_command does not.
        val narrow = Rig(ConfirmationDecision.ONCE).also { it.world.off += AssistantToolId.TERMINAL_INPUT }
        assertTrue(narrow.grant()); narrow.world.off.clear()
        assertNull(narrow.run(typing(AssistantToolId.TERMINAL_INPUT), call("terminal_input", *withCommand))); assertEquals(1, narrow.asked.size)
        assertNull(narrow.run(described(), call("ssh_command", *withCommand))); assertEquals(1, narrow.asked.size)
        // A tool switched off leaves the grant for good: switching it on again does not bring it back.
        val gone = Rig(ConfirmationDecision.ONCE); assertTrue(gone.grant())
        gone.world.off += AssistantToolId.SSH_COMMAND
        assertNull(gone.run(described(), call("ssh_command", *withCommand))); assertEquals(1, gone.asked.size)
        gone.world.off.clear()
        assertNull(gone.run(described(), call("ssh_command", *withCommand))); assertEquals(2, gone.asked.size)
        assertNull(gone.run(typing(AssistantToolId.TERMINAL_INPUT), call("terminal_input", *withCommand))); assertEquals("typing is still covered", 2, gone.asked.size)
    }

    @Test fun `a tool that is not grantable never records or honors a session grant whatever its class shares`() = runBlocking {
        for (id in AssistantToolId.entries.filter { it.effect.changes && it.confirms && !it.grantable }) {
            val rig = Rig(ConfirmationDecision.SESSION)
            val tool = Changing(id) { ChangeRequest(id, target, "x") }
            assertNull(id.name, rig.run(tool, call(id.wire, *withCommand)))
            assertTrue("${id.name}: an answer For session records nothing", rig.support.grants.snapshot().isEmpty())
            assertFalse(rig.support.grants.record(GrantKey(id.actionClass, target), id, rig.support.grants.stamp(target)!!, rig.support.grants.coverableBy(id)))
            // Even with a grant of the class held (by ssh_command), it still asks.
            assertTrue(rig.grant())
            assertNull(rig.run(tool, call(id.wire, *withCommand)))
            assertEquals("${id.name} asks twice", 2, rig.asked.size)
        }
        // A key of another class than the tool's covers nothing.
        val rig = Rig(ConfirmationDecision.ONCE); assertTrue(rig.grant())
        assertFalse(rig.support.grants.covers(AssistantToolId.SSH_COMMAND, GrantKey(ActionClass.APP_CHANGE, target)))
    }

    @Test fun `typing turned on while the card is open is not in the grant the owner gave`() = runBlocking {
        val world = FakeGrantWorld().also { it.off += AssistantToolId.TERMINAL_INPUT }
        val grants = SessionGrants(world)
        val support = ConfirmationSupport(policy = { ConfirmationPolicy() }, persistOff = { true }, grants = grants)
        val shown = ArrayList<Set<AssistantToolId>>()
        val result = authorizeCall(support, mapOf("ssh_command" to described()), call("ssh_command", *withCommand),
            wait = { request, _ -> shown += request.sessionTools; world.off.clear(); ConfirmationDecision.SESSION }, onNotSaved = {}, io = Dispatchers.Unconfined)
        assertNull(result); assertEquals("the card said commands only", listOf(setOf(AssistantToolId.SSH_COMMAND)), shown)
        assertTrue(grants.covers(AssistantToolId.SSH_COMMAND, key)); assertFalse(grants.covers(AssistantToolId.TERMINAL_INPUT, key))
    }

    @Test fun `no grant is recorded when the period ends or the connection changes while the owner is asked and the call still obeys the answer`() = runBlocking {
        for (during in listOf<(FakeGrantWorld) -> Unit>({ it.period = 2 }, { it.period = null }, { it.fingerprint = "f2" }, { it.disableVersion = 1 }, { it.enabled = false })) {
            val world = FakeGrantWorld(); val grants = SessionGrants(world)
            val support = ConfirmationSupport(policy = { ConfirmationPolicy() }, persistOff = { true }, grants = grants)
            var calls = 0
            val result = authorizeCall(support, mapOf("ssh_command" to described()), call("ssh_command", *withCommand),
                wait = { _, _ -> during(world); calls++; ConfirmationDecision.SESSION }, onNotSaved = {}, io = Dispatchers.Unconfined)
            assertNull("the call runs: Allow once semantics", result); assertEquals(1, calls)
            assertTrue("nothing recorded", grants.snapshot().isEmpty())
        }
        val rig = Rig(ConfirmationDecision.SESSION); rig.run(described(), call("ssh_command", *withCommand))
        assertEquals("a grant is recorded when nothing changed", 1, rig.support.grants.snapshot().size)
        // No open period at all (the tunnel is not open): the answer is Allow once.
        val closed = Rig(ConfirmationDecision.SESSION).also { it.world.period = null }
        assertNull(closed.run(described(), call("ssh_command", *withCommand))); assertTrue(closed.support.grants.snapshot().isEmpty())
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
        assertEquals("the card names the registered tool", AssistantToolId.SSH_COMMAND, rig.asked.single().tool)
        val session = Rig(ConfirmationDecision.SESSION)
        session.run(lying, call("ssh_command", *withCommand))
        assertTrue(session.support.grants.covers(AssistantToolId.SSH_COMMAND, GrantKey(ActionClass.CONNECTION_CHANGE, target)))
    }

    @Test fun `a session answer is not recorded for a tool that was switched off while the owner was asked`() = runBlocking {
        val rig = Rig(ConfirmationDecision.SESSION, enabled = false)
        assertNull(rig.run(described(), call("ssh_command", *withCommand)))
        assertTrue(rig.support.grants.snapshot().isEmpty())
        val on = Rig(ConfirmationDecision.SESSION)
        on.run(described(), call("ssh_command", *withCommand)); assertEquals(1, on.support.grants.snapshot().size)
    }
}
