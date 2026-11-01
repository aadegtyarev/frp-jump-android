package ru.adsrv.frpjump.web

import org.junit.Assert.assertEquals
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

    @Test
    fun `back to a redirecting first entry closes`() {
        // homeui: [/, #/dashboards/dashboard1]; / pushes the dashboard again the moment it shows.
        val bounce = BackBounce()
        bounce.leaving(1, now = 0)
        assertNull(bounce.landed(0, now = 10))
        assertEquals(Bounce.CLOSE, bounce.landed(1, now = 20))
    }

    @Test
    fun `back to a redirect with pages before it steps over it`() {
        val bounce = BackBounce()
        bounce.leaving(3, now = 0)
        assertNull(bounce.landed(2, now = 10))
        assertEquals(Bounce.SKIP_REDIRECT, bounce.landed(3, now = 20))
    }

    @Test
    fun `an ordinary back, and what comes later, is no bounce`() {
        val bounce = BackBounce(windowMs = 1000)
        bounce.leaving(2, now = 0)
        assertNull(bounce.landed(1, now = 10))
        // A link followed well after the back.
        assertNull(bounce.landed(2, now = 5000))
        // Nothing pending: pushes are just navigation.
        assertNull(BackBounce().landed(4, now = 0))
    }
}
