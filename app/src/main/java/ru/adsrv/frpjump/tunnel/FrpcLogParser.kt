package ru.adsrv.frpjump.tunnel

/** What the card shows per tunnel. */
enum class TunnelStatus { CONNECTING, READY, DIRECT, RELAYED, FAILED }

data class VisitorState(val status: TunnelStatus, val detail: String? = null)

/**
 * Best-effort tunnel state from frpc 0.70's own log lines (`info` level).
 * frpc's admin API (used here only to reload) reports proxies, not
 * visitors, so this reads what frpc says, matched against the upstream
 * sources (`client/service.go`, the visitors under `client/visitor/`):
 *
 * - `login to server success`: logged in; frpc (re)starts every visitor in
 *   config order, each followed by `start visitor success` or
 *   `start error: ...`, which carry no visitor name: they are matched to the
 *   names in config order. A later retry is announced by
 *   `try to start visitor [<name>]`.
 * - `connect to server error` / `login to the server failed`: the relay is
 *   unreachable or refused this phone; frpc keeps retrying.
 * - `[<grant>-xtcp-visitor] establishing nat hole connection successful`:
 *   hole punching worked, traffic goes peer to peer (direct).
 * - `[<grant>-xtcp-visitor] open tunnel error`: no p2p path in time; the
 *   connection is handed to the stcp visitor (relayed).
 * - `[<grant>-stcp-visitor] dialRawVisitorConn error` / `wrapVisitorConn
 *   error`, `transfer connection to visitor ... error`: the relay could not
 *   carry a connection (e.g. the device is offline): failed until the next
 *   success line.
 *
 * - After a reload (`GET /api/reload`) frpc starts only the visitors that
 *   are new or changed, in config order, each again followed by `start
 *   visitor success` or `start error: ...`: [reconfigure] lines them up.
 *
 * Hole punching only starts with the first connection, so a started tunnel
 * stays READY ("route picked on first use") until something connects. With
 * [relayOnly] there is nothing to pick: a started tunnel is RELAYED.
 */
class FrpcLogParser(grantIds: List<String>, private val relayOnly: Boolean) {
    private var grantIds: List<String> = grantIds
    private val states = grantIds.associateWith { VisitorState(TunnelStatus.CONNECTING) }.toMutableMap()
    private val startOrder = ArrayDeque<String>()
    private var visitorNames: List<String> = visitorNamesOf(grantIds)

    @Synchronized
    fun snapshot(): Map<String, VisitorState> = states.toMap()

    /**
     * frpc is about to reload with [grantIds]; it will start the visitors of
     * [restarted] (new or changed grants, in config order) and leave the
     * others running as they are.
     */
    @Synchronized
    fun reconfigure(grantIds: List<String>, restarted: List<String>) {
        this.grantIds = grantIds
        visitorNames = visitorNamesOf(grantIds)
        states.keys.retainAll(grantIds.toSet())
        for (grant in grantIds) if (grant in restarted || grant !in states) states[grant] = VisitorState(TunnelStatus.CONNECTING)
        startOrder.clear()
        startOrder.addAll(visitorNamesOf(grantIds.filter { it in restarted }))
    }

    private fun visitorNamesOf(grants: List<String>): List<String> =
        grants.flatMap { if (relayOnly) listOf(stcpVisitorName(it)) else listOf(stcpVisitorName(it), xtcpVisitorName(it)) }

    /** Feeds one log line; returns true when a state changed. */
    @Synchronized
    fun feed(rawLine: String): Boolean {
        val line = ANSI.replace(rawLine, "")
        val before = states.toMap()
        when {
            TRY_START.containsMatchIn(line) -> {
                startOrder.clear()
                TRY_START.find(line)?.groupValues?.get(1)?.let(startOrder::addLast)
            }
            // Visitor-prefixed lines first: their errors may quote "connect to server error".
            VISITOR.containsMatchIn(line) -> onVisitorLine(line)
            "login to server success" in line -> {
                startOrder.clear()
                startOrder.addAll(visitorNames)
                grantIds.forEach { states[it] = VisitorState(TunnelStatus.CONNECTING) }
            }
            "login to the server failed" in line || "connect to server error" in line ->
                grantIds.forEach { states[it] = VisitorState(TunnelStatus.FAILED, "Cannot log in to the relay: ${tail(line, "error")}") }
            "start visitor success" in line -> startOrder.removeFirstOrNull()?.let(::started)
            "start error:" in line -> startOrder.removeFirstOrNull()?.let { name ->
                grantOf(name)?.let { states[it] = VisitorState(TunnelStatus.FAILED, tail(line, "start error:")) }
            }
        }
        return states != before
    }

    /** frpc exited: nothing listens any more. */
    @Synchronized
    fun exited(code: Int) {
        grantIds.forEach { states[it] = VisitorState(TunnelStatus.FAILED, "frpc stopped (exit code $code)") }
    }

    private fun started(name: String) {
        val grant = grantOf(name) ?: return
        val listening = if (relayOnly) name == stcpVisitorName(grant) else name == xtcpVisitorName(grant)
        if (listening && states[grant]?.status != TunnelStatus.FAILED) {
            states[grant] = VisitorState(if (relayOnly) TunnelStatus.RELAYED else TunnelStatus.READY)
        }
    }

    private fun onVisitorLine(line: String) {
        val name = VISITOR.find(line)?.groupValues?.get(1) ?: return
        val grant = grantOf(name) ?: return
        val xtcp = name == xtcpVisitorName(grant)
        when {
            xtcp && "establishing nat hole connection successful" in line -> states[grant] = VisitorState(TunnelStatus.DIRECT)
            xtcp && "open tunnel error" in line -> states[grant] = VisitorState(TunnelStatus.RELAYED, "No peer-to-peer path, using the relay")
            "transfer connection to visitor" in line && "error" in line ||
                "dialRawVisitorConn error" in line || "wrapVisitorConn error" in line ->
                states[grant] = VisitorState(TunnelStatus.FAILED, tail(line, "error"))
        }
    }

    private fun grantOf(visitorName: String): String? = when {
        visitorName.endsWith("-xtcp-visitor") -> visitorName.removeSuffix("-xtcp-visitor")
        visitorName.endsWith("-stcp-visitor") -> visitorName.removeSuffix("-stcp-visitor")
        else -> null
    }?.takeIf { it in states }

    /** The text after the first [marker], for a short detail line. */
    private fun tail(line: String, marker: String): String {
        val at = line.indexOf(marker)
        return (if (at < 0) line else line.substring(at)).trim().take(200)
    }

    private companion object {
        val ANSI = Regex("\u001B\\[[0-9;]*m")
        val TRY_START = Regex("try to start visitor \\[([^\\]]+)]")
        val VISITOR = Regex("\\[([^\\]\\s]+-[sx]tcp-visitor)]")
    }
}
