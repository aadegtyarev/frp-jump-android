package ru.adsrv.frpjump.assistant.chat

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.ChatHistoryProblem

class ChatProjectionTest {
    private fun turn(user: String, status: SavedTurnState, answer: String = "", problem: String? = null, problemStatus: Int? = null) =
        SavedTurn(user, listOf(SavedExchange(SavedReply(answer))), status = status, problem = problem, problemStatus = problemStatus)
    private fun project(value: SavedChat, archive: Boolean, running: Boolean, previous: ChatState = ChatState()) =
        projectChat(value, value.views(), archive, running, previous)
    private val done = turn("q1", SavedTurnState.COMPLETE, "a1")

    @Test fun `empty document`() {
        assertEquals(ChatState(), project(SavedChat(), archive = false, running = false))
    }
    @Test fun `without an archive only completed turns are shown and a running one is current`() {
        val state = project(SavedChat(records = listOf(done, turn("q2", SavedTurnState.RUNNING)), draft = "d"), archive = false, running = true)
        assertEquals(listOf("q1"), state.turns.map { it.user }); assertEquals("q2", state.pendingText); assertEquals("d", state.draft)
        assertNull(state.problem)
    }
    @Test fun `without an archive a failed turn is current but not pending and not in turns`() {
        val state = project(SavedChat(records = listOf(done, turn("q2", SavedTurnState.FAILED, problem = "TIMEOUT", problemStatus = 504))), archive = false, running = false)
        assertEquals(listOf("q1"), state.turns.map { it.user }); assertNull(state.pendingText)
        assertEquals(ChatProblem.TIMEOUT, state.problem); assertEquals(504, state.problemStatus)
    }
    @Test fun `with an archive every turn is kept except the running one`() {
        val value = SavedChat(records = listOf(done, turn("q2", SavedTurnState.RUNNING)))
        val state = project(value, archive = true, running = true)
        assertEquals(listOf("q1"), state.turns.map { it.user }); assertEquals("q2", state.pendingText)
        val failed = project(SavedChat(records = listOf(done, turn("q2", SavedTurnState.FAILED, problem = "PROVIDER"))), archive = true, running = false)
        assertEquals(listOf("q1", "q2"), failed.turns.map { it.user }); assertEquals(ChatProblem.PROVIDER, failed.problem)
    }
    @Test fun `a running turn without an owner is not pending`() {
        val state = project(SavedChat(records = listOf(turn("q", SavedTurnState.RUNNING))), archive = true, running = false)
        assertNull(state.pendingText); assertTrue(state.turns.isEmpty())
    }
    @Test fun `an interrupted archive turn reports INTERRUPTED unless it has its own problem`() {
        assertEquals(ChatProblem.INTERRUPTED, project(SavedChat(records = listOf(turn("q", SavedTurnState.INTERRUPTED))), archive = true, running = false).problem)
        assertNull(project(SavedChat(records = listOf(turn("q", SavedTurnState.INTERRUPTED))), archive = false, running = false).problem)
        assertEquals(ChatProblem.KEY, project(SavedChat(records = listOf(turn("q", SavedTurnState.INTERRUPTED, problem = "KEY"))), archive = true, running = false).problem)
    }
    @Test fun `document flags are projected`() {
        val value = SavedChat(records = listOf(done), omitted = true, contextStart = 1, summary = SavedSummary("s", 1, 0))
        val state = project(value, archive = true, running = false)
        assertTrue(state.omitted); assertTrue(state.contextChanged); assertTrue(state.summarized)
        val plain = project(SavedChat(records = listOf(done)), archive = true, running = false)
        assertFalse(plain.omitted); assertFalse(plain.contextChanged); assertFalse(plain.summarized)
    }
    @Test fun `current turn details are projected and other state is kept`() {
        val running = SavedTurn("q", listOf(SavedExchange(SavedReply("x"))), listOf(SavedSource("T", "https://e.org/")), SavedTurnState.RUNNING)
        val previous = ChatState(busy = true, stopping = true, loading = true, historyProblem = ChatHistoryProblem.WRITE, toolProgress = ChatToolProgress.SUMMARY)
        val state = project(SavedChat(records = listOf(running)), archive = true, running = true, previous = previous)
        assertEquals(listOf("T"), state.currentSources.map { it.title })
        assertTrue(state.busy && state.stopping && state.loading); assertEquals(ChatHistoryProblem.WRITE, state.historyProblem)
        assertEquals(ChatToolProgress.SUMMARY, state.toolProgress)
    }
    @Test fun `current turn receipts tool failures and profile information are projected only while it is current`() {
        val search = SavedCall("c1", AssistantToolId.WEB_SEARCH.wire, buildJsonObject { put("query", "frp") })
        val info = SavedCall("c2", AssistantToolId.PROFILE_INFORMATION.wire, JsonObject(emptyMap()))
        val results = listOf(SavedResult("c1", search.name, "down", "FAILED"), SavedResult("c2", info.name, "{\"name\":\"p\"}", "SUCCESS"))
        val current = SavedTurn("q", listOf(SavedExchange(SavedReply(calls = listOf(search, info)), results)), status = SavedTurnState.RUNNING)
        val state = project(SavedChat(records = listOf(done, current)), archive = true, running = true)
        assertEquals(listOf("c1", "c2"), state.receipts.map { it.call.id }); assertEquals(listOf("FAILED", "SUCCESS"), state.receipts.map { it.result?.outcome?.name })
        assertTrue(state.toolFailed); assertEquals(listOf("frp"), state.toolFailures.map { it.query })
        assertEquals(listOf("p"), state.information.map { it.page?.get("name")?.jsonPrimitive?.content })
        val settled = project(SavedChat(records = listOf(done, current.copy(status = SavedTurnState.COMPLETE))), archive = true, running = false)
        assertTrue(settled.receipts.isEmpty()); assertFalse(settled.toolFailed); assertTrue(settled.toolFailures.isEmpty()); assertTrue(settled.information.isEmpty())
    }
}
