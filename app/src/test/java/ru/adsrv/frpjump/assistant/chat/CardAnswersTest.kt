package ru.adsrv.frpjump.assistant.chat

import org.junit.Assert.*
import org.junit.Test

class CardAnswersTest {
    @Test fun `the plain buttons answer at once`() {
        assertEquals(ConfirmationDecision.ONCE, decisionFor(CardTap.ALLOW, false))
        assertEquals(ConfirmationDecision.SESSION, decisionFor(CardTap.SESSION, false))
        assertEquals(ConfirmationDecision.DENY, decisionFor(CardTap.DECLINE, false))
    }

    @Test fun `Always answers nothing until it is confirmed and then answers Always`() {
        assertNull("a plain tap must not turn the question off", decisionFor(CardTap.ALWAYS, false))
        assertEquals(ConfirmationDecision.ALWAYS, decisionFor(CardTap.ALWAYS, true))
    }

    @Test fun `no other tap becomes Always and none is a no-answer`() {
        for (tap in CardTap.entries) for (confirmed in listOf(false, true)) {
            val d = decisionFor(tap, confirmed)
            if (tap != CardTap.ALWAYS) assertNotEquals("$tap", ConfirmationDecision.ALWAYS, d)
            assertNotEquals(ConfirmationDecision.NO_ANSWER, d)
        }
    }
}
