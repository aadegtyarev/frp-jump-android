package ru.adsrv.frpjump.ops.mqtt

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.adsrv.frpjump.mqtt.MqttPath
import ru.adsrv.frpjump.mqtt.PublishAnswer
import ru.adsrv.frpjump.mqtt.PublishFailure
import ru.adsrv.frpjump.mqtt.PublishRequest
import ru.adsrv.frpjump.mqtt.TopicValue
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbControl
import ru.adsrv.frpjump.mqtt.WbWriteOwner
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.MqttFailure
import ru.adsrv.frpjump.ops.MqttRefusal
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.Operation
import ru.adsrv.frpjump.ops.RemoteOutcome

/** The MQTT connection an operation writes through: a saved connection of a profile. Not a `GrantTarget`: these operations are UI only. */
data class MqttTarget(val profile: String, val connectionId: String)

/** How a message that left the phone ended: the broker confirmed it, or nothing more is known (QoS 0, MQTT 3.1.1). */
enum class MqttDelivery { ACCEPTED, UNCONFIRMED }

/** What the MQTT write operations need of one open session: its own members, unchanged. */
interface MqttWriteSession {
    /** Answers once, on the session's thread. */
    fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit)
    fun ownsWbWrites(owner: WbWriteOwner?): Boolean
    fun wbWritable(control: WbControl): Boolean
    fun history(path: MqttPath): List<TopicValue>
    /** The owner and writability are checked again in the same session action as the send; answers once. */
    fun publishWb(owner: WbWriteOwner?, control: WbControl, payload: String, done: (PublishAnswer) -> Unit)
}

/** What the MQTT write operations need of the app: the open session of [target]; null when there is none. */
interface MqttWriteHost {
    fun session(target: MqttTarget): MqttWriteSession?
}

/** Maps every [PublishAnswer] case to a result. A refusal means nothing was sent; the other failures happened after the send. */
fun PublishAnswer.toOpResult(): OpResult<MqttDelivery> = when (this) {
    PublishAnswer.Accepted -> OpResult.Done(MqttDelivery.ACCEPTED)
    PublishAnswer.Unconfirmed -> OpResult.Done(MqttDelivery.UNCONFIRMED)
    is PublishAnswer.Refused -> OpResult.Failed(MqttFailure.BrokerRefused(code, reasonString), RemoteOutcome.KNOWN)
    is PublishAnswer.Failed -> when (failure) {
        PublishFailure.NOT_CONNECTED -> OpResult.Refused(MqttRefusal.NotConnected)
        PublishFailure.CONTROL_NOT_WRITABLE -> OpResult.Refused(MqttRefusal.NotWritable)
        PublishFailure.LOST -> OpResult.Failed(MqttFailure.ConnectionLost, RemoteOutcome.UNKNOWN)
        PublishFailure.TIMEOUT -> OpResult.Failed(MqttFailure.NoAnswer, RemoteOutcome.UNKNOWN)
        PublishFailure.OTHER -> OpResult.Failed(MqttFailure.Other(detail), RemoteOutcome.UNKNOWN)
    }
}

/** Starts [send] and suspends for its one answer. A caller that stops waiting does not take a started send back. */
internal suspend fun awaitAnswer(send: ((PublishAnswer) -> Unit) -> Unit): PublishAnswer =
    suspendCancellableCoroutine { cont -> send { answer -> cont.resume(answer) } }

/** The publish sheet's Send: [request] to [target]. The topic was checked by the sheet; WB read-only is not enforced here. */
data class MqttPublishInput(val target: MqttTarget, val request: PublishRequest)

/**
 * Publishes through the open connection of the target. The session answers once on its own thread (NOT_CONNECTED unless open, a
 * timeout after [ru.adsrv.frpjump.mqtt.PUBLISH_TIMEOUT_MS]); this maps that answer with [toOpResult] and loses no case. A send that left
 * the phone cannot be taken back, so cancelling the caller only stops the wait. What a sent message is remembered for stays with the
 * caller (the ViewModel's scope), not here. UI only.
 */
class MqttPublish(private val host: MqttWriteHost) : Operation<MqttPublishInput, MqttDelivery> {
    override val name = "mqtt_publish"
    override val effect = Effect.CHANGE_REMOTE

    override suspend fun invoke(input: MqttPublishInput): OpResult<MqttDelivery> {
        val session = host.session(input.target) ?: return OpResult.Refused(MqttRefusal.NotConnected)
        val r = input.request
        return awaitAnswer { done -> session.publish(r.topic, r.payload.toByteArray(Charsets.UTF_8), r.qos, r.retain, done) }.toOpResult()
    }
}
