package ru.adsrv.frpjump.ui.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.settings.RememberedGrant

class AssistantMarksTest {
    private val a = GrantTarget("p", "a")
    private val b = GrantTarget("p", "b")
    private val c = GrantTarget("p", "c")

    @Test fun `a connection has a mark exactly while a session grant or a remembered permission exists`() {
        val marks = assistantMarks(mapOf(a to 1, b to 2), mapOf(b to RememberedGrant(5L, 1), c to RememberedGrant(7L, 2)))
        assertEquals(setOf(a, b, c), marks.keys)
        assertTrue(assistantMarks(emptyMap(), emptyMap()).isEmpty())
    }

    @Test fun `each lifetime says its own tools and the remembered one its date`() {
        val marks = assistantMarks(mapOf(a to 1, b to 2), mapOf(b to RememberedGrant(5L, 1), c to RememberedGrant(7L, 2)))
        assertEquals(AssistantMark(session = true, sessionBoth = false, rememberedAt = null, rememberedBoth = false), marks[a])
        assertEquals(AssistantMark(session = true, sessionBoth = true, rememberedAt = 5L, rememberedBoth = false), marks[b])
        assertEquals(AssistantMark(session = false, sessionBoth = false, rememberedAt = 7L, rememberedBoth = true), marks[c])
        assertNull(marks[GrantTarget("p", "none")])
    }
}
