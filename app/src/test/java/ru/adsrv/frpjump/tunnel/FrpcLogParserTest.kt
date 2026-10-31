package ru.adsrv.frpjump.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lines as frpc 0.70 prints them, caller, run id and visitor prefixes
 * included: the ones in quotes below were captured from frpc on the
 * emulator and from the laptop (grant ids shortened to g1, g2).
 */
class FrpcLogParserTest {
    private var now = 0L

    private fun parser(vararg specs: VisitorSpec) = FrpcLogParser(specs.toList()) { now }

    private val g1 = VisitorSpec("g1", "s1", 40000)
    private val g2 = VisitorSpec("g2", "s2", 40001)

    private fun info(text: String) = "2026-10-30 20:30:06.154 [I] [visitor/xtcp.go:312] [0123456789abcdef0123456789abcdef] $text"
    private fun warn(text: String) = "2026-10-30 20:29:42.391 [W] [visitor/xtcp.go:308] [0123456789abcdef0123456789abcdef] $text"
    private fun error(text: String) = "2026-10-30 20:30:05.121 [E] [visitor/xtcp.go:168] [0123456789abcdef0123456789abcdef] $text"

    private val login = "2026-10-30 20:31:00.268 [I] [client/service.go:328] [0123456789abcdef0123456789abcdef] login to server success, get run id [0123456789abcdef0123456789abcdef]"
    private val startSuccess = "2026-10-30 20:31:00.269 [I] [visitor/visitor_manager.go:129] [0123456789abcdef0123456789abcdef] start visitor success"
    private val prepared = info("[g1-xtcp-visitor] nathole prepare success, nat type: EasyNAT, behavior: BehaviorNoChange, addresses: [52.1.2.3:4000], assistedAddresses: [10.0.2.15:4000]")
    private val punched = info("[g1-xtcp-visitor] establishing nat hole connection successful, sid [179300000000000000000000aa], remoteAddr [1.2.3.4:5]")
    private val holeFailed = warn("[g1-xtcp-visitor] make hole error: wait detect message error: read udp4 0.0.0.0:33520: i/o timeout")
    private val fellBack = "\u001B[1;31m" + error("[g1-xtcp-visitor] open tunnel error: open tunnel timeout") + "\u001B[0m"
    private val checkFailed = warn("[g1-xtcp-visitor] keepTunnelOpenWorker get tunnel connection error: timeout: no recent network activity")

    /** The hole punched and its QUIC session not failed in time. */
    private fun FrpcLogParser.punch(line: String = punched) {
        feed(line)
        now += SESSION_GRACE_MS
        expire()
    }

    private fun FrpcLogParser.state(grant: String = "g1") = snapshot().getValue(grant)
    private fun FrpcLogParser.status(grant: String = "g1") = state(grant).status

    /** Logged in with [g1] direct: its stcp, then its xtcp visitor started. */
    private fun trying(spec: VisitorSpec = g1) = parser(spec).apply {
        feed(login)
        feed(startSuccess)
        if (spec.direct) feed(startSuccess)
    }

    @Test
    fun `connecting until the listening visitor starts, then trying direct`() {
        val parser = parser(g1)
        assertEquals(TunnelStatus.CONNECTING, parser.status())
        parser.feed(login)
        parser.feed(startSuccess)
        assertEquals("the stcp visitor does not listen", TunnelStatus.CONNECTING, parser.status())
        parser.feed(startSuccess)
        assertEquals(TunnelStatus.TRYING, parser.status())
    }

    @Test
    fun `trying, then the hole is punched - direct`() {
        val parser = trying()
        parser.feed(prepared)
        assertEquals(TunnelStatus.TRYING, parser.status())
        parser.feed(punched)
        assertEquals("punched, the session not up yet", VisitorState(TunnelStatus.TRYING, confirming = true), parser.state())
        now += SESSION_GRACE_MS - 1
        assertFalse(parser.expire())
        now += 1
        assertTrue(parser.expire())
        assertEquals(VisitorState(TunnelStatus.DIRECT), parser.state())
    }

