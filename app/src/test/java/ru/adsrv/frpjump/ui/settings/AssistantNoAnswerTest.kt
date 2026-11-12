package ru.adsrv.frpjump.ui.settings

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolResult
import ru.adsrv.frpjump.settings.ConfirmationTexts

/** A wait that ran out must read differently from a real decline, in the receipt and in the quiet line. */
class AssistantNoAnswerTest {
    private fun result(text: String, outcome: ToolOutcome = ToolOutcome.DECLINED) = ToolResult("c", "ssh_command", text, outcome)

    @Test fun `only the fixed no-answer text of a declined result is a no-answer`() {
        assertTrue(result(ConfirmationTexts.NO_ANSWER).isNoAnswer())
        assertFalse("a real decline", result(ConfirmationTexts.DECLINED).isNoAnswer())
        assertFalse(result("something else").isNoAnswer())
        assertFalse("same text but not a declined outcome", result(ConfirmationTexts.NO_ANSWER, ToolOutcome.FAILED).isNoAnswer())
        assertFalse(null.isNoAnswer())
    }
}
