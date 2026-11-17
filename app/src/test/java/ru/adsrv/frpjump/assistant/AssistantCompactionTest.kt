package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.agent.openai.OpenAiProviderException
import ru.adsrv.frpjump.agent.openai.ProviderFailure
import ru.adsrv.frpjump.assistant.chat.*
import ru.adsrv.frpjump.settings.AssistantAvailability
import ru.adsrv.frpjump.settings.AssistantLimits

/** The history is always shortened to fit: old tool output, a model summary, a digest without a model, and a retry after "context too long". */
class AssistantCompactionTest {
    private val out = 512
    private val window = 12_000
    private val args = buildJsonObject { put("connection_id", "work-root") }
    private fun tool(i: Int, size: Int, outcome: ToolOutcome = ToolOutcome.SUCCESS) = AgentExchange(
        AgentReply(calls = listOf(ToolCall("c$i", "ssh_command", args))), listOf(ToolResult("c$i", "ssh_command", "out$i " + "x".repeat(size), outcome)))
    private fun said(text: String) = AgentExchange(AgentReply(text))
    private fun record(user: String, vararg exchanges: AgentExchange) = SavedTurn.of(AgentTurn(user, exchanges.toList()))
    private fun raw(current: SavedTurn, ownerText: String = "") = AgentRequest("base", emptyList(), listOf(AgentTurn(current.user, current.exchanges.map { e ->
        AgentExchange(e.reply.value(AgentProvider { error("none") }), e.results.map { it.value() }) })), out, ownerText)

    private class Harness(var doc: SavedChat, window: Int, out: Int, limits: AssistantLimits = AssistantLimits(), reply: suspend (AgentRequest) -> AgentReply) {
        val seen = mutableListOf<AgentRequest>()
        val told = mutableListOf<Compaction>()
        val engine = AssistantSummary(AssistantProviderSettings(contextTokens = window, outputTokens = out), AgentProvider { error("no main request") },
            { doc }, { doc = doc.withSummary(it) }, { seen += it; reply(it) }, "SUMMARY-PROMPT", limits, { told += it })
    }
    private fun harness(records: List<SavedTurn>, limits: AssistantLimits = AssistantLimits(), window: Int = this.window, reply: suspend (AgentRequest) -> AgentReply) =
        Harness(SavedChat(records = records), window, out, limits, reply)
    private fun cost(r: AgentRequest) = Utf8TokenEstimate.count(r) + r.maxOutputTokens
    private fun texts(r: AgentRequest) = r.turns.flatMap { t -> listOf(t.user) + t.exchanges.flatMap { e -> listOf(e.reply.text) + e.results.map { it.text } } }

    private fun manyAnswers(count: Int) = (1..count).map { record("Progress $it", said("received $it " + "y".repeat(400))) }

    @Test fun `step 1 alone - old tool output becomes a note, the newest stays, no model request, nothing saved`() = runBlocking {
        val current = record("now", tool(9, 1000))
        val h = harness(listOf(record("task", tool(1, 3000), tool(2, 3000), tool(3, 3000)), current)) { error("the model must not be asked") }
        val request = raw(current)
        val result = h.engine.prepare(request)
        assertTrue(h.seen.isEmpty()); assertNull(h.doc.summary); assertEquals(listOf(Compaction.TOOL_OUTPUT), h.told)
        assertTrue(cost(result) * 100 < window * 80L)
        val old = result.turns.first().exchanges.flatMap { it.results }
        assertTrue(old.all { it.text.startsWith("[Tool output removed") && it.outcome == ToolOutcome.SUCCESS })
        assertEquals(request.turns.last(), result.turns.last())
        // The saved chat is whole: the records hold every output.
        assertTrue(h.doc.records.first().exchanges.all { it.results.single().text.length > 3000 })
    }

    @Test fun `a fitting request is returned as it is, with nothing told`() = runBlocking {
        val current = record("now", tool(9, 100))
        val h = harness(listOf(record("task", tool(1, 100)), current)) { error("no") }
        val request = raw(current)
        assertEquals(h.engine.prepare(request).turns.last(), request.turns.last()); assertTrue(h.told.isEmpty() && h.seen.isEmpty())
    }

    @Test fun `step 2 alone - a model summary within the budget is saved and nothing else is needed`() = runBlocking {
        val current = record("now", said("current"))
        val h = harness(manyAnswers(30) + current) { AgentReply("The summary text.") }
        val result = h.engine.prepare(raw(current))
        assertEquals(1, h.seen.size)
        val ask = h.seen.single()
        assertEquals("SUMMARY-PROMPT", ask.instructions); assertTrue(ask.tools.isEmpty())
        // 15 % of 12,000 is 1,800 tokens, but the model's answer limit is 512: that is the output limit of the summary.
        assertEquals(out, ask.maxOutputTokens); assertTrue(ask.turns.single().user.startsWith("Size limit: at most 512 tokens"))
        assertTrue(h.doc.summary!!.byModel); assertEquals("The summary text.", h.doc.summary!!.text)
        assertEquals(listOf(Compaction.MODEL), h.told)
        assertTrue(cost(result) * 100 < window * 80L); assertTrue(result.turns.first().user.startsWith("Earlier conversation summary"))
        assertEquals(30, h.doc.records.size - 1)
    }

