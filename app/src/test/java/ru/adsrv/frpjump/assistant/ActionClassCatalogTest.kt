package ru.adsrv.frpjump.assistant

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.ops.ActionClass
import ru.adsrv.frpjump.ops.ActionClass.*
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.Effect.*

/** The catalog of tools and their action classes: the contract's table, row by row. A changed class, effect, `confirms` or `grantable` fails here. */
class ActionClassCatalogTest {
    private class Row(val tool: AssistantToolId, val actionClass: ActionClass, val effect: Effect, val confirms: Boolean, val grantable: Boolean)
    private val table = listOf(
        Row(AssistantToolId.WEB_SEARCH, PUBLIC_READ, READ_REMOTE, true, false),
        Row(AssistantToolId.READ_PAGE, PUBLIC_READ, READ_REMOTE, true, false),
        Row(AssistantToolId.INSPECT_IMAGE, PUBLIC_READ, READ_REMOTE, true, false),
        Row(AssistantToolId.PROFILE_INFORMATION, CONNECTION_READ, READ_REMOTE, true, false),
        Row(AssistantToolId.TERMINAL_TABS, CONNECTION_READ, READ_REMOTE, true, false),
        Row(AssistantToolId.TERMINAL_READ, CONNECTION_READ, READ_REMOTE, true, false),
        Row(AssistantToolId.SSH_COMMAND, CONNECTION_CHANGE, CHANGE_REMOTE, true, true),
        Row(AssistantToolId.TERMINAL_INPUT, CONNECTION_CHANGE, CHANGE_REMOTE, true, true),
        Row(AssistantToolId.TERMINAL_OPEN, CONNECTION_CHANGE, CHANGE_REMOTE, false, false),
        Row(AssistantToolId.TERMINAL_CLOSE, CONNECTION_CHANGE, CHANGE_REMOTE, true, false),
        Row(AssistantToolId.READ_SKILL, APP_READ, READ_LOCAL, true, false),
        Row(AssistantToolId.SKILLS_LIST, APP_READ, READ_LOCAL, true, false),
        Row(AssistantToolId.SKILL_SAVE, APP_CHANGE, CHANGE_LOCAL, true, false),
        Row(AssistantToolId.SKILL_DELETE, APP_CHANGE, CHANGE_LOCAL, true, false),
        Row(AssistantToolId.START_TUNNEL, APP_CHANGE, CHANGE_LOCAL, false, false),
        Row(AssistantToolId.STOP_TUNNEL, APP_CHANGE, CHANGE_LOCAL, true, false),
        Row(AssistantToolId.TERMINAL_RENAME, APP_CHANGE, CHANGE_LOCAL, false, false),
        Row(AssistantToolId.TERMINAL_SHOW, APP_CHANGE, CHANGE_LOCAL, false, false),
        Row(AssistantToolId.WAIT_FOR, APP_CHANGE, CHANGE_LOCAL, false, false),
    )

    @Test fun `every tool has the class effect confirms and grantable of its row, and the table lists all 19`() {
        assertEquals(19, AssistantToolId.entries.size); assertEquals(19, table.size)
        assertEquals("every catalog entry is in the table", AssistantToolId.entries.toSet(), table.map { it.tool }.toSet())
        for (row in table) {
            assertEquals("${row.tool} class", row.actionClass, row.tool.actionClass)
            assertEquals("${row.tool} effect", row.effect, row.tool.effect)
            assertEquals("${row.tool} confirms", row.confirms, row.tool.confirms)
            assertEquals("${row.tool} grantable", row.grantable, row.tool.grantable)
        }
        val counts = AssistantToolId.entries.groupingBy { it.actionClass }.eachCount()
        assertEquals(mapOf(PUBLIC_READ to 3, CONNECTION_READ to 3, CONNECTION_CHANGE to 4, APP_READ to 2, APP_CHANGE to 7), counts)
    }

    @Test fun `a tool asks exactly when its effect changes and it confirms - six tools - and two are grantable`() {
        val asking = AssistantToolId.entries.filter { it.effect.changes && it.confirms }
        assertEquals(setOf(AssistantToolId.SSH_COMMAND, AssistantToolId.TERMINAL_INPUT, AssistantToolId.TERMINAL_CLOSE, AssistantToolId.SKILL_SAVE,
            AssistantToolId.SKILL_DELETE, AssistantToolId.STOP_TUNNEL), asking.toSet())
        assertEquals(setOf(AssistantToolId.SSH_COMMAND, AssistantToolId.TERMINAL_INPUT), AssistantToolId.entries.filter { it.grantable }.toSet())
        // A read class holds exactly the tools whose effect reads; a changing class holds exactly the changing ones.
        val readClasses = setOf(PUBLIC_READ, CONNECTION_READ, APP_READ)
        for (t in AssistantToolId.entries) assertEquals("${t.name}: a read class iff it reads", !t.effect.changes, t.actionClass in readClasses)
        // The class never decides by itself: every quiet changing tool is a class member that does not ask.
        assertEquals(setOf(AssistantToolId.TERMINAL_OPEN, AssistantToolId.START_TUNNEL, AssistantToolId.TERMINAL_RENAME, AssistantToolId.TERMINAL_SHOW, AssistantToolId.WAIT_FOR),
            AssistantToolId.entries.filter { it.effect.changes && !it.confirms }.toSet())
    }

    @Test fun `only a class that can hold grants has a grantable tool and no tool that asks lacks a name on the card`() {
        assertEquals(listOf(CONNECTION_CHANGE), ActionClass.entries.filter { it.grantable })
        for (t in AssistantToolId.entries.filter { it.grantable }) assertTrue(t.actionClass.grantable)
        for (t in AssistantToolId.entries.filter { it.effect.changes && it.confirms }) assertTrue("${t.name} is a changing class", t.actionClass == CONNECTION_CHANGE || t.actionClass == APP_CHANGE)
    }
}
