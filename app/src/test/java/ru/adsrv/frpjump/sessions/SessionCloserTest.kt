package ru.adsrv.frpjump.sessions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.undo.PendingUndo
import ru.adsrv.frpjump.undo.UndoKind

/** Closing a session with Undo: gone from the lists at once, ended only when the notice goes. */
class SessionCloserTest {
    private var timer: (() -> Unit)? = null
    private var counts: Map<UndoKind, Int>? = null
    private var undoOnNotice: (() -> Unit)? = null
    private val pending = PendingUndo(
        timer = { _, block ->
            timer = block
            val cancel: () -> Unit = { if (timer === block) timer = null }
            cancel
        },
        run = { commit -> runBlocking { commit() } },
        announce = { c, undo ->
            counts = c
            undoOnNotice = undo.takeIf { c != null }
        },
    )
    private val closedTerminals = mutableListOf<Int>()
    private val closedMqtt = mutableListOf<Int>()
    private val closer = SessionCloser(pending, closedTerminals::add, closedMqtt::add)
    private val tunnel = TunnelRef("p1", "dev-a", 22)

    @Test
    fun `a closed tab is hidden, its shell ends only when the notice goes`() {
        closer.terminal(3)
        assertTrue(sessionHidden(pending.hidden.value, SessionKind.TERMINAL, 3, tunnel))
        assertFalse(sessionHidden(pending.hidden.value, SessionKind.MQTT, 3, tunnel))
        assertEquals(mapOf(UndoKind.CLOSED to 1), counts)
        assertTrue(closedTerminals.isEmpty())
        timer!!.invoke()
        assertEquals(listOf(3), closedTerminals)
    }

    @Test
    fun `Undo brings the same session back, its own undo told, nothing ended`() {
        var selected = 0
        closer.terminal(3, undo = { selected = 3 })
        closer.mqtt(1)
        assertEquals(mapOf(UndoKind.CLOSED to 2), counts)
        undoOnNotice!!.invoke()
        assertEquals(3, selected)
        assertFalse(sessionHidden(pending.hidden.value, SessionKind.TERMINAL, 3, tunnel))
        pending.commitAll()
        assertTrue(closedTerminals.isEmpty())
        assertTrue(closedMqtt.isEmpty())
    }
}
