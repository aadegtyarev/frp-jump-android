package ru.adsrv.frpjump.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackGuardTest {
    private val tunnel = 40001

    @Test
    fun thisTunnelStaysInApp() {
        listOf(
            "http://127.0.0.1:40001/",
            "http://127.0.0.1:40001/#!/dashboards/1?x={y}",
            "https://127.0.0.1:40001/login",
            "http://localhost:40001",
            "HTTP://LOCALHOST:40001/",
            "http://[::1]:40001/",
            "http://user:pw@127.0.0.1:40001/",
            "http://127.0.0.1:040001/",
        ).forEach { assertEquals(it, WebNavigation.IN_APP, webNavigationFor(it, tunnel)) }
    }

    @Test
    fun otherLoopbackPortsAreBlocked() {
        listOf(
            "http://127.0.0.1:40002/",
            "http://127.0.0.1/",
            "https://127.0.0.1/",
            "http://localhost:8080/",
            "http://[::1]:5555/",
            "http://127.0.0.1:400010/",
            "http://127.0.0.1:4000x/",
            "http://[::1]x/",
        ).forEach { assertEquals(it, WebNavigation.BLOCKED, webNavigationFor(it, tunnel)) }
    }

    @Test
    fun pageLocalSchemesStayInApp() {
        listOf("about:blank", "blob:http://127.0.0.1:40001/abc", "data:text/html,hi").forEach {
            assertEquals(it, WebNavigation.IN_APP, webNavigationFor(it, tunnel))
        }
    }

    @Test
    fun everythingElseGoesExternal() {
        listOf(
            "http://wirenboard.com/",
            "https://example.com/127.0.0.1",
            "http://127.0.0.1.evil.example/",
            "http://localhost.evil.example/",
            "http://127.0.0.1@evil.example/",
            "http://evil.example\\@127.0.0.1:40001/",
            "http://10.0.0.1:80/",
            "ftp://127.0.0.1:40001/",
            "mailto:info@wirenboard.com",
            "tel:+70000000000",
            "intent://scan#Intent;scheme=zxing;end",
            "127.0.0.1:40001",
            "",
        ).forEach { assertEquals(it, WebNavigation.EXTERNAL, webNavigationFor(it, tunnel)) }
    }

    @Test
    fun portsDefaultBySchemeAndMustBeNumbers() {
        assertEquals(80, loopbackHttpPort("http://localhost/"))
        assertEquals(443, loopbackHttpPort("https://localhost"))
        assertEquals(40001, loopbackHttpPort("http://[::1]:40001/x"))
        assertEquals(0, loopbackHttpPort("http://127.0.0.1:+40001/"))
        assertEquals(0, loopbackHttpPort("http://127.0.0.1:0/"))
        assertNull(loopbackHttpPort("http://example.com:40001/"))
    }

    @Test
    fun downloadsAreOnlyTakenFromThisTunnel() {
        assertTrue(isTunnelUrl("http://127.0.0.1:40001/backup.tar.gz", tunnel))
        assertFalse(isTunnelUrl("http://127.0.0.1:40002/backup.tar.gz", tunnel))
        assertFalse(isTunnelUrl("blob:http://127.0.0.1:40001/abc", tunnel))
        assertFalse(isTunnelUrl("https://example.com/file", tunnel))
        assertTrue(isLoopbackHttpUrl("http://127.0.0.1:40002/"))
    }
}
