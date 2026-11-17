package ru.adsrv.frpjump.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.AssistantToolId.SSH_COMMAND
import ru.adsrv.frpjump.assistant.AssistantToolId.TERMINAL_INPUT

/** Which of the three For session hints a card carries: what it covers is what it says. */
class SessionHintTest {
    @Test fun `commands only, typing only and both each get their own hint`() {
        assertEquals(R.string.confirm_session_hint, sessionHintRes(setOf(SSH_COMMAND), typing = false))
        assertEquals(R.string.confirm_input_session_hint, sessionHintRes(setOf(TERMINAL_INPUT), typing = true))
        assertEquals(R.string.confirm_session_hint_both, sessionHintRes(setOf(SSH_COMMAND, TERMINAL_INPUT), typing = false))
        assertEquals(R.string.confirm_session_hint_both, sessionHintRes(setOf(SSH_COMMAND, TERMINAL_INPUT), typing = true))
    }

    @Test fun `what the card says follows what the grant covers, not the card's own kind`() {
        assertEquals("a typing card whose grant is commands only", R.string.confirm_session_hint, sessionHintRes(setOf(SSH_COMMAND), typing = true))
        assertEquals("a command card whose grant is typing only", R.string.confirm_input_session_hint, sessionHintRes(setOf(TERMINAL_INPUT), typing = false))
    }

    @Test fun `with nothing known the card's own kind decides`() {
        assertEquals(R.string.confirm_session_hint, sessionHintRes(emptySet(), typing = false))
        assertEquals(R.string.confirm_input_session_hint, sessionHintRes(emptySet(), typing = true))
    }
}
