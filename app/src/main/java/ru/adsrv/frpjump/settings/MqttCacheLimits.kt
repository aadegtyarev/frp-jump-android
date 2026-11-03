package ru.adsrv.frpjump.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** New-explorer owned tree/history accounting, not a measurement of all live UI/library heap. */
@Serializable
data class MqttCacheLimits(
    @SerialName("storage_bytes") val storageBytes: Int = DEFAULT_STORAGE_BYTES,
    val topics: Int = DEFAULT_TOPICS,
    val history: Int = DEFAULT_HISTORY,
    val overflow: MqttOverflow = MqttOverflow.DROP_INCOMING,
) {
    companion object {
        const val DEFAULT_STORAGE_BYTES = 32 * 1024 * 1024
        const val DEFAULT_TOPICS = 100_000
        const val DEFAULT_HISTORY = 20
        const val MIN_STORAGE_BYTES = 4096
        const val MAX_STORAGE_BYTES = 256 * 1024 * 1024
        const val MAX_TOPICS = 1_000_000
        const val MAX_HISTORY = 200
    }
}

fun validMqttCacheLimits(limits: MqttCacheLimits): Boolean =
    limits.storageBytes in MqttCacheLimits.MIN_STORAGE_BYTES..MqttCacheLimits.MAX_STORAGE_BYTES &&
        limits.topics in 1..MqttCacheLimits.MAX_TOPICS && limits.history in 1..MqttCacheLimits.MAX_HISTORY
