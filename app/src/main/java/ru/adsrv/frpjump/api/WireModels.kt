package ru.adsrv.frpjump.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import ru.adsrv.frpjump.devices.DisplayChange

/** The wire format this app speaks; the server's `common/api.py` PROTOCOL_VERSION. */
const val PROTOCOL_VERSION = 1

const val AGENT_API_PREFIX = "/api/agent"

val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** `GET /api/agent/version`, unauthenticated: answered before anything is sent. */
@Serializable
data class VersionResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("package_version") val packageVersion: String,
    /** Optional capabilities; an old server sends none, read as an empty list. */
    val features: List<String> = emptyList(),
)

/** In [VersionResponse.features]: device labels and icons are supported. */
const val FEATURE_DEVICE_DISPLAY = "device-display"

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
    /** Free text the owner set; null or absent when unset or the server is older. */
    val label: String? = null,
    /** The owner's icon choice; null means automatic. */
    val icon: String? = null,
    /** What the device reports about itself; null when unknown. */
    val kind: String? = null,
)

/**
 * The `/display` body: a field left out stays as it is on the server, an
 * explicit null clears it. Built by hand, since [WireJson] drops nulls.
 */
fun displayBody(change: DisplayChange): String = buildJsonObject {
    change.label?.let { put("label", it.value?.let(::JsonPrimitive) ?: JsonNull) }
    change.icon?.let { put("icon", it.value?.let(::JsonPrimitive) ?: JsonNull) }
}.toString()

/** FastAPI errors are `{"detail": "..."}`; anything else is shown as the raw body. */
fun errorDetail(body: String): String {
    val element = runCatching { WireJson.parseToJsonElement(body) }.getOrNull()
    val detail = (element as? JsonObject)?.get("detail")
    return (detail as? JsonPrimitive)?.takeIf { it.isString }?.content ?: body
}

/** What this app reports about itself in every heartbeat; an old server ignores it. */
const val PHONE_KIND = "phone"

@Serializable
data class HeartbeatRequest(
    @SerialName("agent_version") val agentVersion: String?,
    val kind: String?,
)

/** `protocol` is the server's `ServiceProtocol`: only `"ssh"` and `"tcp"` exist. */
@Serializable
data class ConnectRequest(
    @SerialName("device_name") val deviceName: String,
    @SerialName("target_port") val targetPort: Int,
    val protocol: String,
)

@Serializable
data class ConnectResponse(
    @SerialName("grant_id") val grantId: String,
    @SerialName("exposer_device_name") val exposerDeviceName: String,
    @SerialName("target_port") val targetPort: Int,
    val protocol: String,
)

@Serializable
data class DisconnectRequest(
    @SerialName("device_name") val deviceName: String,
    @SerialName("target_port") val targetPort: Int,
)

/** A grant this phone consumes: the exposing side runs the matching proxies. */
@Serializable
data class ConsumedGrant(
    @SerialName("grant_id") val grantId: String,
    val secret: String,
    @SerialName("service_name") val serviceName: String,
    val protocol: String,
    @SerialName("exposer_device_name") val exposerDeviceName: String,
    @SerialName("target_port") val targetPort: Int,
) {
    override fun toString() = "ConsumedGrant($grantId, $exposerDeviceName:$targetPort)"
}

/**
 * `GET /api/agent/desired-state`. `exposed` is ignored: the phone exposes nothing.
 * A missing `protocol_version` means a server older than the check, accepted
 * as the Linux agent does; only an explicit different number is a mismatch.
 */
@Serializable
data class DesiredStateResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("server_addr") val serverAddr: String,
    @SerialName("server_port") val serverPort: Int,
    val consumed: List<ConsumedGrant>,
    @SerialName("protocol_version") val protocolVersion: Int? = null,
)
