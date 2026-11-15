package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.ProfileIndex
import ru.adsrv.frpjump.profiles.SyntheticProfileInformation
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.SessionOn
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.tunnel.VisitorState
import ru.adsrv.frpjump.undo.PendingUndo
import ru.adsrv.frpjump.undo.TunnelDisconnects

/** The app's real saved-tunnel lookups over real profile owners (synthetic, never started) and a real Undo queue. */
class AppSavedTunnelsTest {
    private val ids = SavedTunnelIds("home", "ssh")
    private val ref = TunnelRef("home", "same", 22)

    private class World(f: SyntheticProfileInformation) {
        val up = MutableStateFlow(true)
        var grant = "g1"
        var sessions = emptyList<SessionOn>()
        val release = CompletableDeferred<Unit>()
        val pending = PendingUndo(timer = { _, _ -> {} }, run = { commit -> runBlocking { commit() } }, announce = { _, _ -> })
        val disconnects = TunnelDisconnects(pending, isUp = { up.value }) { release.await(); up.value = false }
        val tunnels = AppSavedTunnels(f.state, f.hidden, disconnects, { sessions }, { c -> c.customName ?: "${c.kind} ${c.port}" },
            tunnelsOf = { if (up.value) TunnelsState(tunnels = listOf(Tunnel("same", 22, grant, 40001, VisitorState(TunnelStatus.DIRECT)))) else TunnelsState() })
    }

    private fun <T> world(block: (SyntheticProfileInformation, World) -> T): T = SyntheticProfileInformation().use { f ->
        runBlocking { f.runtimes.getValue("home").connections.save(Connection("ssh", "same", 22, PortKind.SSH, customName = "pi ssh")) }
        block(f, World(f))
    }

    @Test fun `a saved tunnel is found with its names, and a hidden or unknown one is not`() = world { f, w ->
        val tunnel = w.tunnels.find(ids)!!
        assertEquals(listOf("home", "Home", "ssh", "pi ssh", "same", 22), listOf(tunnel.profileId, tunnel.profileName, tunnel.connectionId, tunnel.name, tunnel.device, tunnel.port))
        assertNull(w.tunnels.find(SavedTunnelIds("home", "other")))
        assertNull(w.tunnels.find(SavedTunnelIds("nobody", "ssh")))
        // Hidden by a pending Undo of the owner's (the connection was removed), or its profile gone from the index, or its profile removed.
        f.hidden.value = setOf(PendingUndo.connectionKey("home", "ssh"))
        assertNull(w.tunnels.find(ids))
        f.hidden.value = emptySet()
        assertTrue(w.tunnels.find(ids) != null)
        f.state.value = f.state.value.copy(index = ProfileIndex(f.profiles.filter { it.id != "home" }, "work"))
        assertNull(w.tunnels.find(ids))
    }

    @Test fun `a tunnel that is dark for the owner's Undo is not on, and is stopping`() = world { f, w ->
        val tunnel = w.tunnels.find(ids)!!
        assertTrue(w.tunnels.isOn(tunnel)); assertFalse(w.tunnels.isStopping(tunnel))
        f.hidden.value = setOf(PendingUndo.tunnelKey("home", "same", 22))
        assertFalse(w.tunnels.isOn(tunnel)); assertTrue(w.tunnels.isStopping(tunnel))
        f.hidden.value = emptySet()
        assertTrue(w.tunnels.isOn(tunnel))
        // A tunnel that is down is not on, and is not stopping either.
        w.up.value = false
        assertFalse(w.tunnels.isOn(tunnel)); assertFalse(w.tunnels.isStopping(tunnel))
    }

    @Test fun `the pin differs after a restart, an edit or a stop, and is equal otherwise`() = world { f, w ->
        val before = w.tunnels.pin(ids)
        assertEquals(before, w.tunnels.pin(ids))
        // Restarted: another grant of the same tunnel.
        w.grant = "g2"
        val restarted = w.tunnels.pin(ids)
        assertNotEquals(before, restarted)
        // Edited: another name.
        runBlocking { f.runtimes.getValue("home").connections.save(Connection("ssh", "same", 22, PortKind.SSH, customName = "renamed")) }
        val edited = w.tunnels.pin(ids)
        assertNotEquals(restarted, edited)
        // Stopping (dark): not the running tunnel any more.
        f.hidden.value = setOf(PendingUndo.tunnelKey("home", "same", 22))
        assertNotEquals(edited, w.tunnels.pin(ids))
        assertNull(w.tunnels.pin(SavedTunnelIds("home", "missing")))
    }

    @Test fun `the card counts the terminal and MQTT sessions of the tunnel and no others`() = world { _, w ->
        w.sessions = listOf(
            SessionOn(ref, true, SessionKind.TERMINAL, 1), SessionOn(ref, false, SessionKind.TERMINAL, 2), SessionOn(ref, true, SessionKind.MQTT, 3),
            SessionOn(TunnelRef("home", "same", 2222), true, SessionKind.TERMINAL, 4), SessionOn(TunnelRef("work", "same", 22), true, SessionKind.TERMINAL, 5),
        )
        assertEquals(3, w.tunnels.tabs(w.tunnels.find(ids)!!))
    }

    @Test fun `an at-once stop is going down until it is down, for the lookups and for a later start`() = world { _, w ->
        val tunnel = w.tunnels.find(ids)!!
        val stopped = CompletableDeferred<Unit>()
        runBlocking {
            val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { w.disconnects.disconnectAtOnce(ref); stopped.complete(Unit) }
            // The stop is under way: no Undo to show it by, yet it is stopping and not on.
            assertTrue(w.tunnels.isStopping(tunnel)); assertFalse(w.tunnels.isOn(tunnel)); assertTrue(w.disconnects.disconnecting(ref))
            // Whoever connects next waits for it to be down.
            val waiter = async(Dispatchers.Default) { w.disconnects.awaitDisconnected(ref); true }
            kotlinx.coroutines.delay(100)
            assertFalse(waiter.isCompleted)
            w.release.complete(Unit)
            job.await(); stopped.await()
            assertTrue(waiter.await())
        }
        assertFalse(w.tunnels.isStopping(tunnel)); assertFalse(w.disconnects.disconnecting(ref))
        assertFalse(w.tunnels.isOn(tunnel))
    }
}
