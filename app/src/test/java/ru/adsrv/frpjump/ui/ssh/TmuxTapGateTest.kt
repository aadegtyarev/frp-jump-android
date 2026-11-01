package ru.adsrv.frpjump.ui.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ssh.TapAnswer

class TmuxTapGateTest {
    private var now = 0L
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val gate = TmuxTapGate({ now }) { ms, task -> timers += ms to task }
    private val session = Any()
    private var screen = "one"
    private val actions = mutableListOf<TapAction>()
    private val questions = mutableListOf<(TapAnswer) -> Unit>()

    private fun tap(at: String = screen) = gate.tap(session, at, { questions += it }, { screen }, { actions += it })

    @Test
    fun `a shell on the tapped screen gets the arrows`() {
        assertTrue(tap())
        questions.single()(TapAnswer.SHELL)
        assertEquals(listOf(TapAction.ARROWS), actions)
    }

    @Test
    fun `a screen that changed while tmux answered gets no arrows`() {
        tap()
        screen = "two"
        questions.single()(TapAnswer.SHELL)
        assertEquals(listOf(TapAction.NOTHING), actions)
    }

    @Test
    fun `anything else answered in time gets the click`() {
        tap()
        questions.single()(TapAnswer.NOT_SHELL)
        assertEquals(listOf(TapAction.CLICK), actions)
    }

    @Test
    fun `no answer, a failed or a late one, does nothing - no late click`() {
        tap()
        questions.single()(TapAnswer.NONE)
        assertEquals(listOf(TapAction.NOTHING), actions)
        // And it is not kept: the next tap asks again.
        tap()
        assertEquals(2, questions.size)
    }

    @Test
    fun `a tap while tmux has not answered is dropped`() {
        assertTrue(tap())
        assertFalse(tap())
        assertEquals(1, questions.size)
        assertTrue(actions.isEmpty())
        questions[0](TapAnswer.SHELL)
        assertTrue(tap())
    }

    @Test
    fun `an answer stands for the same screen for a short while`() {
        tap()
        questions.single()(TapAnswer.SHELL)
        now += TmuxTapGate.CACHE_MS
        tap()
        assertEquals(1, questions.size)
        assertEquals(listOf(TapAction.ARROWS, TapAction.ARROWS), actions)
        // Later, or on another screen, tmux is asked again.
        now += 1
        tap()
        screen = "three"
        tap()
        assertEquals(2, questions.size)
    }

    @Test
    fun `a question still open after the answer limit counts as no answer, its late answer is ignored`() {
        tap()
        val (delay, timeout) = timers.single()
        assertEquals(TmuxTapGate.PENDING_MAX_MS, delay)
        timeout()
        assertEquals(listOf(TapAction.NOTHING), actions)
        // Taps go through again; the old answer does nothing, not even a click.
        assertTrue(tap())
        questions[0](TapAnswer.NOT_SHELL)
        assertEquals(listOf(TapAction.NOTHING), actions)
        questions[1](TapAnswer.SHELL)
        assertEquals(listOf(TapAction.NOTHING, TapAction.ARROWS), actions)
    }

    @Test
    fun `the timeout after an answer does nothing`() {
        tap()
        questions.single()(TapAnswer.SHELL)
        timers.single().second()
        assertEquals(listOf(TapAction.ARROWS), actions)
    }

    @Test
    fun `sent arrows drop the kept answer`() {
        tap()
        questions.single()(TapAnswer.SHELL)
        gate.forget()
        tap()
        assertEquals(2, questions.size)
    }
}
