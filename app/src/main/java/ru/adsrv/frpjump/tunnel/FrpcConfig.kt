package ru.adsrv.frpjump.tunnel

/**
 * One consumed grant as frpc needs it: [localPort] is where it listens on
 * 127.0.0.1. [direct]: an xtcp visitor tries peer to peer, falling back to
 * the stcp one; otherwise the stcp visitor alone listens. [attempt] counts
 * "Retry direct": a new number renames the xtcp visitor, which makes a
 * reload start it afresh (and so punch again) while everything else stays.
 */
data class VisitorSpec(
    val grantId: String,
    val secret: String,
    val localPort: Int,
    val direct: Boolean = true,
    val attempt: Int = 0,
) {
    override fun toString() = "VisitorSpec($grantId, 127.0.0.1:$localPort, direct=$direct#$attempt)"
}

/** Where frpc finds the device's TLS material. */
data class TlsFiles(val certFile: String, val keyFile: String, val caFile: String)

/** xtcp hole punching waits this long per connection before using the relay (server `xtcp_fallback_timeout_ms`). */
const val DEFAULT_FALLBACK_TIMEOUT_MS = 500

/**
 * Kept open while a direct tunnel is up (`keepTunnelOpen`): frpc punches as
 * soon as the visitor starts, not on the first connection, and every
 * [DIRECT_CHECK_SECONDS] opens a stream on the tunnel to see that it still
 * works, punching again when not, at most [DIRECT_RETRIES_AN_HOUR] times an
 * hour after the first few (frp 0.70 `client/visitor/xtcp.go`).
 */
const val DIRECT_CHECK_SECONDS = 30
const val DIRECT_RETRIES_AN_HOUR = 8

/**
 * QUIC under a direct tunnel (frp's defaults, written out so they do not
 * change under us): a PING every 10 s keeps the NAT mapping of an idle
 * tunnel open (mobile NATs drop idle UDP mappings after about 30 s), and a
 * peer silent for 30 s ends the tunnel, which the check above then notices.
 */
const val QUIC_KEEPALIVE_SECONDS = 10
const val QUIC_IDLE_TIMEOUT_SECONDS = 30

fun stcpVisitorName(grantId: String) = "$grantId-stcp-visitor"
fun xtcpVisitorName(grantId: String, attempt: Int = 0) = "$grantId-xtcp-visitor" + if (attempt > 0) "-$attempt" else ""
private fun stcpProxyName(grantId: String) = "$grantId-stcp"
private fun xtcpProxyName(grantId: String) = "$grantId-xtcp"

/**
 * frpc.toml for the phone: the consumer half of the Linux agent's
 * `driver/frp/config.py` `build_frpc_config`, key for key and in the same
 * order. No proxies: the phone exposes nothing.
 *
 * Per [VisitorSpec.direct] grant an stcp visitor plus an xtcp visitor that
 * falls back to it after [fallbackTimeoutMs]; only the xtcp one listens (the
 * stcp one has `bindPort = -1` and only takes connections handed over by
 * the fallback). A grant that is not direct (the agent's `disable_p2p`)
 * gets a single stcp visitor bound directly. Every visitor sets
 * `useEncryption`: a punched p2p leg bypasses the relay's TLS.
 *
 * Unlike the agent, the xtcp visitor keeps its tunnel open
 * ([DIRECT_CHECK_SECONDS]) and QUIC's keepalive is set explicitly
 * ([QUIC_KEEPALIVE_SECONDS]): a direct tunnel must not drop after idle.
 *
 * [admin] adds frpc's admin API on 127.0.0.1 (the agent runs without it):
 * the phone reloads visitors through it instead of restarting frpc, which
 * would drop every open connection.
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
    fallbackTimeoutMs: Int = DEFAULT_FALLBACK_TIMEOUT_MS,
    logLevel: String = "info",
    plainLog: Boolean = true,
    admin: FrpcAdmin? = null,
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
            "quic" to linkedMapOf<String, Any>(
                "keepalivePeriod" to QUIC_KEEPALIVE_SECONDS,
                "maxIdleTimeout" to QUIC_IDLE_TIMEOUT_SECONDS,
            ),
        ),
        "log" to log,
        "metadatas" to linkedMapOf<String, Any>(
            "frp_jump_device" to deviceId,
            "frp_jump_token" to apiToken,
        ),
    )
    if (admin != null) {
        config["webServer"] = linkedMapOf<String, Any>(
            "addr" to "127.0.0.1",
            "port" to admin.port,
            "user" to admin.user,
            "password" to admin.password,
        )
    }
    val entries = visitorEntries(visitors, fallbackTimeoutMs)
    if (entries.isNotEmpty()) config["visitors"] = entries
    return config
}

/** frpc's `[[visitors]]` for [visitors], in config order. */
fun visitorEntries(visitors: List<VisitorSpec>, fallbackTimeoutMs: Int = DEFAULT_FALLBACK_TIMEOUT_MS): List<Map<String, Any>> =
    visitors.flatMap { v ->
        if (!v.direct) {
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
                visitor(xtcpVisitorName(v.grantId, v.attempt), "xtcp", xtcpProxyName(v.grantId), v.secret) {
                    put("bindAddr", "127.0.0.1")
                    put("bindPort", v.localPort)
                    put("keepTunnelOpen", true)
                    put("maxRetriesAnHour", DIRECT_RETRIES_AN_HOUR)
                    put("minRetryInterval", DIRECT_CHECK_SECONDS)
                    put("fallbackTo", stcpVisitorName(v.grantId))
                    put("fallbackTimeoutMs", fallbackTimeoutMs)
                },
            )
        }
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
