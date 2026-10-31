package ru.adsrv.frpjump.tunnel

/**
 * What the card shows per tunnel. [TRYING]: a direct tunnel is being
 * punched (up to [TRY_DIRECT_MS]); [RELAYED]: connections go through the
 * server, either because direct is off for the tunnel or because it did not
 * work ([VisitorState.directFailed]).
 */
enum class TunnelStatus { CONNECTING, TRYING, DIRECT, RELAYED, FAILED }

data class VisitorState(
    val status: TunnelStatus,
    val detail: String? = null,
    /** [TunnelStatus.RELAYED] because direct was tried and did not work (or dropped and could not be redone). */
    val directFailed: Boolean = false,
    /**
     * [TunnelStatus.TRYING], but the device has no direct tunnel to meet yet
     * (it restarts its frpc for a new grant): frpc would wait for its next
     * check, so [TunnelManager] tries again shortly within the same window.
     */
    val waitingForPeer: Boolean = false,
    /**
     * The hole is punched; the tunnel is DIRECT once its QUIC session is up,
     * which frpc does not log: when no `init tunnel session error` follows
     * in [SESSION_GRACE_MS].
     */
    val confirming: Boolean = false,
)

/** quic-go gives up a dial after 5 s of silence (its handshake idle timeout); a little more. */
const val SESSION_GRACE_MS = 6_000L

