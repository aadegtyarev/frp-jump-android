package ru.adsrv.frpjump.undo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.connectionRows
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.shownTunnelCounts
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.tunnel.VisitorState

/** Disconnect with Undo: dark at once everywhere it is counted, stopped only when the notice goes. */
class TunnelDisconnectsTest {
    private var timer: (() -> Unit)? = null
    private var counts: Map<UndoKind, Int>? = null
    private var undoOnNotice: (() -> Unit)? = null
    private val stopped = mutableListOf<TunnelRef>()

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
    private val disconnects = TunnelDisconnects(pending) { stopped += it }

    private val ssh = TunnelRef("p1", "dev-a", 22)
    private val web = TunnelRef("p1", "dev-a", 80)
    private val other = TunnelRef("p2", "dev-b", 22)

    private fun tunnel(t: TunnelRef) = Tunnel(t.device, t.port, "g-${t.device}-${t.port}", 40000 + t.port, VisitorState(TunnelStatus.DIRECT))

    /** Every profile's tunnels as their managers list them: still running, whatever the screens show. */
    private val running = mapOf(
        "p1" to TunnelsState(tunnels = listOf(tunnel(ssh), tunnel(web))),
        "p2" to TunnelsState(tunnels = listOf(tunnel(other))),
    )
    private val all = listOf(ssh, web, other)

    private fun expire() = checkNotNull(timer) { "no timer running" }.invoke()

    private fun dark() = PendingUndo.darkTunnels(pending.hidden.value)

    @Test
    fun `a disconnect shows the tunnel down at once and stops nothing yet`() {
        disconnects.disconnect(listOf(ssh))
        assertEquals(mapOf(UndoKind.DISCONNECTED to 1), counts)
        assertTrue(stopped.isEmpty())
        // The row of its connection shows it down; the other tunnel of the device stays up.
        val p1 = running.getValue("p1").withoutDark(PendingUndo.darkTunnels(pending.hidden.value, "p1"))
        val rows = connectionRows("p1", listOf(Connection("c1", "dev-a", 22, PortKind.SSH), Connection("c2", "dev-a", 80, PortKind.WEB)), p1)
        assertEquals(listOf(false, true), rows.map { it.active })
        // The chip and the notification count one less.
        assertEquals(mapOf("p1" to 1, "p2" to 1), shownTunnelCounts(running, dark()))
    }

    @Test
    fun `Undo brings it back as it was and nothing is stopped`() {
        disconnects.disconnect(listOf(ssh))
        undoOnNotice!!.invoke()
        assertEquals(emptySet<String>(), pending.hidden.value)
        assertEquals(mapOf("p1" to 2, "p2" to 1), shownTunnelCounts(running, dark()))
        assertNull(counts)
        assertNull(timer)
        pending.commitAll()
        assertTrue(stopped.isEmpty())
    }

    @Test
    fun `when the notice goes it is stopped and shown no more as waiting`() {
        disconnects.disconnect(listOf(ssh))
        expire()
        assertEquals(listOf(ssh), stopped)
        assertEquals(emptySet<String>(), pending.hidden.value)
    }

    @Test
    fun `disconnect all of two profiles shares one notice counting every tunnel`() {
        disconnects.disconnect(disconnects.shown(all))
        assertEquals(mapOf(UndoKind.DISCONNECTED to 3), counts)
        assertEquals(emptyMap<String, Int>(), shownTunnelCounts(running, dark()))
        assertEquals(setOf("dev-a:22", "dev-a:80"), PendingUndo.darkTunnels(pending.hidden.value, "p1"))
        assertEquals(setOf("dev-b:22"), PendingUndo.darkTunnels(pending.hidden.value, "p2"))
        // Nothing more to disconnect now: a second tap adds nothing.
        assertTrue(disconnects.shown(all).isEmpty())
        expire()
        assertEquals(all, stopped)
    }

    @Test
    fun `disconnect all after one disconnect counts on, one notice, and Undo brings all back`() {
        disconnects.disconnect(listOf(web))
        disconnects.disconnect(disconnects.shown(all))
        assertEquals(mapOf(UndoKind.DISCONNECTED to 3), counts)
        undoOnNotice!!.invoke()
        assertEquals(mapOf("p1" to 2, "p2" to 1), shownTunnelCounts(running, dark()))
        assertTrue(stopped.isEmpty())
    }

    @Test
    fun `a disconnect and a delete in a row share the notice, each kind counted`() {
        disconnects.disconnect(listOf(other))
        pending.enqueue(PendingUndo.connectionKey("p1", "c1"), commit = {})
        assertEquals(mapOf(UndoKind.DISCONNECTED to 1, UndoKind.DELETED to 1), counts)
        expire()
        assertEquals(listOf(other), stopped)
    }

    @Test
    fun `using a dark tunnel again keeps it up, the others still go`() {
        disconnects.disconnect(listOf(ssh, other))
        assertTrue(disconnects.revive(ssh))
        assertEquals(mapOf(UndoKind.DISCONNECTED to 1), counts)
        assertEquals(mapOf("p1" to 2), shownTunnelCounts(running, dark()))
        // Up and not waiting: nothing to take back.
        assertFalse(disconnects.revive(web))
        expire()
        assertEquals(listOf(other), stopped)
    }
}
