package ru.adsrv.frpjump.tunnel

import org.junit.Assert.assertEquals
import org.junit.Test

/** Lines as frpc 0.70 prints them (caller and run id prefixes included). */
class FrpcLogParserTest {
    private fun line(text: String) = "2026-10-30 12:00:00.000 [I] [client/x.go:1] [runid] $text"

    private fun started(relayOnly: Boolean = false) = FrpcLogParser(listOf("g1"), relayOnly).apply {
        feed(line("login to server success, get run id [runid]"))
        if (!relayOnly) feed(line("start visitor success"))
        feed(line("start visitor success"))
    }

    private fun FrpcLogParser.status() = snapshot().getValue("g1").status

    @Test
    fun `connecting until the listening visitor starts, then ready`() {
        val parser = FrpcLogParser(listOf("g1"), relayOnly = false)
        assertEquals(TunnelStatus.CONNECTING, parser.status())
        assertEquals(TunnelStatus.READY, started().status())
    }

    @Test
    fun `hole punch success is direct, fallback is relayed`() {
        val parser = started()
        parser.feed(line("[g1-xtcp-visitor] establishing nat hole connection successful, sid [1], remoteAddr [1.2.3.4:5]"))
        assertEquals(TunnelStatus.DIRECT, parser.status())
        parser.feed("\u001B[1;31m" + line("[g1-xtcp-visitor] open tunnel error: open tunnel timeout") + "\u001B[0m")
        assertEquals(TunnelStatus.RELAYED, parser.status())
    }

    @Test
    fun `relay only is relayed once started`() {
        assertEquals(TunnelStatus.RELAYED, started(relayOnly = true).status())
    }

    @Test
    fun `a bind failure of the listening visitor fails it`() {
        val parser = FrpcLogParser(listOf("g1"), relayOnly = false)
        parser.feed(line("login to server success, get run id [runid]"))
        parser.feed(line("start visitor success"))
        parser.feed(line("start error: listen tcp 127.0.0.1:40000: bind: address already in use"))
        assertEquals(VisitorState(TunnelStatus.FAILED, "start error: listen tcp 127.0.0.1:40000: bind: address already in use"), parser.snapshot()["g1"])
    }

    @Test
    fun `a relay error inside a visitor line fails only that tunnel`() {
        val parser = FrpcLogParser(listOf("g1", "g2"), relayOnly = false)
        parser.feed(line("[g1-stcp-visitor] dialRawVisitorConn error: connect to server error: EOF"))
        assertEquals(TunnelStatus.FAILED, parser.snapshot().getValue("g1").status)
        assertEquals(TunnelStatus.CONNECTING, parser.snapshot().getValue("g2").status)
    }

    @Test
    fun `login failure and exit fail every tunnel`() {
        val parser = started()
        parser.feed(line("connect to server error: dial tcp: lookup relay: no such host"))
        assertEquals(TunnelStatus.FAILED, parser.status())
        parser.feed(line("login to server success, get run id [runid]"))
        assertEquals(TunnelStatus.CONNECTING, parser.status())
        parser.exited(1)
        assertEquals(VisitorState(TunnelStatus.FAILED, "frpc stopped (exit code 1)"), parser.snapshot()["g1"])
    }
}
