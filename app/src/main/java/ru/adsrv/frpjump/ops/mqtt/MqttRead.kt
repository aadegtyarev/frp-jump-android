package ru.adsrv.frpjump.ops.mqtt

import ru.adsrv.frpjump.mqtt.MqttPath
import ru.adsrv.frpjump.mqtt.TopicValue
import ru.adsrv.frpjump.ops.*
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds

data class MqttReadInput(val ids: SavedTunnelIds, val filter: String)
data class MqttReadSnapshot(val values: List<Pair<MqttPath, TopicValue>>, val ingressDropped: Long, val cacheDropped: Long)

/** Reads only the connected explorer's existing cache. */
class MqttRead(private val read: (MqttReadInput) -> OpResult<MqttReadSnapshot>) : Operation<MqttReadInput, MqttReadSnapshot> {
    override val name = "mqtt_read"
    override val effect = Effect.READ_REMOTE
    override val actionClass = ActionClass.CONNECTION_READ
    override suspend fun invoke(input: MqttReadInput) = read(input)
}
