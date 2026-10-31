package ru.adsrv.frpjump.web

import org.junit.Assert.assertEquals
import org.junit.Test

class WebPageMemoryTest {
    private val start = "http://127.0.0.1:20001/"

    @Test
    fun `nothing remembered starts at the root`() {
        assertEquals(start, WebPageMemory().startFor("p1", "dev-a", 20001, start))
    }

    @Test
    fun `the same tunnel opens on its last page`() {
        val pages = WebPageMemory()
        pages.remember("p1", "dev-a", 20001, "http://127.0.0.1:20001/")
        pages.remember("p1", "dev-a", 20001, "http://127.0.0.1:20001/#!/devices?x=1")
        assertEquals("http://127.0.0.1:20001/#!/devices?x=1", pages.startFor("p1", "dev-a", 20001, start))
    }

    @Test
    fun `another profile, device or local port starts at the root`() {
        val pages = WebPageMemory()
        pages.remember("p1", "dev-a", 20001, "http://127.0.0.1:20001/rules")
        assertEquals(start, pages.startFor("p2", "dev-a", 20001, start))
        assertEquals(start, pages.startFor("p1", "dev-b", 20001, start))
        assertEquals("http://127.0.0.1:20002/", pages.startFor("p1", "dev-a", 20002, "http://127.0.0.1:20002/"))
    }

    @Test
    fun `pages off the tunnel are not remembered`() {
        val pages = WebPageMemory()
        pages.remember("p1", "dev-a", 20001, "http://127.0.0.1:20001/rules")
        pages.remember("p1", "dev-a", 20001, "chrome-error://chromewebdata/")
        pages.remember("p1", "dev-a", 20001, "http://127.0.0.1:20002/other-tunnel")
        pages.remember("p1", "dev-a", 20001, "https://example.com/")
        pages.remember("p1", "dev-a", 20001, "about:blank")
        assertEquals("http://127.0.0.1:20001/rules", pages.startFor("p1", "dev-a", 20001, start))
    }

    @Test
    fun `a page of another scheme starts over`() {
        val pages = WebPageMemory()
        pages.remember("p1", "dev-a", 20001, "https://127.0.0.1:20001/settings")
        // "Open over HTTP" since: the HTTPS page would fail.
        assertEquals(start, pages.startFor("p1", "dev-a", 20001, start))
        assertEquals("https://127.0.0.1:20001/settings", pages.startFor("p1", "dev-a", 20001, "https://127.0.0.1:20001/"))
    }

    @Test
    fun `forgetting a profile drops its pages only`() {
        val pages = WebPageMemory()
        pages.remember("p1", "dev-a", 20001, "http://127.0.0.1:20001/a")
        pages.remember("p2", "dev-a", 20003, "http://127.0.0.1:20003/b")
        pages.forgetProfile("p1")
        assertEquals(start, pages.startFor("p1", "dev-a", 20001, start))
        assertEquals("http://127.0.0.1:20003/b", pages.startFor("p2", "dev-a", 20003, "http://127.0.0.1:20003/"))
    }
}
