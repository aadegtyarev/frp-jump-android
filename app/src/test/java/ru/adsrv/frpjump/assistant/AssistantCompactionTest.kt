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

    @Test fun `step 1 alone - old tool output becomes a note, oldest first and only down to the prune size, the newest stays, no model request, nothing saved`() = runBlocking {
        val current = record("now", tool(9, 1000))
        val h = harness(listOf(record("task", tool(1, 3000), tool(2, 3000), tool(3, 3000)), current)) { error("the model must not be asked") }
        val request = raw(current)
        val result = h.engine.prepare(request)
        assertTrue(h.seen.isEmpty()); assertNull(h.doc.summary); assertEquals(listOf(Compaction.TOOL_OUTPUT), h.told)
        assertTrue(cost(result) * 100 < window * 80L)
        // The prune size at the defaults: (75 - 15 + 5) % of the window; the oldest outputs go first and the rest stay whole once it is reached.
        assertTrue(cost(result) * 100 <= window * 65L)
        val old = result.turns.first().exchanges.flatMap { it.results }
        assertTrue(old.take(2).all { it.text.startsWith("[Tool output removed") && it.outcome == ToolOutcome.SUCCESS })
        assertTrue("stopped at the prune size", old.last().text.startsWith("out3 "))
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
        // The summary's own output limit is 15 % of 12,000 = 1,800, not the model's general answer limit (512); the request says the same size, in bytes.
        assertEquals(1_800, ask.maxOutputTokens); assertTrue(ask.turns.single().user.startsWith("Size limit: at most 1800 bytes of UTF-8 text"))
        assertTrue(h.doc.summary!!.byModel); assertEquals("The summary text.", h.doc.summary!!.text)
        assertEquals(listOf(Compaction.MODEL), h.told)
        assertTrue(cost(result) * 100 < window * 80L); assertTrue(result.turns.first().user.startsWith("Earlier conversation summary"))
        assertEquals(30, h.doc.records.size - 1)
    }

    @Test fun `the window split drives the budget - trigger is history plus reply, summary is its own share, prune size is history less summary plus reply`() {
        val d = ContextBudget(12_000, AssistantLimits())
        assertEquals(1_800, d.summaryTokens); assertEquals(600, d.replyTokens)
        assertEquals(9_600, d.triggerTokens); assertEquals(7_800, d.pruneTokens)
        val custom = ContextBudget(100_000, AssistantLimits(summarySizePercent = 40, historyPercentSet = 50, replyPercentSet = 10))
        assertEquals(40_000, custom.summaryTokens); assertEquals(10_000, custom.replyTokens)
        assertEquals(60_000, custom.triggerTokens); assertEquals(20_000, custom.pruneTokens)
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
        assertTrue(h.doc.summary!!.text.startsWith("Mechanical digest of an older part"))
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

    @Test fun `an over-long summary is cut to its budget in bytes, not refused`() = runBlocking {
        val current = record("now", said("current"))
        val h = harness(manyAnswers(22) + current) { AgentReply("z".repeat(20_000)) }
        val result = h.engine.prepare(raw(current))
        assertEquals(1, h.seen.size)
        val saved = h.doc.summary!!
        assertTrue(saved.byModel); assertTrue(saved.text.toByteArray().size <= 1_800)
        assertTrue(saved.text.endsWith("[Summary cut to its size limit.]")); assertTrue(cost(result) <= window)
    }

    @Test fun `a Russian summary is cut by bytes, not characters, at a character boundary`() = runBlocking {
        val current = record("now", said("current"))
        val h = harness(manyAnswers(22) + current) { AgentReply("я".repeat(5_000)) }
        h.engine.prepare(raw(current))
        val saved = h.doc.summary!!.text
        val size = saved.toByteArray(Charsets.UTF_8).size
        assertTrue("$size bytes", size <= 1_800); assertTrue(size > 1_700)
        assertEquals(saved, String(saved.toByteArray(Charsets.UTF_8), Charsets.UTF_8)); assertFalse(saved.contains('\uFFFD'))
        // 1,000 Russian letters are fewer characters than the 1,800-byte limit but 2,000 bytes: still cut.
        val mid = harness(manyAnswers(22) + current) { AgentReply("я".repeat(1_000)) }
        mid.engine.prepare(raw(current))
        assertTrue(mid.doc.summary!!.text.toByteArray(Charsets.UTF_8).size <= 1_800); assertTrue(mid.doc.summary!!.text.endsWith("[Summary cut to its size limit.]"))
        // The limit stated in the request is the same one.
        assertTrue(h.seen.first().turns.single().user.startsWith("Size limit: at most 1800 bytes"))
    }

    @Test fun `the summary request has its own output limit, and a provider that refuses it gets the answer limit once without using the blank retry`() = runBlocking {
        val current = record("now", said("current"))
        var n = 0
        val h = harness(manyAnswers(30) + current) { request ->
            when {
                request.maxOutputTokens > 600 -> throw OpenAiProviderException(ProviderFailure.HTTP, 400)
                n++ == 0 -> AgentReply("")
                else -> AgentReply("Summary after the fallback and the retry.")
            }
        }
        h.engine.prepare(raw(current))
        // The answer limit is the reply share of the window: 5 % of 12,000.
        assertEquals(listOf(1_800, 600, 600), h.seen.map { it.maxOutputTokens })
        assertEquals("Summary after the fallback and the retry.", h.doc.summary!!.text); assertTrue(h.doc.summary!!.byModel)
        // A refusal that is not a 400/422, or one when the output limit already is the answer limit, is no reason to resend.
        val server = harness(manyAnswers(30) + current) { throw OpenAiProviderException(ProviderFailure.HTTP, 500) }
        server.engine.prepare(raw(current)); assertEquals(listOf(1_800), server.seen.map { it.maxOutputTokens }); assertFalse(server.doc.summary!!.byModel)
        val small = harness(manyAnswers(30) + current, AssistantLimits(summarySizePercent = 5, historyPercentSet = 40, replyPercentSet = 10), window = 8_000) { throw OpenAiProviderException(ProviderFailure.HTTP, 400) }
        small.engine.prepare(raw(current)); assertEquals(1, small.seen.size); assertFalse(small.doc.summary!!.byModel)
    }

    @Test fun `a hanging summary request is stopped inside its share of the timeout and the digest takes over`() = runBlocking {
        val current = record("now", said("current"))
        val h = harness(manyAnswers(30) + current) { awaitCancellation() }
        val started = System.nanoTime()
        val result = withTimeout(10_000) { h.engine.prepare(raw(current).copy(timeoutMs = 500)) }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 2_000); assertEquals(1, h.seen.size)
        assertFalse(h.doc.summary!!.byModel); assertEquals(listOf(Compaction.NO_MODEL), h.told); assertTrue(cost(result) <= window)
    }

    @Test fun `at most two model rounds per Send, then the digest`() = runBlocking {
        val current = record("now", said("current"))
        val h = harness(manyAnswers(100) + current) { AgentReply("S") }
        val result = h.engine.prepare(raw(current))
        assertEquals(2, h.seen.size); assertFalse(h.doc.summary!!.byModel); assertTrue(cost(result) * 100 < window * 80L)
    }

    @Test fun `a provider error of the summary request falls back to the digest and the conversation continues`() = runBlocking {
        val current = record("now", said("current"))
        for (failure in listOf<Exception>(OpenAiProviderException(ProviderFailure.HTTP, 500), OpenAiProviderException(ProviderFailure.TIMEOUT), IllegalStateException("x"))) {
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

    @Test fun `a summary that does not make the request smaller is not saved and the digest is used`() = runBlocking {
        val current = record("now", said("current"))
        // 8 tiny records are less than the 1,200 bytes a 30 % summary of a 4,000 window may be: a reply of that size would grow the request.
        val h = Harness(SavedChat(records = (1..8).map { record("p$it", said("q")) } + current), 4_000, out, AssistantLimits(summarySizePercent = 30, historyPercentSet = 40, replyPercentSet = 1)) { AgentReply("q".repeat(5_000)) }
        val result = h.engine.prepare(raw(current))
        assertEquals(1, h.seen.size); assertFalse(h.doc.summary!!.byModel); assertTrue(h.doc.summary!!.text.startsWith("Mechanical digest"))
        assertTrue(cost(result) <= 4_000)
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
        assertTrue(digest.toByteArray().size <= 1_800); assertFalse(digest.contains("SECRET-TYPED-TEXT")); assertTrue(digest.contains("Older lines of this digest were left out"))
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
        try { h.engine.prepare(raw(current)); fail("expected") } catch (e: ContextCannotFit) { assertTrue(e.message!!.contains("cannot fit")) }
    }

    @Test fun `after an overflow the request is shortened to at once, below the trigger too, with no model`() = runBlocking {
        val current = record("now", said("current"))
        val h = harness(manyAnswers(20) + current, window = 60_000) { error("the model must not be asked after an overflow") }
        val request = raw(current)
        val sent = h.engine.prepare(request)
        assertTrue(h.seen.isEmpty()); assertTrue(cost(sent) < 60_000 * 80L / 100)
        val shorter = h.engine.shortenAfterOverflow(request)
        assertTrue("shorter ${cost(shorter)} < ${cost(sent)}", cost(shorter) < cost(sent))
        assertTrue(h.seen.isEmpty()); assertFalse(h.doc.summary!!.byModel); assertEquals(listOf(Compaction.NO_MODEL), h.told)
    }

    @Test fun `an overflow after a summary inside the current turn keeps the newest exchanges once, not dropped twice`() = runBlocking {
        val blocks = (0 until 6).map { tool(it, 300) }
        val current = record("now", *blocks.toTypedArray())
        val covered = SavedSummary("Summary of the first three exchanges.", 0, 3)
        val h = Harness(SavedChat(records = listOf(current), summary = covered), 60_000, out) { error("no model") }
        val request = raw(current)
        // The kernel's request already shows only the exchanges after the summary; the resend starts from the RAW request, so they are shown once.
        val shorter = h.engine.shortenAfterOverflow(request)
        val kept = shorter.turns.last().exchanges
        assertTrue("the newest exchanges stay", kept.isNotEmpty()); assertEquals(request.turns.last().exchanges.last(), kept.last())
        assertTrue(kept.last().results.single().text.startsWith("out5 "))
    }

    @Test fun `the summary request never carries the owner's own text, and the trigger follows the limit`() = runBlocking {
        val current = record("now", said("current"))
        // 80 % trigger: this history is about 9.5k of 12k, under it; at 41 % the same history is over.
        val records = manyAnswers(14) + current
        val low = harness(records, AssistantLimits(summarySizePercent = 5, historyPercentSet = 40, replyPercentSet = 1)) { AgentReply("S") }
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
            // No model is asked after an overflow: the digest takes the old part and the request is sent once more at once.
            assertEquals(2, mains); assertEquals(0, summaries); assertNull(chat.state.value.problem)
            assertEquals("answer", chat.state.value.turns.last().answer); assertEquals(Compaction.NO_MODEL, chat.state.value.compaction)
            assertFalse(doc.summary!!.byModel); assertEquals(41, doc.records.size)
        }
        sendWith(OpenAiProviderException(ProviderFailure.HTTP, 413, contextTooLong = true), summaryFails = true) { mains, summaries, chat, doc ->
            assertEquals(2, mains); assertEquals(0, summaries); assertNull(chat.state.value.problem); assertEquals(Compaction.NO_MODEL, chat.state.value.compaction); assertFalse(doc.summary!!.byModel)
        }
    }

    @Test fun `final synthesis uses existing archive summary without restoring covered raw content`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = AssistantChatHistory(Memory(), Box(), scope, Dispatchers.Unconfined)
        val snapshot = AssistantProviderSnapshot(AssistantProviderSettings(model = "chat", contextTokens = 12_000, outputTokens = 512), "revision")
        val old = record("old question", said("covered raw secret"))
        history.save(SavedChat().bind(snapshot.settings).copy(records = listOf(old, record("recent question", said("recent answer"))), summary = SavedSummary("saved summary fact", 1, 0))); history.load()
        val requests = mutableListOf<AgentRequest>()
        val model = AgentProvider { request ->
            requests += request
            if (requests.size <= 3) AgentReply(calls = listOf(ToolCall("ghost${requests.size}", "ghost", buildJsonObject {}))) else AgentReply("answer")
        }
        val chat = AssistantConversation(scope, { AssistantAvailability(true) }, { snapshot }, { model }, {}, AgentModule(true),
            limits = { _, _ -> AgentLimits(contextTokens = 12_000, outputReserveTokens = 512, maxSteps = 1) }, io = Dispatchers.Unconfined,
            historyStore = history, prompts = TestPrompts.lazy, confirmations = TestConfirmations.off)
        try {
            chat.ready(); chat.edit("Continue"); chat.send(); chat.ready()
            assertEquals(4, requests.size)
            assertEquals(listOf(3, 2, 1, 0), requests.map { it.toolBudget?.rounds })
            assertTrue(requests.all { it.tools == requests.first().tools })
            val final = requests.last()
            assertTrue(final.finalSynthesis)
            val texts = final.turns.flatMap { t -> listOf(t.user) + t.exchanges.flatMap { e -> listOf(e.reply.text) + e.results.map { it.text } } }
            assertTrue("final texts: $texts", texts.any { it.contains("saved summary fact") })
            assertFalse(texts.any { it.contains("covered raw secret") })
            assertFalse(texts.any { it.contains("Tool budget:") })
            assertFalse(history.load()!!.records.toString().contains("Tool budget:"))
            assertEquals(listOf("ghost", "ghost", "ghost"), final.turns.last().exchanges.flatMap { it.results }.map { it.name })
        } finally { chat.close(); scope.cancel() }
    }

    @Test fun `final synthesis does not summarize or retry provider overflow`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = AssistantChatHistory(Memory(), Box(), scope, Dispatchers.Unconfined)
        val snapshot = AssistantProviderSnapshot(AssistantProviderSettings(model = "chat", contextTokens = 12_000, outputTokens = 512), "revision")
        history.save(SavedChat(records = manyAnswers(10)).bind(snapshot.settings)); history.load()
        var mainCalls = 0
        var summariesAfterTool = 0
        val model = AgentProvider { request ->
            if (request.instructions == TestPrompts.real.summary) {
                if (mainCalls > 0) summariesAfterTool++
                AgentReply("Summary of older exchanges")
            } else {
                mainCalls++
                if (mainCalls <= 3) AgentReply(calls = listOf(ToolCall("ghost$mainCalls", "ghost", buildJsonObject { put("blob", "x".repeat(200)) })))
                else {
                    assertTrue(request.finalSynthesis); assertEquals(0, request.toolBudget?.rounds)
                    throw OpenAiProviderException(ProviderFailure.HTTP, 400, contextTooLong = true)
                }
            }
        }
        val chat = AssistantConversation(scope, { AssistantAvailability(true) }, { snapshot }, { model }, {}, AgentModule(true),
            limits = { _, _ -> AgentLimits(contextTokens = 12_000, outputReserveTokens = 512, maxSteps = 1) }, io = Dispatchers.Unconfined,
            historyStore = history, prompts = TestPrompts.lazy, confirmations = TestConfirmations.off)
        try {
            chat.ready(); chat.edit("Continue"); chat.send(); chat.ready()
            assertEquals(4, mainCalls)
            assertEquals(0, summariesAfterTool)
            assertNotNull(chat.state.value.problem)
        } finally { chat.close(); scope.cancel() }
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

    @Test fun `a context-too-long error after a summary inside the current turn resends the newest exchange once, with its result`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = AssistantChatHistory(Memory(), Box(), scope, Dispatchers.Unconfined)
        val snapshot = AssistantProviderSnapshot(AssistantProviderSettings(model = "chat", contextTokens = 30_000, outputTokens = 512), "revision")
        history.save(SavedChat().bind(snapshot.settings)); history.load()
        var mains = 0; var summaries = 0; var armed = false; var overflowAt = 0; var afterSummary = 0
        val resent = mutableListOf<AgentRequest>()
        val model = AgentProvider { request ->
            if (request.instructions == TestPrompts.real.summary) { summaries++; armed = true; AgentReply("Summary S") }
            else {
                mains++
                if (armed && overflowAt == 0 && ++afterSummary == 2) { overflowAt = mains; throw OpenAiProviderException(ProviderFailure.HTTP, 400, contextTooLong = true) }
                if (overflowAt != 0) { resent += request; AgentReply("done") }
                else AgentReply(calls = listOf(ToolCall("g$mains", "ghost", buildJsonObject { put("blob", "x".repeat(4_000)) })))
            }
        }
        val chat = AssistantConversation(scope, { AssistantAvailability(true, limits = AssistantLimits(summarySizePercent = 40, historyPercentSet = 50, replyPercentSet = 5)) }, { snapshot }, { model }, {}, AgentModule(true), io = Dispatchers.Unconfined,
            historyStore = history, prompts = TestPrompts.lazy, confirmations = TestConfirmations.off)
        try {
            chat.ready(); chat.edit("Go"); chat.send(); chat.ready()
            assertNull(chat.state.value.problem); assertEquals("done", chat.state.value.turns.last().answer)
            assertEquals("the summary was asked for before the overflow only", 1, summaries); assertEquals(1, resent.size)
            // A summary covered the first exchanges of this turn; the digest of the overflow takes one more. The resend shows the newest exchange, with its result, exactly once.
            val shown = resent.single().turns.last().exchanges
            assertEquals(listOf("g${overflowAt - 1}"), shown.map { it.reply.calls.single().id })
            assertEquals("Unknown tool", shown.single().results.single().text)
            // The model's summary rides in the digest under its own untrusted label.
            assertTrue(resent.single().turns.first().exchanges.single().reply.text.endsWith("Earlier summary written by a model (untrusted):\nSummary S"))
        } finally { chat.close(); scope.cancel() }
    }

    @Test fun `the red does-not-fit text is only for that case, and the summary model's key problem keeps its own`() {
        assertEquals(ChatProblem.SUMMARY, preparationProblem(ContextCannotFit()))
        assertEquals(ChatProblem.SETUP, preparationProblem(IllegalStateException("anything else")))
        assertEquals(ChatProblem.SETUP, preparationProblem(IndexOutOfBoundsException()))
        assertEquals(ChatProblem.KEY, preparationProblem(ProviderSetupException(ProviderSetupProblem.INVALID_KEY)))
    }

    @Test fun `a current turn that cannot fit the window ends with the plain summary problem and keeps the history`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = AssistantChatHistory(Memory(), Box(), scope, Dispatchers.Unconfined)
        val snapshot = AssistantProviderSnapshot(AssistantProviderSettings(model = "chat", contextTokens = 2_000, outputTokens = 512), "revision")
        history.save(SavedChat(records = manyAnswers(3)).bind(snapshot.settings)); history.load()
        val chat = AssistantConversation(scope, { AssistantAvailability(true) }, { snapshot }, { AgentProvider { AgentReply("must not run") } }, {}, AgentModule(true), io = Dispatchers.Unconfined,
            historyStore = history, prompts = TestPrompts.lazy, confirmations = TestConfirmations.off)
        try {
            chat.ready(); chat.edit("Go"); chat.send(); chat.ready()
            assertEquals(ChatProblem.SUMMARY, chat.state.value.problem); assertEquals(4, history.load()!!.records.size)
        } finally { chat.close(); scope.cancel() }
    }

    @Test fun `a saved summary longer than the old 4000 characters reads back through the real history path`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val file = Memory(); val box = Box()
        val long = "s".repeat(50_000)
        val doc = SavedChat(records = manyAnswers(3), summary = SavedSummary(long, 1, 0))
        val first = AssistantChatHistory(file, box, scope, Dispatchers.Unconfined)
        first.save(doc)
        assertEquals(long, first.load()!!.summary!!.text)
        // A fresh store reads the file: that is `restored()`, which used to refuse it.
        assertEquals(long, AssistantChatHistory(file, box, scope, Dispatchers.Unconfined).load()!!.summary!!.text)
        // The bound is the biggest window times the biggest summary share, no more.
        assertEquals(400_000, MAX_SUMMARY_CHARS)
        SavedChat(records = manyAnswers(3), summary = SavedSummary("m".repeat(MAX_SUMMARY_CHARS), 1, 0)).restored()
        assertThrows(IllegalArgumentException::class.java) { SavedChat(records = manyAnswers(3), summary = SavedSummary("m".repeat(MAX_SUMMARY_CHARS + 1), 1, 0)).restored() }
        scope.cancel()
    }

    @Test fun `a digest keeps an earlier model summary under its own untrusted label, after the extracted lines, once`() = runBlocking {
        val current = record("now", said("current"))
        val earlier = SavedSummary("Owner's requests: keep Work not Home\nDone: upgrade on work-root", 0, 0)
        val records = manyAnswers(30) + current
        val h = Harness(SavedChat(records = records, summary = earlier), window, out) { throw OpenAiProviderException(ProviderFailure.HTTP, 500) }
        h.engine.prepare(raw(current))
        val text = h.doc.summary!!.text
        val lines = text.lines()
        val label = lines.indexOf("Earlier summary written by a model (untrusted):")
        assertTrue(label > 0); assertFalse(h.doc.summary!!.byModel)
        assertEquals(listOf("Owner's requests: keep Work not Home", "Done: upgrade on work-root"), lines.drop(label + 1))
        assertTrue(lines.take(label).any { it.startsWith("Owner: ") }); assertTrue(lines.first().contains("without a model"))
        assertTrue(lines.take(label).none { it.contains("keep Work not Home") })
        // Digesting again does not nest or repeat the label, and the model's lines never join the extracted ones.
        val again = Harness(SavedChat(records = records + record("more", said("m ".repeat(300))), summary = h.doc.summary), 3_000, out) { throw IllegalStateException() }
        again.engine.prepare(raw(again.doc.records.last()))
        val second = again.doc.summary!!.text.lines()
        assertEquals(1, second.count { it == "Earlier summary written by a model (untrusted):" })
        assertEquals(listOf("Owner's requests: keep Work not Home", "Done: upgrade on work-root"), second.drop(second.indexOf("Earlier summary written by a model (untrusted):") + 1))
    }
}
