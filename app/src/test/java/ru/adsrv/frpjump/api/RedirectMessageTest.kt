package ru.adsrv.frpjump.api

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedirectMessageTest {
    private val request = Request.Builder().url("https://way.example.com/api/agent/enroll").build()

    @Test
    fun namesTheTargetHostOnly() {
        val e = redirectException(request, "https://evil.example.net/path?token=secret")
        assertEquals(NetworkException.Reason.REDIRECT, e.reason)
        assertEquals("way.example.com", e.host)
        assertEquals("evil.example.net", e.detail)
        assertEquals("way.example.com redirects to evil.example.net; not following it. Check the server URL.", e.message)
    }

    @Test
    fun relativeAndMissingLocations() {
        assertEquals("way.example.com", redirectException(request, "/login").detail)
        val unknown = redirectException(request, null)
        assertNull(unknown.detail)
        assertEquals("way.example.com redirects to an unknown address; not following it. Check the server URL.", unknown.message)
    }
}