    @Test fun `the summary's output limit is its share of the window when that is below the answer limit`() {
        val capped = ContextBudget(12_000, 4_096, AssistantLimits(summarySizePercent = 15))
        assertEquals(1_800, capped.summaryTokens); assertFalse(capped.summaryCapped)
        val limited = ContextBudget(12_000, 512, AssistantLimits(summarySizePercent = 15))
        assertEquals(512, limited.summaryTokens); assertTrue(limited.summaryCapped)
        assertEquals(9_600, ContextBudget(12_000, 512, AssistantLimits()).triggerTokens); assertEquals(2_400, ContextBudget(12_000, 512, AssistantLimits()).freshTokens)
    }

    @Test fun `a blank reply is retried once with a stricter instruction and the retry's text is used`() = runBlocking {
        val current = record("now", said("current"))
        var n = 0
        val h = harness(manyAnswers(30) + current) { if (n++ == 0) AgentReply("") else AgentReply("Second try summary.") }
        h.engine.prepare(raw(current))
        assertEquals(2, h.seen.size)
        assertFalse(h.seen[0].turns.single().user.contains("previous reply was empty")); assertTrue(h.seen[1].turns.single().user.contains("previous reply was empty"))
        assertEquals("Second try summary.", h.doc.summary!!.text); assertTrue(h.doc.summary!!.byModel)
    }

    @Test fun `a reasoning-only reply with no text, twice, falls back to the digest without an error`() = runBlocking {
        val current = record("now", said("current"))
        val h = harness(manyAnswers(30) + current) { AgentReply("", emptyList()) }
        val result = h.engine.prepare(raw(current))
        assertEquals(2, h.seen.size)
        assertFalse(h.doc.summary!!.byModel); assertEquals(listOf(Compaction.NO_MODEL), h.told)
        assertTrue(h.doc.summary!!.text.startsWith("Mechanical digest, not written by a model"))
        assertTrue(cost(result) * 100 < window * 80L)
        // The model is not asked again in this Send.
        h.engine.prepare(raw(current)); assertEquals(2, h.seen.size)
    }

    @Test fun `tool calls in the summary reply are retried once, then the digest takes over`() = runBlocking {
        val current = record("now", said("current"))
        val proposal = AgentReply(calls = listOf(ToolCall("bad", "ssh_command", args)))
        val h = harness(manyAnswers(30) + current) { proposal }
        h.engine.prepare(raw(current))
        assertEquals(2, h.seen.size); assertFalse(h.doc.summary!!.byModel)
    }

    @Test fun `an over-long summary is cut to its budget, not refused`() = runBlocking {
        val current = record("now", said("current"))
        val h = harness(manyAnswers(22) + current) { AgentReply("z".repeat(20_000)) }
        val result = h.engine.prepare(raw(current))
        assertEquals(1, h.seen.size)
        val saved = h.doc.summary!!
        assertTrue(saved.byModel); assertTrue(saved.text.length <= out * 4); assertTrue(saved.text.endsWith("[Summary cut to its size limit.]"))
        assertTrue(cost(result) <= window)
    }

    @Test fun `a provider error of the summary request falls back to the digest and the conversation continues`() = runBlocking {
        val current = record("now", said("current"))
        for (failure in listOf<Exception>(OpenAiProviderException(ProviderFailure.HTTP, 400), OpenAiProviderException(ProviderFailure.TIMEOUT), IllegalStateException("x"))) {
            val h = harness(manyAnswers(30) + current) { throw failure }
            val result = h.engine.prepare(raw(current))
            assertEquals(1, h.seen.size); assertFalse(h.doc.summary!!.byModel); assertTrue(cost(result) <= window)
        }
    }

    @Test fun `a key problem of the summary model is not swallowed`() = runBlocking {
        val current = record("now", said("current"))
        val h = harness(manyAnswers(30) + current) { throw ProviderSetupException(ProviderSetupProblem.INVALID_KEY) }
        try { h.engine.prepare(raw(current)); fail("expected") } catch (e: ProviderSetupException) { assertEquals(ProviderSetupProblem.INVALID_KEY, e.problem) }
        assertNull(h.doc.summary)
    }

