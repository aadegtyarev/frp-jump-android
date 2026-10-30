package ru.adsrv.frpjump.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackGuardTest {
    @Test
    fun tunnelsOnLoopbackStayInApp() {
        listOf(
            "http://127.0.0.1:40001/",
            "http://127.0.0.1:40001/#!/dashboards/1?x={y}",
            "https://127.0.0.1:40002/login",
            "http://localhost:40001",
            "HTTP://LOCALHOST:40001/",
            "http://[::1]:40001/",
            "http://user:pw@127.0.0.1:40001/",
        ).forEach { assertEquals(it, WebNavigation.IN_APP, webNavigationFor(it)) }
    }

    @Test
    fun pageLocalSchemesStayInApp() {
        listOf("about:blank", "blob:http://127.0.0.1:40001/abc", "data:text/html,hi").forEach {
            assertEquals(it, WebNavigation.IN_APP, webNavigationFor(it))
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
            "http://evil.example\\@127.0.0.1/",
            "http://10.0.0.1:80/",
            "ftp://127.0.0.1/",
            "mailto:info@wirenboard.com",
            "tel:+70000000000",
            "intent://scan#Intent;scheme=zxing;end",
            "127.0.0.1:40001",
            "",
        ).forEach { assertEquals(it, WebNavigation.EXTERNAL, webNavigationFor(it)) }
    }

    @Test
    fun downloadsAreOnlyTakenFromLoopbackHttp() {
        assertTrue(isLoopbackHttpUrl("http://127.0.0.1:40001/backup.tar.gz"))
        assertFalse(isLoopbackHttpUrl("blob:http://127.0.0.1:40001/abc"))
        assertFalse(isLoopbackHttpUrl("https://example.com/file"))
    }
}
