package ru.adsrv.frpjump.ui.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.settings.RememberedGrant

class AssistantMarksTest {
    private val a = GrantTarget("p", "a")
    private val b = GrantTarget("p", "b")
    private val c = GrantTarget("p", "c")
    private val cmd = AssistantToolId.SSH_COMMAND
    private val typing = AssistantToolId.TERMINAL_INPUT

    @Test fun `a connection has a mark exactly while a session grant or a remembered permission exists`() {
        val marks = assistantMarks(mapOf(a to setOf(cmd), b to setOf(cmd, typing)), mapOf(b to RememberedGrant(5L, setOf(cmd)), c to RememberedGrant(7L, setOf(typing))))
        assertEquals(setOf(a, b, c), marks.keys)
        assertTrue(assistantMarks(emptyMap(), emptyMap()).isEmpty())
    }

    @Test fun `each lifetime carries its own tools and the remembered one its date`() {
        val marks = assistantMarks(mapOf(a to setOf(cmd), b to setOf(cmd, typing)), mapOf(b to RememberedGrant(5L, setOf(typing)), c to RememberedGrant(7L, setOf(cmd, typing))))
        assertEquals(AssistantMark(sessionTools = setOf(cmd), rememberedAt = null), marks[a])
        assertEquals(AssistantMark(sessionTools = setOf(cmd, typing), rememberedAt = 5L, rememberedTools = setOf(typing)), marks[b])
        assertEquals(AssistantMark(sessionTools = null, rememberedAt = 7L, rememberedTools = setOf(cmd, typing)), marks[c])
        assertNull(marks[GrantTarget("p", "none")])
    }

    @Test fun `the sheet names exactly the tools a grant covers`() {
        assertEquals(GrantCover.HTTP, coverOf(setOf(AssistantToolId.HTTP_POST)))
        assertEquals(R.string.connection_assistant_sheet_session_http, sessionLineRes(GrantCover.HTTP))
        assertEquals(R.string.connection_assistant_sheet_connection_http, connectionLineRes(GrantCover.HTTP))
        assertEquals(GrantCover.COMMANDS, coverOf(setOf(cmd)))
        assertEquals(GrantCover.INPUT, coverOf(setOf(typing)))
        assertEquals(GrantCover.BOTH, coverOf(setOf(cmd, typing)))
        assertEquals(R.string.connection_assistant_sheet_session, sessionLineRes(GrantCover.COMMANDS))
        assertEquals(R.string.connection_assistant_sheet_session_input, sessionLineRes(GrantCover.INPUT))
        assertEquals(R.string.connection_assistant_sheet_session_both, sessionLineRes(GrantCover.BOTH))
        assertEquals(R.string.connection_assistant_sheet_connection, connectionLineRes(GrantCover.COMMANDS))
        assertEquals(R.string.connection_assistant_sheet_connection_input, connectionLineRes(GrantCover.INPUT))
        assertEquals(R.string.connection_assistant_sheet_connection_both, connectionLineRes(GrantCover.BOTH))
    }
}