/** How long "Trying direct…" lasts before the tunnel is shown as through the server. */
const val TRY_DIRECT_MS = 10_000L

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
 * - After a reload (`GET /api/reload`) frpc starts only the visitors that
 *   are new or changed, in config order, each again followed by `start
 *   visitor success` or `start error: ...`: [reconfigure] lines them up.
 * - `[<name>] dialRawVisitorConn error` / `wrapVisitorConn error`,
 *   `transfer connection to visitor ... error`: the relay could not carry a
 *   connection (e.g. the device is offline): failed until the next success
 *   line.
 *
 * Direct tunnels (the xtcp visitor keeps its tunnel open, so it punches as
 * soon as it starts, see [visitorEntries]):
 *
 * - the xtcp visitor started: TRYING, for up to [TRY_DIRECT_MS] ([expire]).
 * - `[<xtcp>] establishing nat hole connection successful`: punched, but
 *   not yet direct: frpc then dials QUIC over the hole, which can still
 *   fail (seen behind the emulator's NAT: `init tunnel session error: dial
 *   quic error: timeout` 5 s later). DIRECT when that error has not come in
 *   [SESSION_GRACE_MS] ([expire]); meanwhile still TRYING (or quietly
 *   RELAYED), [VisitorState.confirming].
 * - `nathole precheck error` (seen on every new connection: the device's
 *   frpc restarts for the new grant and has no xtcp proxy for a few
 *   seconds): still TRYING, [VisitorState.waitingForPeer].
 * - `nathole prepare error`, `nathole exchange info error`, `make hole
 *   error`, `init tunnel session error` (the punch failed) or no success in
 *   [TRY_DIRECT_MS]: RELAYED, direct failed.
 *   Connections already fall back to the stcp visitor meanwhile.
 * - While DIRECT, `get tunnel connection error` (the tunnel's periodic
 *   check, or a connection, found it dead) or `open tunnel error` (a
 *   connection fell back): the tunnel dropped; frpc punches again at once,
 *   so it is TRYING again, with the same outcomes.
 * - While RELAYED after a failure frpc keeps retrying by itself, quietly:
 *   only a success changes the state (to DIRECT).
 *
 * A reload that restarts a TRYING tunnel's xtcp visitor (the retry for a
 * device not ready yet) keeps its window: it still ends [TRY_DIRECT_MS]
 * after it began.
 *
 * A grant that is not direct is RELAYED once its stcp visitor starts.
 */
class FrpcLogParser(
    visitors: List<VisitorSpec>,
    /** Monotonic milliseconds. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var specs: Map<String, VisitorSpec> = visitors.associateBy { it.grantId }
    private var order: List<String> = visitors.map { it.grantId }
    private val states = order.associateWith { VisitorState(TunnelStatus.CONNECTING) }.toMutableMap()
    private val tryingSince = mutableMapOf<String, Long>()
    private val punchedAt = mutableMapOf<String, Long>()
    /** Windows of TRYING tunnels restarted by a reload, until they try again. */
    private val carried = mutableMapOf<String, Long>()
    private val startOrder = ArrayDeque<String>()

    @Synchronized
    fun snapshot(): Map<String, VisitorState> = states.toMap()

    /**
     * frpc is about to reload with [visitors]; it will start the visitors
     * named in [restarted] (new or changed, in config order) and leave the
     * others running as they are.
     */
    @Synchronized
    fun reconfigure(visitors: List<VisitorSpec>, restarted: List<String>) {
        specs = visitors.associateBy { it.grantId }
        order = visitors.map { it.grantId }
        states.keys.retainAll(order.toSet())
        tryingSince.keys.retainAll(order.toSet())
        punchedAt.keys.retainAll(order.toSet())
        carried.keys.retainAll(order.toSet())
        for (grant in order) {
            val listenerRestarted = listeningName(specs.getValue(grant)) in restarted
            if (listenerRestarted && specs.getValue(grant).direct) tryingSince[grant]?.let { carried[grant] = it }
            if (listenerRestarted || grant !in states) set(grant, VisitorState(TunnelStatus.CONNECTING))
        }
        startOrder.clear()
        startOrder.addAll(restarted)
    }

    /** Every visitor name of the config, in config order. */
    private fun allNames(): List<String> = order.flatMap { namesOf(specs.getValue(it)) }

    private fun namesOf(v: VisitorSpec): List<String> =
        if (v.direct) listOf(stcpVisitorName(v.grantId), xtcpVisitorName(v.grantId, v.attempt)) else listOf(stcpVisitorName(v.grantId))

    /** The visitor bound to the local port. */
    private fun listeningName(v: VisitorSpec) = if (v.direct) xtcpVisitorName(v.grantId, v.attempt) else stcpVisitorName(v.grantId)

    /** Feeds one log line; returns true when a state changed. */
    @Synchronized
    fun feed(rawLine: String): Boolean {
        val line = ANSI.replace(rawLine, "")
        val before = states.toMap()
        when {
            // frpc's own retry of a visitor that failed to start; not while a login or a
            // reload is still lining up its start lines, which carry no names.
            TRY_START.containsMatchIn(line) -> if (startOrder.isEmpty()) {
                TRY_START.find(line)?.groupValues?.get(1)?.let(startOrder::addLast)
            }
            // Visitor-prefixed lines first: their errors may quote "connect to server error".
            VISITOR.containsMatchIn(line) -> onVisitorLine(line)
            "login to server success" in line -> {
                startOrder.clear()
                startOrder.addAll(allNames())
                order.forEach { set(it, VisitorState(TunnelStatus.CONNECTING)) }
            }
            "login to the server failed" in line || "connect to server error" in line ->
                order.forEach { set(it, VisitorState(TunnelStatus.FAILED, "Cannot log in to the relay: ${tail(line, "error")}")) }
            "start visitor success" in line -> startOrder.removeFirstOrNull()?.let(::started)
            "start error:" in line -> startOrder.removeFirstOrNull()?.let { name ->
                grantOf(name)?.let { set(it, VisitorState(TunnelStatus.FAILED, tail(line, "start error:"))) }
            }
        }
        return states != before
    }

    /**
     * A punched tunnel whose session did not fail in [SESSION_GRACE_MS] is
     * DIRECT; "Trying direct…" that has gone on for [TRY_DIRECT_MS] without
     * a punch ends through the server. Returns true when a state changed.
     */
    @Synchronized
    fun expire(): Boolean {
        val now = clock()
        val confirmed = punchedAt.filter { (_, at) -> now - at >= SESSION_GRACE_MS }.keys
        confirmed.forEach { set(it, VisitorState(TunnelStatus.DIRECT)) }
        val due = tryingSince.filter { (grant, since) ->
            now - since >= TRY_DIRECT_MS && states[grant]?.status == TunnelStatus.TRYING && grant !in punchedAt
        }.keys
        due.forEach { set(it, failedDirect(null)) }
        return confirmed.isNotEmpty() || due.isNotEmpty()
    }

    /** frpc exited: nothing listens any more. */
    @Synchronized
    fun exited(code: Int) {
        order.forEach { set(it, VisitorState(TunnelStatus.FAILED, "frpc stopped (exit code $code)")) }
    }

    private fun set(grant: String, state: VisitorState) {
        if (state.status == TunnelStatus.TRYING) {
            if (states[grant]?.status != TunnelStatus.TRYING) tryingSince[grant] = carried.remove(grant) ?: clock()
        } else {
            tryingSince.remove(grant)
        }
        if (!state.confirming) punchedAt.remove(grant)
        // A window carried over a reload belongs to that attempt only.
        if (state.status == TunnelStatus.FAILED || state.status == TunnelStatus.RELAYED) carried.remove(grant)
        states[grant] = state
    }

    private fun failedDirect(reason: String?) = VisitorState(TunnelStatus.RELAYED, reason, directFailed = true)

    private fun started(name: String) {
        val grant = grantOf(name) ?: return
        val spec = specs.getValue(grant)
        if (name == listeningName(spec) && states[grant]?.status != TunnelStatus.FAILED) {
            set(grant, VisitorState(if (spec.direct) TunnelStatus.TRYING else TunnelStatus.RELAYED))
        }
    }

    private fun onVisitorLine(line: String) {
        val name = VISITOR.find(line)?.groupValues?.get(1) ?: return
        val grant = grantOf(name) ?: return
        val spec = specs.getValue(grant)
        val xtcp = spec.direct && name == xtcpVisitorName(grant, spec.attempt)
        val status = states[grant]?.status
        when {
            xtcp && PUNCHED in line -> {
                val current = states[grant]
                if (current != null && (status == TunnelStatus.TRYING || status == TunnelStatus.RELAYED && current.directFailed)) {
                    set(grant, current.copy(waitingForPeer = false, confirming = true))
                    punchedAt[grant] = clock()
                }
            }
            xtcp && PRECHECK_FAILED in line -> {
                if (status == TunnelStatus.TRYING) set(grant, VisitorState(TunnelStatus.TRYING, tail(line, "error"), waitingForPeer = true))
            }
            xtcp && PUNCH_FAILED.any { it in line } -> {
                // Quiet retries while through the server stay quiet, unless one had punched.
                if (status == TunnelStatus.TRYING || status == TunnelStatus.DIRECT || states[grant]?.confirming == true) {
                    set(grant, failedDirect(tail(line, "error")))
                }
            }
            xtcp && DROPPED.any { it in line } -> {
                if (status == TunnelStatus.DIRECT) set(grant, VisitorState(TunnelStatus.TRYING))
            }
            "transfer connection to visitor" in line && "error" in line ||
                "dialRawVisitorConn error" in line || "wrapVisitorConn error" in line ->
                set(grant, VisitorState(TunnelStatus.FAILED, tail(line, "error")))
        }
    }

    /** The grant a visitor of the current config belongs to; null for any other name (an old attempt's). */
    private fun grantOf(visitorName: String): String? =
        order.firstOrNull { visitorName in namesOf(specs.getValue(it)) }

    /** The text after the first [marker], for a short detail line. */
    private fun tail(line: String, marker: String): String {
        val at = line.indexOf(marker)
        return (if (at < 0) line else line.substring(at)).trim().take(200)
    }

    private companion object {
        val ANSI = Regex("\u001B\\[[0-9;]*m")
        val TRY_START = Regex("try to start visitor \\[([^\\]]+)]")
        val VISITOR = Regex("\\[([^\\]\\s]+-[sx]tcp-visitor(?:-\\d+)?)]")
        const val PUNCHED = "establishing nat hole connection successful"
        const val PRECHECK_FAILED = "nathole precheck error"
        val PUNCH_FAILED = listOf(
            "nathole prepare error",
            "nathole exchange info error",
            "make hole error",
            "init tunnel session error",
        )
        val DROPPED = listOf("get tunnel connection error", "open tunnel error")
    }
}