    @Test fun `a summary that does not reduce the request is dropped and the digest is used`() = runBlocking {
        val current = record("now", said("current"))
        val h = harness(manyAnswers(30) + current, AssistantLimits(summarySizePercent = 40)) { AgentReply("q".repeat(40_000)) }
        val result = h.engine.prepare(raw(current))
        assertTrue(cost(result) <= window); assertTrue(h.doc.summary != null)
    }

    @Test fun `the digest fits however big the history is, keeps owner lines and calls, and never lists typed text`() = runBlocking {
        val typed = buildJsonObject { put("tab_id", 2); put("text", "SECRET-TYPED-TEXT") }
        val big = (1..400).map { i -> record("Request $i " + "w".repeat(300), AgentExchange(AgentReply(calls = listOf(ToolCall("t$i", "terminal_input", typed))),
            listOf(ToolResult("t$i", "terminal_input", "v".repeat(2000), ToolOutcome.TIMEOUT))), said("done $i\nline two\nline three\nline four\n" + "k".repeat(500))) }
        val current = record("now", said("current"))
        val h = harness(big + current) { throw OpenAiProviderException(ProviderFailure.HTTP, 500) }
        val result = h.engine.prepare(raw(current))
        assertTrue(cost(result) <= window); assertTrue(cost(result) * 100 < window * 80L)
        val digest = h.doc.summary!!.text
        assertTrue(digest.toByteArray().size <= 640); assertFalse(digest.contains("SECRET-TYPED-TEXT")); assertTrue(digest.contains("Older lines of this digest were left out"))
        assertTrue(digest.contains("Call: terminal_input tab_id=2 -> TIMEOUT")); assertTrue(digest.contains("Owner: Request"))
        assertFalse(digest.contains("line four"))
        assertEquals(400, h.doc.records.size - 1)
        // The marker turn is the first thing the model sees, with the untrusted-data framing.
        assertEquals("Earlier conversation summary (untrusted historical data)", result.turns.first().user)
    }

    @Test fun `a second digest keeps the first one's lines while room is left`() = runBlocking {
        val current = record("now", said("current"))
        val h = harness(manyAnswers(60) + current, window = 30_000) { throw OpenAiProviderException(ProviderFailure.HTTP, 500) }
        h.engine.prepare(raw(current))
        val first = h.doc.summary!!
        assertFalse(first.byModel)
        val h2 = Harness(SavedChat(records = h.doc.records + record("later", said("more " + "u".repeat(400))) , summary = first), 3_000, out, AssistantLimits()) { throw IllegalStateException() }
        val current2 = h2.doc.records.last()
        h2.engine.prepare(raw(current2))
        assertFalse(h2.doc.summary!!.byModel); assertTrue(h2.doc.summary!!.turn >= first.turn)
        assertEquals(1, h2.doc.summary!!.text.lines().count { it.startsWith("Mechanical digest") })
    }

