package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.flow.StateFlow
import ru.adsrv.frpjump.mqtt.MqttSession
import ru.adsrv.frpjump.mqtt.MqttSessions
import ru.adsrv.frpjump.mqtt.WbWriteOwner
import ru.adsrv.frpjump.ops.tunnel.SavedTunnel
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.sessions.sessionHidden
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.undo.PendingUndo

/** A snapshot of the exact saved target, tunnel and accepted MQTT client. */
internal class ConnectedMqtt(private val tunnels: AssistantTunnels, private val sessions: MqttSessions, private val hidden: StateFlow<Set<String>>,
    private val localPort: (SavedTunnel) -> Int?, private val hideCount: (String) -> Long = { 0L }) {
    class Pin(val ids: SavedTunnelIds, val target: SavedTunnel, val session: MqttSession, val client: WbWriteOwner, val fingerprint: Any, val hides: List<Long>)
    fun pin(ids: SavedTunnelIds): Pin? {
        val target = tunnels.find(ids)?.takeIf { it.kind == PortKind.MQTT } ?: return null
        val session = sessions.find(ids.profileId, ids.connectionId) ?: return null
        val client = session.wbWriteOwner ?: return null
        val fingerprint = tunnels.pin(ids) ?: return null
        return Pin(ids, target, session, client, fingerprint, hides(target, session)).takeIf(::valid)
    }
    private fun hides(target: SavedTunnel, session: MqttSession) = listOf(
        hideCount(PendingUndo.connectionKey(target.profileId, target.connectionId)),
        hideCount(PendingUndo.tunnelKey(target.profileId, target.device, target.port)),
        hideCount(PendingUndo.sessionKey(SessionKind.MQTT, session.id)))
    fun valid(pin: Pin): Boolean {
        val t = pin.target; val s = pin.session
        return hides(t, s) == pin.hides && tunnels.find(pin.ids) == t && tunnels.pin(pin.ids) == pin.fingerprint && tunnels.isOn(t) &&
            sessions.find(t.profileId, t.connectionId) === s && s.device == t.device && s.port == t.port && localPort(t) == s.localPort &&
            s.ownsWbWrites(pin.client) && !sessionHidden(hidden.value, SessionKind.MQTT, s.id, t.ref)
    }
}
