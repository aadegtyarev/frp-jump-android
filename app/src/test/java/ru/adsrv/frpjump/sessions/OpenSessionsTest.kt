package ru.adsrv.frpjump.sessions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.SessionOn
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.undo.PendingUndo

class OpenSessionsTest {
    private val ssh = TunnelRef("p1", "dev-a", 22)
    private val broker = TunnelRef("p1", "dev-a", 1883)
    private val terminals = MutableStateFlow(listOf<SessionOn>())
    private val mqtt = MutableStateFlow(listOf<SessionOn>())
    private val hidden = MutableStateFlow(setOf<String>())
    private val open = OpenSessions(terminals, { terminals.value }, mqtt, { mqtt.value }, hidden)

    @Test
    fun `both kinds are counted together, live ones only`() = runBlocking {
        terminals.value = listOf(SessionOn(ssh, true), SessionOn(ssh, false))
        mqtt.value = listOf(SessionOn(broker, true, SessionKind.MQTT))
        assertEquals(2, open.liveCount.first())
        assertEquals(terminals.value + mqtt.value, open.through.first())
        assertEquals(terminals.value + mqtt.value, open.now())
    }

    @Test
    fun `an MQTT session alone counts`() = runBlocking {
        mqtt.value = listOf(SessionOn(broker, true, SessionKind.MQTT))
        assertEquals(1, open.liveCount.first())
    }

    @Test
    fun `keeps running is said once, with both kinds counted, and not with none live`() {
        terminals.value = listOf(SessionOn(ssh, false))
        assertNull(open.leftRunning())
        terminals.value = listOf(SessionOn(ssh, true))
        mqtt.value = listOf(SessionOn(broker, true, SessionKind.MQTT))
        assertEquals(2, open.leftRunning())
        assertNull(open.leftRunning())
    }

    @Test
    fun `a session closed or on a tunnel disconnected with Undo on screen is not counted, and is again after Undo`() = runBlocking {
        val shell = SessionOn(ssh, true, SessionKind.TERMINAL, id = 1)
        val other = SessionOn(TunnelRef("p1", "dev-b", 22), true, SessionKind.TERMINAL, id = 2)
        val explorer = SessionOn(broker, true, SessionKind.MQTT, id = 1)
        terminals.value = listOf(shell, other)
        mqtt.value = listOf(explorer)
        // Terminal 1 closing: MQTT session 1 is another session (ids are per kind).
        hidden.value = setOf(PendingUndo.sessionKey(SessionKind.TERMINAL, 1))
        assertEquals(listOf(other, explorer), open.through.first())
        assertEquals(listOf(other, explorer), open.now())
        // dev-a's broker tunnel disconnecting: its explorer goes with it.
        hidden.value = setOf(PendingUndo.tunnelKey("p1", "dev-a", 1883))
        assertEquals(listOf(shell, other), open.now())
        assertEquals(2, open.liveCount.first())
        // Undo: all back, as they were.
        hidden.value = emptySet()
        assertEquals(listOf(shell, other, explorer), open.through.first())
    }
}
