package ru.adsrv.frpjump.tunnel

/** One consumed grant as frpc needs it: [localPort] is where it listens on 127.0.0.1. */
data class VisitorSpec(val grantId: String, val secret: String, val localPort: Int) {
    override fun toString() = "VisitorSpec($grantId, 127.0.0.1:$localPort)"
}

/** Where frpc finds the device's TLS material. */
data class TlsFiles(val certFile: String, val keyFile: String, val caFile: String)

/** xtcp hole punching waits this long per connection before using the relay (server `xtcp_fallback_timeout_ms`). */
const val DEFAULT_FALLBACK_TIMEOUT_MS = 500

fun stcpVisitorName(grantId: String) = "$grantId-stcp-visitor"
fun xtcpVisitorName(grantId: String) = "$grantId-xtcp-visitor"
private fun stcpProxyName(grantId: String) = "$grantId-stcp"
private fun xtcpProxyName(grantId: String) = "$grantId-xtcp"

/**
 * frpc.toml for the phone: the consumer half of the Linux agent's
 * `driver/frp/config.py` `build_frpc_config`, key for key and in the same
 * order. No proxies: the phone exposes nothing.
 *
 * Per grant an stcp visitor plus an xtcp visitor that falls back to it after
 * [fallbackTimeoutMs]; only the xtcp one listens (the stcp one has
 * `bindPort = -1` and only takes connections handed over by the fallback).
 * [relayOnly] (the agent's `disable_p2p`) keeps a single stcp visitor bound
 * directly. Every visitor sets `useEncryption`: a punched p2p leg bypasses
 * the relay's TLS.
 *
 * One deliberate difference: the agent logs at `warn`; the phone needs
 * frpc's `info` lines (login, visitor start, hole punch success) to show a
 * tunnel's state, so [logLevel] defaults to `info`, without ANSI colours.
 * frpc never prints secrets at that level; the config itself is never logged.
 */
fun buildFrpcConfig(
    deviceId: String,
    apiToken: String,
    serverAddr: String,
    serverPort: Int,
    tls: TlsFiles,
    visitors: List<VisitorSpec>,
    relayOnly: Boolean = false,
    fallbackTimeoutMs: Int = DEFAULT_FALLBACK_TIMEOUT_MS,
    logLevel: String = "info",
    plainLog: Boolean = true,
): Map<String, Any> {
    val log = linkedMapOf<String, Any>("level" to logLevel)
    if (plainLog) log["disablePrintColor"] = true
    val config = linkedMapOf<String, Any>(
        "serverAddr" to serverAddr,
        "serverPort" to serverPort,
        "loginFailExit" to false,
        "transport" to linkedMapOf<String, Any>(
            "tls" to linkedMapOf<String, Any>(
                "certFile" to tls.certFile,
                "keyFile" to tls.keyFile,
                "trustedCaFile" to tls.caFile,
            ),
            "heartbeatInterval" to 5,
            "heartbeatTimeout" to 30,
        ),
        "log" to log,
        "metadatas" to linkedMapOf<String, Any>(
            "frp_jump_device" to deviceId,
            "frp_jump_token" to apiToken,
        ),
    )
    val entries = visitors.flatMap { v ->
        if (relayOnly) {
            listOf(
                visitor(stcpVisitorName(v.grantId), "stcp", stcpProxyName(v.grantId), v.secret) {
                    put("bindAddr", "127.0.0.1")
                    put("bindPort", v.localPort)
                },
            )
        } else {
            listOf(
                visitor(stcpVisitorName(v.grantId), "stcp", stcpProxyName(v.grantId), v.secret) {
                    put("bindPort", -1)
                },
                visitor(xtcpVisitorName(v.grantId), "xtcp", xtcpProxyName(v.grantId), v.secret) {
                    put("bindAddr", "127.0.0.1")
                    put("bindPort", v.localPort)
                    put("fallbackTo", stcpVisitorName(v.grantId))
                    put("fallbackTimeoutMs", fallbackTimeoutMs)
                },
            )
        }
    }
    if (entries.isNotEmpty()) config["visitors"] = entries
    return config
}

private fun visitor(
    name: String,
    type: String,
    serverName: String,
    secret: String,
    extra: LinkedHashMap<String, Any>.() -> Unit,
): Map<String, Any> = linkedMapOf<String, Any>(
    "name" to name,
    "type" to type,
    "serverName" to serverName,
    "secretKey" to secret,
).apply(extra).apply { put("transport", linkedMapOf<String, Any>("useEncryption" to true)) }
