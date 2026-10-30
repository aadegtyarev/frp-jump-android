package ru.adsrv.frpjump.api

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class RedirectMessageTest {
    private val request = Request.Builder().url("https://way.example.com/api/agent/enroll").build()

    @Test
    fun namesTheTargetHostOnly() {
        assertEquals(
            "way.example.com redirects to evil.example.net; not following it. Check the server URL.",
            redirectMessage(request, "https://evil.example.net/path?token=secret"),
        )
    }

    @Test
    fun relativeAndMissingLocations() {
        assertEquals(
            "way.example.com redirects to way.example.com; not following it. Check the server URL.",
            redirectMessage(request, "/login"),
        )
        assertEquals(
            "way.example.com redirects to an unknown address; not following it. Check the server URL.",
            redirectMessage(request, null),
        )
    }
}