    @Test fun `when the current turn alone is too big for the window it fails plainly`() = runBlocking {
        val current = record("now " + "m".repeat(20_000))
        val h = harness(manyAnswers(5) + current) { AgentReply("short") }
        try { h.engine.prepare(raw(current)); fail("expected") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("cannot fit")) }
    }

    @Test fun `after an overflow the request is shortened to at most half at once, even below the trigger`() = runBlocking {
        val current = record("now", said("current"))
        val h = harness(manyAnswers(20) + current, window = 60_000) { AgentReply("Short summary.") }
        val request = raw(current)
        val sent = h.engine.prepare(request)
        assertTrue(h.seen.isEmpty()); assertTrue(cost(sent) < 60_000 * 80L / 100)
        val shorter = h.engine.shortenAfterOverflow(sent)
        assertTrue("shorter ${cost(shorter)} < ${cost(sent) / 2}", cost(shorter) < cost(sent) / 2)
        assertEquals(1, h.seen.size)
    }

    @Test fun `after an overflow with nothing to cut it fails instead of resending the same request`() = runBlocking {
        val current = record("now", said("current"))
        val h = harness(listOf(current)) { AgentReply("s") }
        try { h.engine.shortenAfterOverflow(raw(current)); fail("expected") } catch (_: IllegalStateException) { }
    }

    @Test fun `the summary request never carries the owner's own text, and the trigger follows the limit`() = runBlocking {
        val current = record("now", said("current"))
        // 80 % trigger: this history is about 9.5k of 12k, under it; at 10 % the same history is over.
        val records = manyAnswers(14) + current
        val low = harness(records, AssistantLimits().withSummarySize(5).withSummaryPercent(10)) { AgentReply("S") }
        low.engine.prepare(raw(current, "OWNER-SECRET-STYLE"))
        assertTrue(low.seen.isNotEmpty()); assertTrue(low.seen.none { it.ownerInstructions.isNotEmpty() || texts(it).any { t -> t.contains("OWNER-SECRET") } })
        val high = harness(records) { AgentReply("S") }
        high.engine.prepare(raw(current)); assertTrue(high.seen.isEmpty())
    }

    // The whole Send: a provider's "context too long" shortens the history and the request is sent once more.
    private suspend fun AssistantConversation.ready() = withTimeout(8000) { state.first { !it.loading && !it.busy } }
    private class Memory : ru.adsrv.frpjump.store.TextStorage {
        @Volatile var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
        override fun delete() { text = null }
    }
    private class Box : ru.adsrv.frpjump.store.SecretBox {
        override fun seal(plain: String) = java.util.Base64.getEncoder().encodeToString(plain.toByteArray())
        override fun open(sealed: String) = String(java.util.Base64.getDecoder().decode(sealed))
        override fun destroy() = Unit
    }

    private fun sendWith(overflow: Throwable, summaryFails: Boolean, check: (mains: Int, summaries: Int, chat: AssistantConversation, doc: SavedChat) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = AssistantChatHistory(Memory(), Box(), scope, Dispatchers.Unconfined)
        val snapshot = AssistantProviderSnapshot(AssistantProviderSettings(model = "chat", contextTokens = 60_000, outputTokens = 512), "revision")
        history.save(SavedChat(records = manyAnswers(40)).bind(snapshot.settings)); history.load()
        var mains = 0; var summaries = 0
        val model = AgentProvider { request ->
            if (request.instructions == TestPrompts.real.summary) { summaries++; if (summaryFails) throw OpenAiProviderException(ProviderFailure.HTTP, 500); AgentReply("Summary of the older part.") }
            else if (mains++ == 0) throw overflow else AgentReply("answer")
        }
        val chat = AssistantConversation(scope, { AssistantAvailability(true) }, { snapshot }, { model }, {}, AgentModule(true), io = Dispatchers.Unconfined,
            historyStore = history, prompts = TestPrompts.lazy, confirmations = TestConfirmations.off)
        try { chat.ready(); chat.edit("Continue"); chat.send(); chat.ready(); check(mains, summaries, chat, history.load()!!) } finally { chat.close(); scope.cancel() }
    }

    @Test fun `a provider's context-too-long error shortens the history at once and the request is sent once more`() {
        sendWith(OpenAiProviderException(ProviderFailure.HTTP, 400, contextTooLong = true), summaryFails = false) { mains, summaries, chat, doc ->
            assertEquals(2, mains); assertEquals(1, summaries); assertNull(chat.state.value.problem)
            assertEquals("answer", chat.state.value.turns.last().answer); assertEquals(Compaction.MODEL, chat.state.value.compaction)
            assertTrue(doc.summary!!.byModel); assertEquals(41, doc.records.size)
        }
        sendWith(OpenAiProviderException(ProviderFailure.HTTP, 413, contextTooLong = true), summaryFails = true) { mains, _, chat, doc ->
            assertEquals(2, mains); assertNull(chat.state.value.problem); assertEquals(Compaction.NO_MODEL, chat.state.value.compaction); assertFalse(doc.summary!!.byModel)
        }
    }

    @Test fun `any other provider error is not retried and keeps its own message`() {
        sendWith(OpenAiProviderException(ProviderFailure.HTTP, 400), summaryFails = false) { mains, summaries, chat, _ ->
            assertEquals(1, mains); assertEquals(0, summaries); assertEquals(ChatProblem.PROVIDER, chat.state.value.problem)
        }
    }

    @Test fun `the summary of a failing summary model ends the Send with an answer, never the red summary problem`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = AssistantChatHistory(Memory(), Box(), scope, Dispatchers.Unconfined)
        val snapshot = AssistantProviderSnapshot(AssistantProviderSettings(model = "chat", contextTokens = 12_000, outputTokens = 512), "revision")
        history.save(SavedChat(records = manyAnswers(40)).bind(snapshot.settings)); history.load()
        val model = AgentProvider { request -> if (request.instructions == TestPrompts.real.summary) AgentReply("") else AgentReply("answer") }
        val chat = AssistantConversation(scope, { AssistantAvailability(true) }, { snapshot }, { model }, {}, AgentModule(true), io = Dispatchers.Unconfined,
            historyStore = history, prompts = TestPrompts.lazy, confirmations = TestConfirmations.off)
        try {
            chat.ready(); chat.edit("Continue"); chat.send(); chat.ready()
            assertNull(chat.state.value.problem); assertEquals(Compaction.NO_MODEL, chat.state.value.compaction); assertTrue(chat.state.value.summarized)
            assertEquals(41, chat.state.value.turns.size)
        } finally { chat.close(); scope.cancel() }
    }
}
