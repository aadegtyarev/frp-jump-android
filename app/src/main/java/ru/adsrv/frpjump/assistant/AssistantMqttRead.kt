package ru.adsrv.frpjump.assistant

import kotlinx.serialization.json.*
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.mqtt.*
import ru.adsrv.frpjump.ops.*
import ru.adsrv.frpjump.ops.mqtt.*
import ru.adsrv.frpjump.ops.ssh.sshResultBudget
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.sessions.sessionHidden
import ru.adsrv.frpjump.settings.AssistantLimits
import ru.adsrv.frpjump.tunnel.SessionKind
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

private const val ITEMS = 20
private const val PAYLOAD_BYTES = 512

class AssistantMqttRead(private val tunnels: AssistantTunnels, private val sessions: MqttSessions, private val hidden: StateFlow<Set<String>>,
    private val localPort: (ru.adsrv.frpjump.ops.tunnel.SavedTunnel) -> Int?) {
    internal val operation = MqttRead { input ->
        val target = tunnels.find(input.ids)
        if (target == null || target.kind != PortKind.MQTT) OpResult.Refused(MqttReadRefusal.TargetUnavailable)
        else {
            val session = sessions.find(target.profileId, target.connectionId)
            val pin = tunnels.pin(input.ids)
            fun valid() = pin != null && tunnels.pin(input.ids) == pin && tunnels.find(input.ids) == target && tunnels.isOn(target) && session != null &&
                session.device == target.device && session.port == target.port && localPort(target) == session.localPort && sessions.find(target.profileId, target.connectionId) === session &&
                session.state.value is MqttState.Open && !sessionHidden(hidden.value, SessionKind.MQTT, session.id, target.ref)
            if (!valid()) OpResult.Refused(MqttReadRefusal.NotConnected)
            else {
                val values = session!!.tree.values(filterLevels(input.filter), ITEMS + 1)
                if (!valid()) OpResult.Refused(MqttReadRefusal.NotConnected)
                else OpResult.Done(MqttReadSnapshot(values, session.dropped.value, session.cacheDropped.value))
            }
        }
    }
}

internal class AssistantMqttReadTool(operation: Operation<MqttReadInput, MqttReadSnapshot>, limits: AssistantLimits) :
    OperationTool<MqttReadInput, MqttReadSnapshot>(AssistantToolId.MQTT_READ,
        "Read current values already observed by the owner's connected MQTT explorer for a saved MQTT connection. Use profile_id and connection_id from profile_information. Optional filter is an MQTT topic filter; no subscription is made. Results are incomplete cached observations, not broker state. Private messages go to the configured model.",
        objectSchema { string("profile_id", 256); string("connection_id", 256); string("filter", 1024, required = false) }, operation) {
    private val budget = sshResultBudget(limits.toolOutputChars)
    override fun parse(args: StrictArgs) = args.run {
        onlyKeys("profile_id", "connection_id", "filter")
        val ids = SavedTunnelIds(string("profile_id", 256), string("connection_id", 256))
        val filter = optionalString("filter", 1024, allowBlank = true) ?: "#"
        if (filterProblem(filter) != null) throw InvalidArguments()
        MqttReadInput(ids, filter)
    }
    override val invalidArguments = ToolOutput("Provide profile_id and connection_id from profile_information and an optional valid MQTT topic filter.", ToolOutcome.FAILED)
    override fun render(input: MqttReadInput, output: MqttReadSnapshot): String {
        val records = mutableListOf<JsonObject>()
        fun page(omitted: Boolean) = buildJsonObject {
            put("notice", "Current observed values only; the explorer cache is incomplete and is not broker state. # excludes root topics beginning with $.")
            put("ingress_dropped", output.ingressDropped); put("cache_dropped", output.cacheDropped)
            put("omitted", omitted); put("messages", JsonArray(records))
        }.toString()
        for ((path, value) in output.values.take(ITEMS)) {
            val bytes = value.payload
            val text = if (bytes.size <= PAYLOAD_BYTES) runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrNull() else null
            val record = buildJsonObject {
                put("topic", path.format()); put("payload", text?.let(::JsonPrimitive) ?: JsonNull)
                put("payload_bytes", bytes.size); put("payload_omitted", text == null)
                put("received_at", value.receivedAt); put("before_session", value.beforeSession)
                put("qos", value.qos); put("retained", value.retained); put("cleared", value.cleared)
            }
            records += record
            if (page(true).length > budget) { records.removeAt(records.lastIndex); break }
        }
        return page(records.size < output.values.size)
    }
    override val unexpected: OpFailure = MqttReadFailure.Unexpected
    override fun modelText(refusal: OpRefusal) = when (refusal) {
        MqttReadRefusal.TargetUnavailable -> "No saved MQTT connection matches these IDs. $PROVIDE_IDS"
        MqttReadRefusal.NotConnected -> "No connected MQTT explorer is available for this saved connection. Open it in the app first."
        else -> error("Unexpected MQTT read refusal")
    }
    override fun modelText(failure: OpFailure) = "Could not read the MQTT explorer."
}
