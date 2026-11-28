package ru.adsrv.frpjump.assistant

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.assistant.chat.ChatAttachment
import ru.adsrv.frpjump.assistant.chat.ChatQuote

class AssistantQuoteDraftTest {
    @Test fun `bounded selection is valid context without modifying draft`() {
        val quote = ChatQuote.block("z".repeat(9000), "[truncated]")!!
        assertTrue(quote.length <= ChatQuote.LIMIT)
        assertTrue(ChatAttachment("terminal_selection", "Selected text", quote = quote).valid())
        assertFalse(ChatAttachment("terminal_selection", "Selected text", quote = "z".repeat(ChatQuote.LIMIT + 1)).valid())
    }
}
