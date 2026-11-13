package ru.adsrv.frpjump.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Р32 part B: which notification the assistant shows, as a decision; no Android involved. */
class AssistantNoticeTest {
    private fun notice(activity: ChatActivity, shown: Boolean = false, end: ChatEnd = ChatEnd.NONE, permission: Boolean = true) =
        assistantNotice(activity, shown, end, permission)

    @Test fun `off screen a running attempt is the ongoing working notice and a card wait the ongoing waiting one`() {
        assertEquals(AssistantNotice.WORKING, notice(ChatActivity.WORKING))
        assertEquals(AssistantNotice.WAITING, notice(ChatActivity.WAITING))
        assertTrue(AssistantNotice.WORKING.ongoing); assertTrue(AssistantNotice.WAITING.ongoing)
    }

    @Test fun `with the chat shown there is none, whatever happens`() {
        for (a in ChatActivity.entries) for (e in ChatEnd.entries) assertNull("$a $e", notice(a, shown = true, end = e))
    }

    @Test fun `the end off screen gives each final text and it is not ongoing`() {
        assertEquals(AssistantNotice.ANSWERED, notice(ChatActivity.IDLE, end = ChatEnd.COMPLETED))
        assertEquals(AssistantNotice.FAILED, notice(ChatActivity.IDLE, end = ChatEnd.FAILED))
        assertEquals(AssistantNotice.UNANSWERED, notice(ChatActivity.IDLE, end = ChatEnd.UNANSWERED))
        for (n in listOf(AssistantNotice.ANSWERED, AssistantNotice.FAILED, AssistantNotice.UNANSWERED)) assertFalse(n.ongoing)
    }

    @Test fun `the owner's Stop or a disable leaves no end and so no final notification`() {
        assertNull(notice(ChatActivity.IDLE, end = ChatEnd.NONE))
    }

    @Test fun `without the permission nothing is posted`() {
        for (a in ChatActivity.entries) for (e in ChatEnd.entries) assertNull("$a $e", notice(a, end = e, permission = false))
    }

    @Test fun `an end that happened with the chat shown is not announced after leaving it`() {
        val t = AssistantNoticeTracker()
        assertNull(t.update(ChatActivity.WORKING, true, ChatEnd.NONE, true))
        assertNull(t.update(ChatActivity.IDLE, true, ChatEnd.COMPLETED, true))
        assertNull("the owner leaves the chat afterwards", t.update(ChatActivity.IDLE, false, ChatEnd.COMPLETED, true))
    }

    @Test fun `an end that happened off screen stays until the chat is shown, then goes for good`() {
        val t = AssistantNoticeTracker()
        assertEquals(AssistantNotice.WORKING, t.update(ChatActivity.WORKING, false, ChatEnd.NONE, true))
        assertEquals(AssistantNotice.ANSWERED, t.update(ChatActivity.IDLE, false, ChatEnd.COMPLETED, true))
        assertNull(t.update(ChatActivity.IDLE, true, ChatEnd.NONE, true))
        assertNull(t.update(ChatActivity.IDLE, false, ChatEnd.NONE, true))
    }

    @Test fun `working to waiting to a final text follows the same notification`() {
        val t = AssistantNoticeTracker()
        assertEquals(AssistantNotice.WORKING, t.update(ChatActivity.WORKING, false, ChatEnd.NONE, true))
        assertEquals(AssistantNotice.WAITING, t.update(ChatActivity.WAITING, false, ChatEnd.NONE, true))
        assertEquals(AssistantNotice.UNANSWERED, t.update(ChatActivity.IDLE, false, ChatEnd.UNANSWERED, true))
    }

    @Test fun `a Stop while off screen cancels with no final`() {
        val t = AssistantNoticeTracker()
        assertEquals(AssistantNotice.WAITING, t.update(ChatActivity.WAITING, false, ChatEnd.NONE, true))
        assertNull(t.update(ChatActivity.IDLE, false, ChatEnd.NONE, true))
    }

    @Test fun `the five notices have five distinct fixed titles and the notifier adds no content`() {
        assertEquals(5, AssistantNotice.entries.map { it.titleRes }.toSet().size)
        val source = File("src/main/java/ru/adsrv/frpjump/assistant/AssistantNotifier.kt").readText()
        for (forbidden in listOf("setContentText", "setStyle", "setSubText", "setTicker", "BigText", "RemoteInput"))
            assertFalse("no $forbidden", source.contains(forbidden))
        assertTrue(source.contains("setContentTitle(Texts.get(notice.titleRes))"))
        assertTrue(source.contains("VISIBILITY_PUBLIC"))
    }
}
