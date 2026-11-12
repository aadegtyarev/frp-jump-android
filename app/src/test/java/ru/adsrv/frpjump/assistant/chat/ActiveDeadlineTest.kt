package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import kotlinx.serialization.json.JsonObject

class ActiveDeadlineTest {
    private fun expires(vt: VirtualTime, deadline: ActiveDeadline, body: suspend CoroutineScope.() -> Unit): Boolean = try {
        vt.run { deadline.guard(body) }; false
    } catch (_: ActiveDeadlineExpired) { true }

    @Test fun `a body that finishes in time returns its value and leaves nothing scheduled`() {
        val vt = VirtualTime()
        assertEquals("v", vt.run { ActiveDeadline(120_000, { vt.now }).guard { delay(119_999); "v" } })
    }

    @Test fun `active time over the limit expires at the limit`() {
        val vt = VirtualTime()
        var reachedAfter = false
        assertTrue(expires(vt, ActiveDeadline(120_000, { vt.now })) { delay(120_001); reachedAfter = true })
        assertFalse(reachedAfter); assertEquals(120_000, vt.now)
    }

    @Test fun `paused time is not counted and the remainder still applies`() {
        val vt = VirtualTime()
        val deadline = ActiveDeadline(120_000, { vt.now })
        var finished = false
        vt.run { deadline.guard { delay(100_000); deadline.paused { delay(600_000) }; delay(19_000); finished = true } }
        assertTrue(finished); assertEquals(719_000, vt.now)
        val vt2 = VirtualTime()
        val second = ActiveDeadline(120_000, { vt2.now })
        var late = false
        assertTrue(expires(vt2, second) { delay(100_000); second.paused { delay(600_000) }; delay(21_000); late = true })
        assertFalse(late); assertEquals(720_000, vt2.now)
    }

    @Test fun `pause and resume are idempotent and several pauses add up`() {
        val vt = VirtualTime()
        val deadline = ActiveDeadline(100, { vt.now })
        var done = false
        vt.run { deadline.guard {
            delay(40); deadline.pause(); deadline.pause(); delay(1_000); deadline.resume(); deadline.resume()
            delay(40); deadline.paused { delay(5_000) }; delay(19); done = true
        } }
        assertTrue(done)
        val vt2 = VirtualTime(); val tight = ActiveDeadline(100, { vt2.now })
        assertTrue(expires(vt2, tight) { delay(40); tight.pause(); delay(1_000); tight.resume(); delay(40); tight.paused { delay(5_000) }; delay(21) })
    }

    @Test fun `paused resumes the clock when cancelled during the wait`() {
        // After cancellation the clock is running: a fresh guard on the same object still expires on active time.
        val vt2 = VirtualTime()
        val d2 = ActiveDeadline(100, { vt2.now })
        assertTrue(try { vt2.run { val w = launch { d2.paused { awaitCancellation() } }; yield(); w.cancelAndJoin(); d2.guard { delay(1_000) } }; false } catch (_: ActiveDeadlineExpired) { true })
    }

    @Test fun `outer cancellation stays a plain cancellation and an inner failure propagates`() {
        val vt = VirtualTime()
        vt.run {
            val job = launch { try { ActiveDeadline(1_000, { vt.now }).guard { awaitCancellation() } } catch (c: CancellationException) { assertFalse(c is ActiveDeadlineExpired); throw c } }
            yield(); job.cancelAndJoin(); assertTrue(job.isCancelled)
        }
        val failure = try { vt.run { ActiveDeadline(1_000, { vt.now }).guard<Unit> { throw IllegalStateException("boom") } }; null } catch (e: IllegalStateException) { e }
        assertEquals("boom", failure?.message)
    }

    @Test fun `the owner's wait of five minutes inside a Send counts for neither tool, model nor Send time`() {
        val vt = VirtualTime()
        val deadline = ActiveDeadline(120_000, { vt.now })
        var runs = 0; var step = 0
        val tool = object : AgentTool {
            override val spec = ToolSpec("fake", "Synthetic", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject): ToolOutput { runs++; delay(20_000); return ToolOutput("ok") }
        }
        val provider = AgentProvider { delay(40_000); if (step++ == 0) AgentReply(calls = listOf(ToolCall("c", "fake", JsonObject(emptyMap())))) else AgentReply("done") }
        val result = vt.run { deadline.guard { AgentModule(true).run(provider, listOf(tool), "", "u",
            authorize = { deadline.paused { delay(300_000) }; null }) } }
        assertEquals(AgentFinish.COMPLETE, result.finish); assertEquals(1, runs)
        assertEquals(ToolOutcome.SUCCESS, result.turn.exchanges.first().results.single().outcome)
        assertEquals(400_000, vt.now)
    }

    @Test fun `without the pause the same Send runs out of active time`() {
        val vt = VirtualTime()
        val deadline = ActiveDeadline(120_000, { vt.now })
        var step = 0
        val provider = AgentProvider { delay(40_000); if (step++ == 0) AgentReply(calls = listOf(ToolCall("c", "fake", JsonObject(emptyMap())))) else AgentReply("done") }
        val tool = object : AgentTool {
            override val spec = ToolSpec("fake", "Synthetic", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject) = ToolOutput("ok")
        }
        assertTrue(expires(vt, deadline) { AgentModule(true).run(provider, listOf(tool), "", "u", authorize = { delay(300_000); null }) })
    }
}
