package ru.adsrv.frpjump.web

import android.webkit.WebViewClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainHttpTest {
    private val url = "https://127.0.0.1:40000/"
    private val handshake = WebViewClient.ERROR_FAILED_SSL_HANDSHAKE

    @Test
    fun `a protocol error on the tunnel's https page is plain http`() {
        assertTrue(answersInPlainHttp(handshake, "net::ERR_SSL_PROTOCOL_ERROR", url, 40000, sslSeen = false))
        assertTrue(answersInPlainHttp(handshake, "", url, 40000, sslSeen = false))
    }

    @Test
    fun `not after a certificate question or refusal, nor for other handshake errors`() {
        assertFalse(answersInPlainHttp(handshake, "net::ERR_SSL_PROTOCOL_ERROR", url, 40000, sslSeen = true))
        assertFalse(answersInPlainHttp(handshake, "net::ERR_SSL_VERSION_OR_CIPHER_MISMATCH", url, 40000, sslSeen = false))
    }

    @Test
    fun `not for other codes, http pages or other ports`() {
        assertFalse(answersInPlainHttp(WebViewClient.ERROR_CONNECT, "net::ERR_CONNECTION_CLOSED", url, 40000, sslSeen = false))
        assertFalse(answersInPlainHttp(handshake, "", "http://127.0.0.1:40000/", 40000, sslSeen = false))
        assertFalse(answersInPlainHttp(handshake, "", "https://127.0.0.1:40001/", 40000, sslSeen = false))
    }
}
