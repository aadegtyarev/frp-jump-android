package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.chat.MqttPublishCard
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.mqtt.*
import ru.adsrv.frpjump.ops.*
import ru.adsrv.frpjump.ops.mqtt.*
import ru.adsrv.frpjump.ops.tunnel.SavedTunnel
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.sessions.sessionHidden
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.tunnel.SessionKind

/** Captures the authorizing basis before enqueue and checks it again on the scheduler. */
internal class ChangeDispatchAuthority(val prepare: (AssistantToolId, GrantTarget) -> (() -> Boolean)?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ChangeDispatchAuthority>
}

/** Resolves only the owner's existing saved connection and connected explorer. */
class AssistantMqttPublish(private val tunnels: AssistantTunnels, private val sessions: MqttSessions, private val hidden: StateFlow<Set<String>>,
    private val loginRevision: () -> Long = { 0L }, private val hideCount: (String) -> Long = { 0L }, private val localPort: (SavedTunnel) -> Int?) {
    private val connected = ConnectedMqtt(tunnels, sessions, hidden, localPort, hideCount)
    internal class Pin(val target: ConnectedMqtt.Pin, val loginRevision: Long)
    internal fun pin(ids: SavedTunnelIds): Pin? = connected.pin(ids)?.let { Pin(it, loginRevision()) }
    internal suspend fun publish(captured: Pin, request: PublishRequest): OpResult<MqttDelivery> {
        val pin = captured.target
        val context = currentCoroutineContext()
        val job = context[Job]
        val admission = context[SideEffectAdmission]
        val authority = context[ChangeDispatchAuthority]?.prepare(AssistantToolId.MQTT_PUBLISH, GrantTarget(pin.ids.profileId, pin.ids.connectionId))
            ?: return OpResult.Refused(MqttWriteRefusal.NotConnected())
        val host = MqttPublishHost { target ->
            if (target.profile != pin.ids.profileId || target.connectionId != pin.ids.connectionId || !connected.valid(pin) || loginRevision() != captured.loginRevision) null
            else MqttPublishSession { topic, payload, qos, retain, done ->
                pin.session.publishGuarded(pin.client, {
                    runCatching { job?.isActive == true && admission?.check("mqtt-dispatch") == true && job.isActive && authority() && loginRevision() == captured.loginRevision && job.isActive && connected.valid(pin) }.getOrDefault(false)
                }, topic, payload, qos, retain, done)
            }
        }
        return MqttPublish(host)(MqttPublishInput(MqttTarget(pin.ids.profileId, pin.ids.connectionId), request))
    }
}

internal class AssistantMqttPublishTool(private val publisher: AssistantMqttPublish) : AppTool<AssistantMqttPublishTool.Input>(AssistantToolId.MQTT_PUBLISH,
    "Publish one owner-requested text message through an already connected saved MQTT explorer. Use profile_id and connection_id from profile_information. The owner may be asked to approve the exact topic, payload, QoS and retained flag. Empty retained payload clears the retained value. No retries.",
    objectSchema { string("profile_id", 256); string("connection_id", 256); string("topic", 4096); string("payload", 8192); integer("qos", 0, maximum = 2); boolean("retain") }) {
    internal data class Input(val ids: SavedTunnelIds, val topic: String, val payload: String, val qos: Int, val retain: Boolean) {
        override fun toString() = "Input(qos=$qos, retain=$retain)"
    }
    private var prepared: Pair<Input, AssistantMqttPublish.Pin>? = null
    override fun parse(args: StrictArgs) = args.run {
        exactKeys("profile_id", "connection_id", "topic", "payload", "qos", "retain")
        val ids = SavedTunnelIds(string("profile_id", 256), string("connection_id", 256))
        val topic = string("topic", 4096, allowBlank = true)
        if (topicProblem(topic) != null) throw InvalidArguments()
        Input(ids, topic, string("payload", 8192, allowBlank = true), int("qos", 0, 2), boolean("retain"))
    }
    override val invalidArguments = ToolOutput("Provide profile_id, connection_id, a valid MQTT topic, text payload, qos 0..2 and retain boolean.", ToolOutcome.FAILED)
    override fun asksAbout(args: Input): Boolean {
        prepared = publisher.pin(args.ids)?.let { args to it }
        return prepared != null
    }
    override fun describeChange(args: Input): ChangeRequest {
        val target = checkNotNull(prepared).second.target.target
        return ChangeRequest(id, GrantTarget(args.ids.profileId, args.ids.connectionId), args.payload,
            mqtt = MqttPublishCard(args.topic, args.qos, args.retain, target.profileName, target.name, target.device, target.port))
    }
    override suspend fun run(args: Input): ToolOutput {
        val saved = prepared.also { prepared = null } ?: return ToolOutput("No connected saved MQTT explorer is available. Nothing was published.", ToolOutcome.FAILED)
        val original = saved.first
        if (original != args)
            return ToolOutput("The MQTT request changed. Nothing was published.", ToolOutcome.FAILED)
        val result = publisher.publish(saved.second, PublishRequest(args.topic, args.payload, args.qos, args.retain, json = false))
        return when (result) {
            is OpResult.Done -> ToolOutput(if (result.value == MqttDelivery.ACCEPTED) "Accepted by the broker." else "Sent without broker confirmation; delivery is unconfirmed.")
            is OpResult.Refused -> ToolOutput("The MQTT connection is unavailable or changed. Nothing was published.", ToolOutcome.FAILED)
            is OpResult.Failed -> ToolOutput(when (val reason = result.reason) {
                is MqttWriteFailure.BrokerRefused -> "The broker refused the publication (code ${reason.code})."
                else -> "Publication outcome is unknown. Do not retry automatically."
            }, ToolOutcome.FAILED)
        }
    }
    override fun failure(error: Exception) = ToolOutput("Could not publish; the outcome may be unknown. Do not retry automatically.", ToolOutcome.FAILED)
}
