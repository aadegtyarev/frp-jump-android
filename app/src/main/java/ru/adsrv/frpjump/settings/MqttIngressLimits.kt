package ru.adsrv.frpjump.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MqttOverflow {
    @SerialName("drop_incoming") DROP_INCOMING,
    @SerialName("stop_session") STOP_SESSION,
}

/** Captured by a new MQTT explorer; accounted bytes are not a measurement of total JVM heap. */
@Serializable
data class MqttIngressLimits(
    @SerialName("payload_bytes") val payloadBytes: Int = DEFAULT_PAYLOAD_BYTES,
    @SerialName("queue_bytes") val queueBytes: Int = DEFAULT_QUEUE_BYTES,
    @SerialName("queue_messages") val queueMessages: Int = DEFAULT_QUEUE_MESSAGES,
    val overflow: MqttOverflow = MqttOverflow.DROP_INCOMING,
) {
    companion object {
        const val DEFAULT_PAYLOAD_BYTES = 1024 * 1024
        const val DEFAULT_QUEUE_BYTES = 8 * 1024 * 1024
        const val DEFAULT_QUEUE_MESSAGES = 10_000
        const val MIN_PAYLOAD_BYTES = 1024
        const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        const val MIN_QUEUE_BYTES = 1024
        const val MAX_QUEUE_BYTES = 256 * 1024 * 1024
        const val MAX_QUEUE_MESSAGES = 100_000
        // Logical fixed charge for the queued message/closure, in addition to topic UTF-16 and payload.
        const val MESSAGE_CHARGE = 256
    }
}

fun validMqttIngressLimits(limits: MqttIngressLimits): Boolean =
    limits.payloadBytes in MqttIngressLimits.MIN_PAYLOAD_BYTES..MqttIngressLimits.MAX_PAYLOAD_BYTES &&
        limits.queueBytes in MqttIngressLimits.MIN_QUEUE_BYTES..MqttIngressLimits.MAX_QUEUE_BYTES &&
        limits.queueMessages in 1..MqttIngressLimits.MAX_QUEUE_MESSAGES &&
        limits.queueBytes.toLong() >= limits.payloadBytes.toLong() + MqttIngressLimits.MESSAGE_CHARGE
