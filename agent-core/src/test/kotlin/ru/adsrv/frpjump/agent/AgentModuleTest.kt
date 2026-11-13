package ru.adsrv.frpjump.agent

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class AgentModuleTest {
    private val args = JsonObject(emptyMap())
    private fun call(id: String = "one", name: String = "fake") = ToolCall(id, name, args)
    private fun tool(action: suspend () -> ToolOutput) = object : AgentTool {
        override val spec = ToolSpec("fake", "Synthetic fixture", args)
        override suspend fun execute(arguments: JsonObject) = action()
    }
    private val final = AgentProvider { AgentReply("done") }
    private val textCounter = TokenCounter { r -> r.instructions.length.toLong() + r.turns.sumOf { t -> t.user.length.toLong() + t.exchanges.sumOf { e -> e.reply.text.length.toLong() + e.results.sumOf { it.text.length.toLong() } } } }
    private fun exchange(text: String) = AgentExchange(AgentReply(calls = listOf(call())), listOf(ToolResult("one", "fake", text, ToolOutcome.SUCCESS)))

    @Test fun `disabled module makes no provider or tool calls`() = runBlocking {
        var calls = 0
        val result = AgentModule().run(AgentProvider { calls++; AgentReply("unexpected") }, listOf(tool { calls++; ToolOutput("unexpected") }), "skill", "user")
        assertEquals(AgentFinish.DISABLED, result.finish); assertEquals(0, calls)
    }

    @Test fun `fake model tool and final response keep complete protocol blocks`() = runBlocking {
        var requests = 0
        var tools = 0
        val result = AgentModule(true).run(AgentProvider { request ->
            assertEquals("pinned skill", request.instructions)
            if (requests++ == 0) AgentReply(calls = listOf(call())) else {
                assertEquals("result", request.turns.last().exchanges.single().results.single().text)
                AgentReply("done")
            }
        }, listOf(tool { tools++; ToolOutput("result") }), "pinned skill", "user")
        assertEquals(AgentFinish.COMPLETE, result.finish); assertEquals(1, tools); assertEquals(2, requests)
    }

    @Test fun `owner instructions reach every request unchanged and the base instructions stay as given`() = runBlocking {
        val seen = ArrayList<AgentRequest>()
        var steps = 0
        AgentModule(true).run(AgentProvider { request ->
            seen += request
            if (steps++ == 0) AgentReply(calls = listOf(call())) else AgentReply("done")
        }, listOf(tool { ToolOutput("result") }), "base", "user", ownerInstructions = "owner text")
        assertEquals(2, seen.size)
        assertTrue(seen.all { it.instructions == "base" && it.ownerInstructions == "owner text" })
        val plain = ArrayList<AgentRequest>()
        AgentModule(true).run(AgentProvider { request -> plain += request; AgentReply("done") }, emptyList(), "base", "user")
        assertEquals("", plain.single().ownerInstructions)
        assertEquals("base", plain.single().systemContent())
        assertEquals("base\n\n$OWNER_INSTRUCTIONS_HEADER\nowner text", seen.first().systemContent())
    }

    @Test fun `owner instructions count against the window and never become the history`() {
        val window = selectAgentWindow("skill", emptyList(), emptyList(), AgentTurn("current"), AgentLimits(contextTokens = 2048, outputReserveTokens = 100),
            ownerInstructions = "o".repeat(300))
        assertEquals("o".repeat(300), window.request!!.ownerInstructions)
        assertTrue(window.request.turns.single().user == "current")
        assertTrue(window.estimatedTokens > Utf8TokenEstimate.count(AgentRequest("skill", emptyList(), listOf(AgentTurn("current")), 100)))
    }

    @Test fun `window drops whole old turns never the current request or instructions`() {
        val history = listOf(AgentTurn("old", listOf(exchange("x".repeat(500)))), AgentTurn("recent"))
        val window = selectAgentWindow("skill", emptyList(), history, AgentTurn("current"), AgentLimits(contextTokens = 512, outputReserveTokens = 100), textCounter)
        assertEquals(1, window.omittedTurns)
        assertEquals(listOf("recent", "current"), window.request!!.turns.map { it.user })
        assertEquals("skill", window.request.instructions)
    }

    @Test fun `window trims whole older current exchanges and preserves latest result`() {
        val window = selectAgentWindow("skill", emptyList(), emptyList(), AgentTurn("current", listOf(exchange("a".repeat(300)), exchange("b".repeat(300)))),
            AgentLimits(contextTokens = 512, outputReserveTokens = 100), textCounter)
        assertEquals(1, window.omittedExchanges)
        assertEquals("b".repeat(300), window.request!!.turns.single().exchanges.single().results.single().text)
    }

    @Test fun `oversized mandatory request and latest result fail instead of disappearing`() = runBlocking {
        var requests = 0
        val result = AgentModule(true).run(AgentProvider { requests++; AgentReply("bad") }, emptyList(), "x".repeat(600), "user",
            limits = AgentLimits(contextTokens = 512, outputReserveTokens = 100), counter = textCounter)
        assertEquals(AgentFinish.CONTEXT_FULL, result.finish); assertEquals(0, requests)
        val window = selectAgentWindow("skill", emptyList(), emptyList(), AgentTurn("current", listOf(exchange("x".repeat(500)))),
            AgentLimits(contextTokens = 512, outputReserveTokens = 100), textCounter)
        assertNull(window.request)
    }

    @Test fun `tool output is bounded marked and retains both ends without broken surrogate pairs`() {
        val text = "😀".repeat(100)
        val clipped = toolResult(call(), ToolOutput(text), 32)
        assertTrue(clipped.text.length <= 32); assertTrue(clipped.omittedChars > 0)
        assertTrue(clipped.text.contains("[…]")); assertTrue(clipped.text.startsWith("😀")); assertTrue(clipped.text.endsWith("😀"))
        assertEquals(clipped.text, clipped.text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertEquals(0, toolResult(call(), ToolOutput("short"), 32).omittedChars)
    }

    @Test fun `invalid batch never partially executes and duplicate IDs never repeat a tool`() = runBlocking {
        var calls = 0
        val module = AgentModule(true)
        val bad = module.run(AgentProvider { AgentReply(calls = listOf(call(), call())) }, listOf(tool { calls++; ToolOutput("x") }), "", "user")
        assertEquals(AgentFinish.INVALID_REPLY, bad.finish); assertEquals(0, calls)
        val duplicate = module.run(AgentProvider { AgentReply(calls = listOf(call())) }, listOf(tool { calls++; ToolOutput("x") }), "", "user")
        assertEquals(AgentFinish.INVALID_REPLY, duplicate.finish); assertEquals(1, calls)
    }

    @Test fun `unknown tools become bounded results and failures never expose exception messages`() = runBlocking {
        var requests = 0
        val unknown = AgentModule(true).run(AgentProvider { if (requests++ == 0) AgentReply(calls = listOf(call(name = "missing"))) else AgentReply("done") }, emptyList(), "", "user")
        assertEquals(ToolOutcome.UNKNOWN_TOOL, unknown.turn.exchanges.first().results.single().outcome)
        requests = 0
        val failed = AgentModule(true).run(AgentProvider { if (requests++ == 0) AgentReply(calls = listOf(call())) else AgentReply("done") },
            listOf(tool { throw java.io.IOException("synthetic-private-detail") }), "", "user")
        val output = failed.turn.exchanges.first().results.single()
        assertEquals(ToolOutcome.FAILED, output.outcome); assertFalse(output.text.contains("synthetic-private-detail"))
    }

    @Test fun `step limit prevents an unlimited tool loop`() = runBlocking {
        var step = 0
        var calls = 0
        val result = AgentModule(true).run(AgentProvider { AgentReply(calls = listOf(call((++step).toString()))) }, listOf(tool { calls++; ToolOutput("ok") }), "", "user", limits = AgentLimits(maxSteps = 2))
        assertEquals(AgentFinish.STEP_LIMIT, result.finish); assertEquals(2, calls)
    }

    @Test fun `model and tool timeouts do not retry a call`() = runBlocking {
        val model = AgentModule(true).run(AgentProvider { awaitCancellation() }, emptyList(), "", "user", limits = AgentLimits(modelTimeoutMs = 10))
        assertEquals(AgentFinish.MODEL_TIMEOUT, model.finish)
        var requests = 0
        var calls = 0
        val result = AgentModule(true).run(AgentProvider { if (requests++ == 0) AgentReply(calls = listOf(call())) else AgentReply("done") },
            listOf(tool { calls++; awaitCancellation() }), "", "user", limits = AgentLimits(toolTimeoutMs = 10))
        assertEquals(1, calls); assertEquals(ToolOutcome.TIMEOUT, result.turn.exchanges.first().results.single().outcome)
    }

    @Test fun `disable during noncooperative model invalidates old continuation and cannot start a second run`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val module = AgentModule(true)
        var tools = 0
        val old = async { module.run(AgentProvider {
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            AgentReply(calls = listOf(call()))
        }, listOf(tool { tools++; ToolOutput("bad") }), "", "user") }
        withTimeout(2000) { entered.await() }
        module.setEnabled(false); module.setEnabled(true)
        assertEquals(AgentFinish.BUSY, module.run(final, emptyList(), "", "new").finish)
        release.complete(Unit)
        try { old.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, tools); assertFalse(module.isRunning); assertTrue(currentCoroutineContext().isActive)
        assertEquals(AgentFinish.COMPLETE, module.run(final, emptyList(), "", "new").finish)
    }

    @Test fun `disable in calling observer prevents actual tool execution`() = runBlocking {
        val module = AgentModule(true); var calls = 0
        try {
            module.run(AgentProvider { AgentReply(calls = listOf(call())) }, listOf(tool { calls++; ToolOutput("bad") }), "", "user",
                onEvent = { if (it is AgentEvent.Calling) module.setEnabled(false) })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, calls); assertFalse(module.isRunning); assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun `disable during tool execution cancels only its child and prevents the next model call`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); var requests = 0
        val module = AgentModule(true)
        val run = async { module.run(AgentProvider { requests++; AgentReply(calls = listOf(call())) },
            listOf(tool { entered.complete(Unit); awaitCancellation() }), "", "user") }
        withTimeout(2000) { entered.await() }
        module.setEnabled(false)
        try { run.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(1, requests); assertFalse(module.isRunning); assertTrue(currentCoroutineContext().isActive)
        assertEquals(AgentFinish.DISABLED, module.run(final, emptyList(), "", "new").finish)
    }

    @Test fun `stop keeps module enabled and a later explicit run is independent`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val module = AgentModule(true)
        val run = async { module.run(AgentProvider { entered.complete(Unit); awaitCancellation() }, emptyList(), "", "user") }
        withTimeout(2000) { entered.await() }
        module.stop()
        try { run.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertTrue(module.isEnabled); assertFalse(module.isRunning)
        assertEquals(AgentFinish.COMPLETE, module.run(final, emptyList(), "", "new").finish)
    }

    @Test fun `reply size and schema overhead are bounded before tools or model dispatch`() = runBlocking {
        val oversized = AgentModule(true).run(AgentProvider { AgentReply("x".repeat(33)) }, emptyList(), "", "user", limits = AgentLimits(maxReplyChars = 32))
        assertEquals(AgentFinish.INVALID_REPLY, oversized.finish)
        val big = object : AgentTool {
            override val spec = ToolSpec("big", "x".repeat(1000), args)
            override suspend fun execute(arguments: JsonObject): ToolOutput = error("Must not run")
        }
        var requests = 0
        val result = AgentModule(true).run(AgentProvider { requests++; AgentReply("bad") }, listOf(big), "", "user", limits = AgentLimits(contextTokens = 512, outputReserveTokens = 100))
        assertEquals(AgentFinish.CONTEXT_FULL, result.finish); assertEquals(0, requests)
    }

    @Test fun `authoritative setting check blocks starts before an asynchronous observer catches up`() = runBlocking {
        var allowed = true
        val module = AgentModule(true) { allowed }
        allowed = false
        assertEquals(AgentFinish.DISABLED, module.run(final, emptyList(), "", "user").finish)
    }

    @Test fun `opaque continuation counts against window and reply budgets without becoming UI text`() = runBlocking {
        val opaque = object : AgentContinuation {
            override val estimatedTokens = 1000L
            override val sizeChars = 1000
            override fun toString() = "<continuation>"
        }
        val reply = AgentReply("visible", continuation = opaque)
        val window = selectAgentWindow("skill", emptyList(), emptyList(), AgentTurn("user", listOf(AgentExchange(reply))),
            AgentLimits(contextTokens = 512, outputReserveTokens = 100))
        assertNull(window.request)
        val result = AgentModule(true).run(AgentProvider { reply }, emptyList(), "", "user", limits = AgentLimits(maxReplyChars = 32))
        assertEquals(AgentFinish.INVALID_REPLY, result.finish)
    }

    @Test fun `incomplete or mismatched protocol blocks and invalid limits are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AgentExchange(AgentReply(calls = listOf(call()))) }
        assertThrows(IllegalArgumentException::class.java) { AgentLimits(maxSteps = 0) }
        assertThrows(IllegalArgumentException::class.java) { AgentLimits(outputReserveTokens = 16_384) }
    }
}
