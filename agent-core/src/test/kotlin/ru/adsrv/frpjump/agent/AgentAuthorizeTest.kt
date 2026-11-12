package ru.adsrv.frpjump.agent

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

/** The `authorize` hook: before the tool timeout, owner re-checked after it, nothing runs when it refuses or fails. */
class AgentAuthorizeTest {
    private val args = JsonObject(emptyMap())
    private fun call(name: String = "fake") = ToolCall("one", name, args)
    private fun tool(action: suspend () -> ToolOutput) = object : AgentTool {
        override val spec = ToolSpec("fake", "Synthetic fixture", args)
        override suspend fun execute(arguments: JsonObject) = action()
    }
    private fun provider(name: String = "fake"): AgentProvider { var n = 0; return AgentProvider { if (n++ == 0) AgentReply(calls = listOf(call(name))) else AgentReply("done") } }

    @Test fun `no hook means every tool executes as before`() = runBlocking {
        var runs = 0
        val result = AgentModule(true).run(provider(), listOf(tool { runs++; ToolOutput("ok") }), "", "user")
        assertEquals(1, runs); assertEquals(ToolOutcome.SUCCESS, result.turn.exchanges.first().results.single().outcome)
    }

    @Test fun `hook runs after Calling and before the tool and null executes`() = runBlocking {
        val order = ArrayList<String>()
        AgentModule(true).run(provider(), listOf(tool { order += "tool"; ToolOutput("ok") }), "", "user",
            onEvent = { if (it is AgentEvent.Calling) order += "calling" }, authorize = { order += "authorize"; null })
        assertEquals(listOf("calling", "authorize", "tool"), order)
    }

    @Test fun `a refusal is the recorded result and the tool never runs`() = runBlocking {
        var runs = 0
        val seen = ArrayList<ToolResult>()
        val result = AgentModule(true).run(AgentProvider { request ->
            val last = request.turns.last().exchanges.lastOrNull()
            if (last == null) AgentReply(calls = listOf(call())) else { seen += last.results; AgentReply("done") }
        }, listOf(tool { runs++; ToolOutput("bad") }), "", "user", authorize = { ToolOutput("declined text", ToolOutcome.DECLINED) })
        assertEquals(0, runs)
        val recorded = result.turn.exchanges.first().results.single()
        assertEquals(ToolOutcome.DECLINED, recorded.outcome); assertEquals("declined text", recorded.text)
        assertEquals(listOf(recorded), seen); assertEquals(AgentFinish.COMPLETE, result.finish)
    }

    @Test fun `unknown tools do not reach the hook`() = runBlocking {
        var asked = 0
        val result = AgentModule(true).run(provider("missing"), emptyList(), "", "user", authorize = { asked++; null })
        assertEquals(0, asked); assertEquals(ToolOutcome.UNKNOWN_TOOL, result.turn.exchanges.first().results.single().outcome)
    }

    @Test fun `a failing hook never lets the tool run and exposes no message`() = runBlocking {
        var runs = 0
        val result = AgentModule(true).run(provider(), listOf(tool { runs++; ToolOutput("bad") }), "", "user", authorize = { throw java.io.IOException("synthetic-private-detail") })
        assertEquals(0, runs)
        val recorded = result.turn.exchanges.first().results.single()
        assertEquals(ToolOutcome.FAILED, recorded.outcome); assertFalse(recorded.text.contains("synthetic-private-detail"))
    }

    @Test fun `the wait is in neither the tool timeout nor the model timeout`() = runBlocking {
        var runs = 0
        val limits = AgentLimits(toolTimeoutMs = 20, modelTimeoutMs = 200)
        val result = AgentModule(true).run(provider(), listOf(tool { runs++; ToolOutput("ok") }), "", "user", limits = limits,
            authorize = { delay(300); null })
        assertEquals(1, runs)
        assertEquals(ToolOutcome.SUCCESS, result.turn.exchanges.first().results.single().outcome)
        assertEquals(AgentFinish.COMPLETE, result.finish)
    }

    @Test fun `Stop during the wait cancels without running the tool and frees the slot`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); var runs = 0
        val module = AgentModule(true)
        val run = async { module.run(provider(), listOf(tool { runs++; ToolOutput("bad") }), "", "user", authorize = { entered.complete(Unit); awaitCancellation() }) }
        withTimeout(2000) { entered.await() }
        module.stop()
        try { run.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, runs); assertFalse(module.isRunning); assertTrue(currentCoroutineContext().isActive)
        assertEquals(AgentFinish.COMPLETE, module.run(AgentProvider { AgentReply("again") }, emptyList(), "", "next").finish)
    }

    @Test fun `disable during the wait cancels and runs nothing`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); var runs = 0
        val module = AgentModule(true)
        val run = async { module.run(provider(), listOf(tool { runs++; ToolOutput("bad") }), "", "user", authorize = { entered.complete(Unit); awaitCancellation() }) }
        withTimeout(2000) { entered.await() }
        module.setEnabled(false)
        try { run.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, runs); assertFalse(module.isRunning)
    }

    @Test fun `an answer that arrives after Stop still runs nothing because the owner is checked after the hook`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var runs = 0
        val module = AgentModule(true)
        val run = async { module.run(provider(), listOf(tool { runs++; ToolOutput("bad") }), "", "user", authorize = {
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            null
        }) }
        withTimeout(2000) { entered.await() }
        module.stop()
        release.complete(Unit)
        try { run.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, runs); assertFalse(module.isRunning)
    }
}
