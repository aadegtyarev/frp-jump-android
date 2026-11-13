package ru.adsrv.frpjump.ops.mqtt

import kotlinx.coroutines.withTimeout
import ru.adsrv.frpjump.mqtt.MqttPath
import ru.adsrv.frpjump.mqtt.PublishAnswer
import ru.adsrv.frpjump.mqtt.TopicValue
import ru.adsrv.frpjump.mqtt.WbWriteOwner
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbControl

/** One open session whose answers the test gives by hand. */
class FakeWriteSession : MqttWriteSession {
    data class Sent(val topic: String, val text: String, val qos: Int, val retain: Boolean)
    val sent = mutableListOf<Sent>()
    val sentWb = mutableListOf<Pair<WbControl, String>>()
    val pending = mutableListOf<(PublishAnswer) -> Unit>()
    var owner: WbWriteOwner? = null
    var writable = true
    var history: List<TopicValue> = emptyList()

    override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) {
        sent += Sent(topic, payload.toString(Charsets.UTF_8), qos, retain); pending += done
    }
    override fun ownsWbWrites(owner: WbWriteOwner?) = owner != null && owner === this.owner
    override fun wbWritable(control: WbControl) = writable
    override fun history(path: MqttPath) = history
    override fun publishWb(owner: WbWriteOwner?, control: WbControl, payload: String, done: (PublishAnswer) -> Unit) {
        sentWb += control to payload; pending += done
    }
}

class FakeWriteHost(val session: FakeWriteSession?, private val target: MqttTarget = MqttTarget("p", "c")) : MqttWriteHost {
    override fun session(target: MqttTarget): MqttWriteSession? = session.takeIf { target == this.target }
}

/** A refusal answers at once; an operation that wrongly goes on to send would wait for an answer that never comes, so it fails here instead of hanging. */
suspend fun <T> refusedAtOnce(block: suspend () -> T): T = withTimeout(3_000) { block() }
