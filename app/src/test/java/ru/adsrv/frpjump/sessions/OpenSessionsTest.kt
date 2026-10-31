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
import ru.adsrv.frpjump.tunnel.openThrough

class OpenSessionsTest {
    private val ssh = TunnelRef("p1", "dev-a", 22)
    private val broker = TunnelRef("p1", "dev-a", 1883)
    private val terminals = MutableStateFlow(listOf<SessionOn>())
    private val mqtt = MutableStateFlow(listOf<SessionOn>())
    private val open = OpenSessions(terminals, { terminals.value }, mqtt, { mqtt.value })

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
        assertEquals(1, openThrough(open.now(), emptyList(), null).mqtt)
    }

    @Test
    fun `disconnecting a tunnel counts what it carries of both kinds`() {
        terminals.value = listOf(SessionOn(ssh, true))
        mqtt.value = listOf(SessionOn(broker, true, SessionKind.MQTT))
        val all = openThrough(open.now(), emptyList(), null)
        assertEquals(1, all.terminals)
        assertEquals(1, all.mqtt)
        assertEquals(0, openThrough(open.now(), emptyList(), broker).terminals)
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
}