    @Test
    fun `punched but the QUIC session fails - through the server, direct failed`() {
        val parser = trying()
        parser.feed(punched)
        // As on the emulator, behind QEMU's NAT, 5 s after the punch.
        parser.feed(warn("[g1-xtcp-visitor] init tunnel session error: dial quic error: timeout: no recent network activity"))
        assertEquals(TunnelStatus.RELAYED, parser.status())
        assertTrue(parser.state().directFailed)
        now += SESSION_GRACE_MS
        assertFalse("nothing left to confirm", parser.expire())
    }

    @Test
    fun `a punch late in the window still gets its grace`() {
        val parser = trying()
        now += TRY_DIRECT_MS - 1_000
        parser.feed(punched)
        now += 1_000
        assertFalse(parser.expire())
        assertEquals(TunnelStatus.TRYING, parser.status())
        now += SESSION_GRACE_MS
        parser.expire()
        assertEquals(TunnelStatus.DIRECT, parser.status())
    }

    @Test
    fun `a quiet retry that punches but cannot open a session stays quiet`() {
        val parser = trying()
        parser.feed(holeFailed)
        val failed = parser.state()
        parser.feed(punched)
        assertEquals(TunnelStatus.RELAYED, parser.status())
        parser.feed(warn("[g1-xtcp-visitor] init tunnel session error: dial quic error: timeout: no recent network activity"))
        assertEquals(TunnelStatus.RELAYED, parser.status())
        assertTrue(parser.state().directFailed)
        assertFalse(parser.state().confirming)
        assertEquals(failed.status, parser.status())
    }

    @Test
    fun `trying, then the punch fails - through the server, direct failed`() {
        val parser = trying()
        parser.feed(prepared)
        parser.feed(holeFailed)
        val state = parser.state()
        assertEquals(TunnelStatus.RELAYED, state.status)
        assertTrue(state.directFailed)
        assertTrue(state.detail!!.startsWith("error: wait detect message error"))
    }

    @Test
    fun `every way the punch fails ends trying`() {
        for (line in listOf(
            "nathole prepare error: dial udp: lookup stun.easyvoip.com: no such host",
            "nathole exchange info error: wait natHoleRespMsg timeout",
            "make hole error: wait detect message error: read udp4 0.0.0.0:33520: i/o timeout",
            "init tunnel session error: dial quic error: timeout: no recent network activity",
        )) {
            val parser = trying()
            parser.feed(warn("[g1-xtcp-visitor] $line"))
            assertEquals(line, TunnelStatus.RELAYED, parser.status())
        }
    }

    @Test
    fun `the device not ready yet keeps trying, and a retry keeps the window`() {
        val parser = trying()
        // Captured on the emulator right after connecting: the device's frpc was restarting for the new grant.
        parser.feed(warn("[g1-xtcp-visitor] nathole precheck error: xtcp server for [g1-xtcp] doesn't exist"))
        val waiting = parser.state()
        assertEquals(TunnelStatus.TRYING, waiting.status)
        assertTrue(waiting.waitingForPeer)
        now += 3_000
        parser.reconfigure(listOf(g1.copy(attempt = 1)), restarted = listOf("g1-xtcp-visitor-1"))
        parser.feed(startSuccess)
        assertEquals(VisitorState(TunnelStatus.TRYING), parser.state())
        now += TRY_DIRECT_MS - 3_000
        assertTrue("ends 10 s after the first try", parser.expire())
        assertEquals(TunnelStatus.RELAYED, parser.status())
    }

    @Test
    fun `no outcome in 10 s - through the server, direct failed`() {
        val parser = trying()
        now += TRY_DIRECT_MS - 1
        assertFalse(parser.expire())
        assertEquals(TunnelStatus.TRYING, parser.status())
        now += 1
        assertTrue(parser.expire())
        assertEquals(VisitorState(TunnelStatus.RELAYED, directFailed = true), parser.state())
        assertFalse("expires once", parser.expire())
    }

