package ru.adsrv.frpjump.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The wire format this app speaks; the server's `common/api.py` PROTOCOL_VERSION. */
const val PROTOCOL_VERSION = 1

const val AGENT_API_PREFIX = "/api/agent"

val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
data class EnrollRequest(
    val token: String,
    @SerialName("agent_version") val agentVersion: String?,
    @SerialName("requested_name") val requestedName: String? = null,
)

@Serializable
data class EnrollResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("api_token") val apiToken: String,
    @SerialName("cert_pem") val certPem: String,
    @SerialName("key_pem") val keyPem: String,
    @SerialName("ca_cert_pem") val caCertPem: String,
    @SerialName("server_addr") val serverAddr: String,
    @SerialName("server_port") val serverPort: Int,
    @SerialName("protocol_version") val protocolVersion: Int,
)

/** `GET /api/agent/devices`: timestamps are `datetime.isoformat()`, see [parseServerTime]. */
@Serializable
data class OwnedDevice(
    val name: String,
    @SerialName("enrolled_at") val enrolledAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String?,
    val enabled: Boolean,
)

/** FastAPI errors are `{"detail": "..."}`; anything else is shown as the raw body. */
fun errorDetail(body: String): String {
    val element = runCatching { WireJson.parseToJsonElement(body) }.getOrNull()
    val detail = (element as? JsonObject)?.get("detail")
    return (detail as? JsonPrimitive)?.takeIf { it.isString }?.content ?: body
}
