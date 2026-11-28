package ru.adsrv.frpjump.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ssh.PinnedHostKey
import ru.adsrv.frpjump.ssh.SshLogin
import java.security.MessageDigest
import java.util.Base64

/** What reading a connection's persistent fingerprint gives: its value, that it is gone for good, or that it cannot be told now (ask, keep the record). */
sealed interface PersistentFp {
    data class Known(val fp: String) : PersistentFp

    /** The profile or the connection is absent from a LOADED profile state. */
    data object Gone : PersistentFp

    /** Profiles not loaded yet, a credential file or a login or host-key store unreadable: a cold start or a transient failure must not erase a long-term decision. */
    data object Unknown : PersistentFp
}

/**
 * The persisted fingerprint of a saved connection: one stable value, `"v1:" + base64url(SHA-256(JSON))`, over a JSON object with a fixed key
 * order, nulls written, no whitespace. In it: every field of the connection except the favourite flag; the profile's id, key alias and activation; the enrollment (a hash of the
 * PUBLIC fields control URL, device id and certificate); the SSH login (user and method) and the pinned host key of the connection's device and port;
 * the MQTT user. Not in it: the profile's name and colour, the connection's enabled state, and every secret. No secret is ever read for it.
 */
object GrantFingerprint {
    const val PREFIX = "v1:"

    /** The enrollment's identity: SHA-256 (hex) of control URL, device id and certificate joined by newlines. */
    fun enrollmentHash(controlUrl: String, deviceId: String, certPem: String): String =
        MessageDigest.getInstance("SHA-256").digest("$controlUrl\n$deviceId\n$certPem".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun of(connection: Connection, profileId: String, keyAlias: String, activationPending: Boolean, enrollment: String?, sshLogin: SshLogin?,
        hostKey: PinnedHostKey?, mqttUser: String?, webCert: String? = null): String {
        val json: JsonObject = buildJsonObject {
            put("connection", buildJsonObject {
                put("id", connection.id); put("device", connection.device); put("port", connection.port)
                put("kind", WireJson.encodeToJsonElement(connection.kind)); put("custom_name", connection.customName?.let(::JsonPrimitive) ?: JsonNull)
                put("https", connection.https); put("user", connection.user?.let(::JsonPrimitive) ?: JsonNull)
                put("local_port", connection.localPort?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("profile", buildJsonObject { put("id", profileId); put("key_alias", keyAlias); put("activation_pending", activationPending) })
            put("enrollment", enrollment?.let(::JsonPrimitive) ?: JsonNull)
            put("ssh_login", sshLogin?.let { buildJsonObject { put("user", it.user); put("method", WireJson.encodeToJsonElement(it.method)) } } ?: JsonNull)
            put("host_key", hostKey?.let { buildJsonObject { put("type", it.type); put("fingerprint", it.fingerprint) } } ?: JsonNull)
            put("mqtt_user", mqttUser?.let(::JsonPrimitive) ?: JsonNull)
            if (connection.kind == ru.adsrv.frpjump.ports.PortKind.WEB) put("web_cert", webCert?.let(::JsonPrimitive) ?: JsonNull)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toString().toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