    @Test
    fun `a connection falling back while trying does not end it`() {
        val parser = trying()
        parser.feed(fellBack)
        assertEquals(TunnelStatus.TRYING, parser.status())
        parser.punch()
        assertEquals(TunnelStatus.DIRECT, parser.status())
    }

    @Test
    fun `a direct tunnel that drops tries again, then falls back to the server`() {
        val parser = trying()
        parser.punch()
        now += 600_000
        parser.feed(checkFailed)
        assertEquals("dropped: frpc punches again", TunnelStatus.TRYING, parser.status())
        now += TRY_DIRECT_MS
        parser.expire()
        assertEquals(VisitorState(TunnelStatus.RELAYED, directFailed = true), parser.state())
    }

    @Test
    fun `a dropped tunnel punched again is direct again`() {
        val parser = trying()
        parser.punch()
        parser.feed(fellBack)
        assertEquals(TunnelStatus.TRYING, parser.status())
        parser.punch()
        assertEquals(TunnelStatus.DIRECT, parser.status())
    }

    @Test
    fun `quiet retries while through the server change nothing until one works`() {
        val parser = trying()
        parser.feed(holeFailed)
        assertFalse(parser.feed(checkFailed))
        assertFalse(parser.feed(fellBack))
        parser.feed(prepared)
        assertFalse(parser.feed(holeFailed))
        assertEquals(TunnelStatus.RELAYED, parser.status())
        parser.punch()
        assertEquals(TunnelStatus.DIRECT, parser.status())
    }

    @Test
    fun `retry direct - the renamed xtcp visitor starts trying again, the old name is ignored`() {
        val parser = trying()
        parser.feed(holeFailed)
        val retried = g1.copy(attempt = 1)
        parser.reconfigure(listOf(retried), restarted = listOf("g1-xtcp-visitor-1"))
        assertEquals(TunnelStatus.CONNECTING, parser.status())
        parser.feed(startSuccess)
        assertEquals(TunnelStatus.TRYING, parser.status())
        // A late line of the closed visitor.
        parser.feed(holeFailed)
        assertEquals(TunnelStatus.TRYING, parser.status())
        parser.punch(punched.replace("[g1-xtcp-visitor]", "[g1-xtcp-visitor-1]"))
        assertEquals(TunnelStatus.DIRECT, parser.status())
    }

    @Test
    fun `direct off - through the server once started, no attempt, nothing failed`() {
        val parser = trying(g1.copy(direct = false))
        assertEquals(VisitorState(TunnelStatus.RELAYED), parser.state())
        now += TRY_DIRECT_MS
        assertFalse(parser.expire())
    }

    @Test
    fun `switching a tunnel to the server only restarts its stcp visitor`() {
        val parser = trying()
        parser.feed(holeFailed)
        parser.reconfigure(listOf(g1.copy(direct = false)), restarted = listOf("g1-stcp-visitor"))
        parser.feed(startSuccess)
        assertEquals(VisitorState(TunnelStatus.RELAYED), parser.state())
    }

    @Test
    fun `a bind failure of the listening visitor fails it`() {
        val parser = parser(g1)
        parser.feed(login)
        parser.feed(startSuccess)
        parser.feed("2026-10-30 20:31:00.269 [W] [visitor/visitor_manager.go:126] [run] start error: listen tcp 127.0.0.1:40000: bind: address already in use")
        assertEquals(VisitorState(TunnelStatus.FAILED, "start error: listen tcp 127.0.0.1:40000: bind: address already in use"), parser.state())
    }

    @Test
    fun `a relay error inside a visitor line fails only that tunnel`() {
        val parser = parser(g1, g2)
        parser.feed(warn("[g1-stcp-visitor] dialRawVisitorConn error: connect to server error: EOF"))
        assertEquals(TunnelStatus.FAILED, parser.status("g1"))
        assertEquals(TunnelStatus.CONNECTING, parser.status("g2"))
    }

