package ru.adsrv.frpjump.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WebBackTest {
    @Test
    fun `full screen is left first, whatever the history`() {
        assertEquals(WebBack.LEAVE_FULLSCREEN, webBackFor(fullscreen = true, canGoBack = true, pageShown = true))
        assertEquals(WebBack.LEAVE_FULLSCREEN, webBackFor(fullscreen = true, canGoBack = false, pageShown = true))
    }

    @Test
    fun `with history Back goes to the previous page`() {
        assertEquals(WebBack.PAGE_BACK, webBackFor(fullscreen = false, canGoBack = true, pageShown = true))
    }

    @Test
    fun `without history, or with the page hidden, Back closes`() {
        assertEquals(WebBack.CLOSE, webBackFor(fullscreen = false, canGoBack = false, pageShown = true))
        assertEquals(WebBack.CLOSE, webBackFor(fullscreen = false, canGoBack = true, pageShown = false))
    }

    private val root = "http://127.0.0.1:40002/"
    private val dashboard = root + "#/dashboards/dashboard1"
    private val devices = root + "#/devices"
    private val history = root + "#/history"

    @Test
    fun `back to a redirecting first entry closes`() {
        // homeui: [/, dashboard]; / pushes the dashboard again the moment it shows.
        val bounce = BackBounce()
        bounce.leaving(1, dashboard)
        assertNull(bounce.landed(0, root, now = 10))
        assertEquals(Bounce.CLOSE, bounce.landed(1, dashboard, now = 20))
        assertFalse(bounce.armed)
    }

    @Test
    fun `a push of the url Back left steps over the redirect`() {
        val bounce = BackBounce()
        bounce.leaving(3, history)
        assertNull(bounce.landed(2, root, now = 10))
        assertEquals(Bounce.SKIP_REDIRECT, bounce.landed(3, history, now = 20))
    }

    @Test
    fun `a push of another url right after the landing is no bounce`() {
        val bounce = BackBounce()
        bounce.leaving(2, history)
        assertNull(bounce.landed(1, devices, now = 10))
        // The owner tapped a link at once.
        assertNull(bounce.landed(2, dashboard, now = 20))
        assertFalse(bounce.armed)
    }

    @Test
    fun `forward or another page asked for disarms it`() {
        val bounce = BackBounce()
        bounce.leaving(2, history)
        assertNull(bounce.landed(1, devices, now = 10))
        bounce.cancel()
        // Forward lands on the url Back left: not a bounce.
        assertNull(bounce.landed(2, history, now = 20))
    }

    @Test
    fun `a second back replaces the first`() {
        val bounce = BackBounce()
        bounce.leaving(3, history)
        bounce.leaving(2, devices)
        assertNull(bounce.landed(1, root, now = 10))
        assertEquals(Bounce.SKIP_REDIRECT, bounce.landed(2, devices, now = 20))
        // The first back's url is no bounce any more.
        bounce.leaving(3, history)
        bounce.leaving(2, devices)
        assertNull(bounce.landed(1, root, now = 30))
        assertNull(bounce.landed(3, history, now = 40))
    }

    @Test
    fun `the cap runs from the landing, not the press`() {
        val bounce = BackBounce(capMs = 5000)
        bounce.leaving(1, dashboard)
        // A slow landing (a page loading for a while) still counts.
        assertNull(bounce.landed(0, root, now = 8000))
        assertEquals(Bounce.CLOSE, bounce.landed(1, dashboard, now = 9000))
        // Long after the landing nothing counts.
        bounce.leaving(1, dashboard)
        assertNull(bounce.landed(0, root, now = 10000))
        assertNull(bounce.landed(1, dashboard, now = 15001))
        // Nothing pending: pushes are just navigation.
        assertNull(BackBounce().landed(4, history, now = 0))
    }
}
