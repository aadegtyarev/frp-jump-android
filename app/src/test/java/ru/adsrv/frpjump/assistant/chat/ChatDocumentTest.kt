package ru.adsrv.frpjump.assistant.chat

import org.junit.Assert.*
import org.junit.Test

class ChatDocumentTest {
    private fun turn(user: String, status: SavedTurnState = SavedTurnState.COMPLETE) = SavedTurn(user, status = status)
    private fun chat(vararg turns: SavedTurn, draft: String = "") = SavedChat(records = turns.toList(), draft = draft)

    @Test fun `withLast changes only the last turn`() {
        val next = chat(turn("a"), turn("b", SavedTurnState.RUNNING)).withLast(true) { it.copy(user = "c") }
        assertEquals(listOf("a", "c"), next.records.map { it.user })
    }
    @Test fun `withTurn trims without an archive and keeps everything with one`() {
        val full = SavedChat(records = List(ChatLimits.MAX_TURNS) { turn("t$it") })
        val trimmed = full.withTurn("new", false)
        assertEquals(ChatLimits.MAX_TURNS, trimmed.records.size); assertTrue(trimmed.omitted); assertEquals("new", trimmed.records.last().user)
        val archived = full.withTurn("new", true)
        assertEquals(ChatLimits.MAX_TURNS + 1, archived.records.size); assertFalse(archived.omitted)
        assertEquals(SavedTurnState.RUNNING, archived.records.last().status)
    }
    @Test fun `completedOnly drops unfinished turns`() {
        val next = chat(turn("a"), turn("b", SavedTurnState.FAILED), turn("c", SavedTurnState.INTERRUPTED), turn("d", SavedTurnState.RUNNING)).completedOnly()
        assertEquals(listOf("a"), next.records.map { it.user })
    }
    @Test fun `interrupted affects only a running last turn`() {
        assertEquals(SavedTurnState.INTERRUPTED, chat(turn("a"), turn("b", SavedTurnState.RUNNING)).interrupted().records.last().status)
        assertEquals(SavedTurnState.RUNNING, chat(turn("a", SavedTurnState.RUNNING), turn("b")).interrupted().records.first().status)
        val untouched = chat(turn("a", SavedTurnState.FAILED))
        assertSame(untouched, untouched.interrupted())
        val empty = SavedChat()
        assertSame(empty, empty.interrupted())
    }
    @Test fun `finished success completes the turn and clears the draft`() {
        val next = chat(turn("a", SavedTurnState.RUNNING), draft = "d").finished(null, null, true)
        assertEquals(SavedTurnState.COMPLETE, next.records.last().status); assertNull(next.records.last().problem); assertEquals("", next.draft)
    }
    @Test fun `finished failure records problem and status and keeps the draft`() {
        val next = chat(turn("a", SavedTurnState.RUNNING), draft = "d").finished(ChatProblem.TIMEOUT, 504, true)
        val last = next.records.last()
        assertEquals(SavedTurnState.FAILED, last.status); assertEquals("TIMEOUT", last.problem); assertEquals(504, last.problemStatus); assertEquals("d", next.draft)
    }
    @Test fun `summary and omitted markers`() {
        assertEquals(SavedSummary("s", 0, 0), SavedChat().withSummary(SavedSummary("s", 0, 0)).summary)
        assertTrue(SavedChat().withOmitted().omitted)
    }
    @Test fun `views follow records`() {
        assertEquals(listOf("a", "b"), chat(turn("a"), turn("b")).views().map { it.user })
    }
    @Test fun `withLast and finished trim without an archive and keep everything with one`() {
        val over = SavedChat(records = List(ChatLimits.MAX_TURNS) { turn("t$it") } + turn("run", SavedTurnState.RUNNING))
        val edited = over.withLast(false) { it.copy(user = "x") }
        assertEquals(ChatLimits.MAX_TURNS, edited.records.size); assertTrue(edited.omitted); assertEquals("x", edited.records.last().user)
        val kept = over.withLast(true) { it.copy(user = "x") }
        assertEquals(ChatLimits.MAX_TURNS + 1, kept.records.size); assertFalse(kept.omitted)
        val done = over.finished(null, null, false)
        assertEquals(ChatLimits.MAX_TURNS, done.records.size); assertTrue(done.omitted); assertEquals(SavedTurnState.COMPLETE, done.records.last().status)
        val archived = over.finished(null, null, true)
        assertEquals(ChatLimits.MAX_TURNS + 1, archived.records.size); assertFalse(archived.omitted)
    }
}