    @Test
    fun `login failure and exit fail every tunnel`() {
        val parser = trying()
        parser.feed("2026-10-30 20:30:23.396 [W] [client/service.go:319] connect to server error: dial tcp: lookup relay: no such host")
        assertEquals(TunnelStatus.FAILED, parser.status())
        parser.feed(login)
        assertEquals(TunnelStatus.CONNECTING, parser.status())
        parser.exited(1)
        assertEquals(VisitorState(TunnelStatus.FAILED, "frpc stopped (exit code 1)"), parser.state())
    }

    @Test
    fun `a reload keeps the state of a kept tunnel and lines up the added one`() {
        val parser = trying()
        parser.punch()
        parser.reconfigure(listOf(g1, g2), restarted = listOf("g2-stcp-visitor", "g2-xtcp-visitor"))
        assertEquals(TunnelStatus.DIRECT, parser.status())
        assertEquals(TunnelStatus.CONNECTING, parser.status("g2"))
        // frpc starts only g2's visitors: stcp, then xtcp.
        parser.feed(startSuccess)
        parser.feed(startSuccess)
        assertEquals(TunnelStatus.TRYING, parser.status("g2"))
        assertEquals(TunnelStatus.DIRECT, parser.status())
    }

    @Test
    fun `a reload drops a removed tunnel`() {
        val parser = parser(g1, g2)
        parser.reconfigure(listOf(g2), restarted = emptyList())
        assertEquals(setOf("g2"), parser.snapshot().keys)
    }

    @Test
    fun `frpc's own retry of a visitor waits for a login's start lines to be lined up`() {
        val parser = parser(g1, g2)
        parser.feed(login)
        // Lined up: g1 stcp, g1 xtcp, g2 stcp, g2 xtcp. A retry line must not reorder them.
        parser.feed("2026-10-30 20:31:00.300 [I] [visitor/visitor_manager.go:93] [run] try to start visitor [g2-stcp-visitor]")
        repeat(2) { parser.feed(startSuccess) }
        assertEquals(TunnelStatus.TRYING, parser.status("g1"))
        repeat(2) { parser.feed(startSuccess) }
        assertEquals(TunnelStatus.TRYING, parser.status("g2"))
        // Nothing lined up: the retry line names the visitor that starts next.
        parser.feed("2026-10-30 20:31:10.300 [I] [visitor/visitor_manager.go:93] [run] try to start visitor [g2-xtcp-visitor]")
        parser.feed("2026-10-30 20:31:10.301 [W] [visitor/visitor_manager.go:126] [run] start error: listen tcp 127.0.0.1:40001: bind: address already in use")
        assertEquals(TunnelStatus.FAILED, parser.status("g2"))
        assertEquals(TunnelStatus.TRYING, parser.status("g1"))
    }

    @Test
    fun `a carried window does not outlive its attempt`() {
        val parser = trying()
        parser.feed(warn("[g1-xtcp-visitor] nathole precheck error: xtcp server for [g1-xtcp] doesn't exist"))
        // The automatic retry's reload carries the window...
        parser.reconfigure(listOf(g1.copy(attempt = 1)), restarted = listOf("g1-xtcp-visitor-1"))
        // ...but the tunnel ends through the server before its visitor starts again.
        parser.feed(warn("[g1-stcp-visitor] dialRawVisitorConn error: connect to server error: EOF"))
        assertEquals(TunnelStatus.FAILED, parser.status())
        now += 60_000
        // A later retry is a fresh attempt with a full window.
        parser.reconfigure(listOf(g1.copy(attempt = 2)), restarted = listOf("g1-xtcp-visitor-2"))
        parser.feed(startSuccess)
        assertEquals(TunnelStatus.TRYING, parser.status())
        now += TRY_DIRECT_MS - 1
        assertFalse(parser.expire())
    }
}
