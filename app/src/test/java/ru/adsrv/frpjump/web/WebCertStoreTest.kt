package ru.adsrv.frpjump.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage
import java.io.IOException

class WebCertStoreTest {
    private val a = "AA:BB"
    private val b = "CC:DD"

    @Test
    fun `first visit asks, the pinned one opens, another one asks again showing the old`() {
        assertEquals(CertCheck.Unknown, certCheck(pinned = null, presented = a, forTunnel = true))
        assertEquals(CertCheck.Trusted, certCheck(pinned = a, presented = a, forTunnel = true))
        assertEquals(CertCheck.Changed(a), certCheck(pinned = a, presented = b, forTunnel = true))
    }

    @Test
    fun `other pages and unreadable certificates are refused without asking`() {
        assertEquals(CertCheck.Refused, certCheck(pinned = a, presented = a, forTunnel = false))
        assertEquals(CertCheck.Refused, certCheck(pinned = null, presented = null, forTunnel = true))
    }

    @Test
    fun `pins are per device and target port and survive a restart`() {
        val file = MemoryStorage()
        val store = WebCertStore(file)
        store.pin("wb", 443, a)
        assertEquals(CertCheck.Trusted, WebCertStore(file).check("wb", 443, a, forTunnel = true))
        assertEquals(CertCheck.Unknown, WebCertStore(file).check("wb", 8443, a, forTunnel = true))
        assertEquals(CertCheck.Unknown, WebCertStore(file).check("pc", 443, a, forTunnel = true))
        store.pin("wb", 443, b)
        assertEquals(CertCheck.Changed(b), WebCertStore(file).check("wb", 443, a, forTunnel = true))
    }

    @Test
    fun `a fingerprint is SHA-256 in colon-separated upper-case hex`() {
        val fp = certFingerprint("abc".toByteArray())
        assertEquals("BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD", fp)
    }

    private class Handler : CertAnswer {
        val log = mutableListOf<String>()
        override fun proceed() { log += "proceed" }
        override fun cancel() { log += "cancel" }
    }

    @Test
    fun `a saved trust lets the page open`() = runBlocking {
        val h = Handler()
        assertNull(answerCertTrust(listOf(h)) { WebCertStore(MemoryStorage()).pin("wb", 443, a) })
        assertEquals(listOf("proceed"), h.log)
    }

    @Test
    fun `a failing save leaves the page closed, cancels the handler and reports the failure`() = runBlocking {
        val h = Handler()
        val failure = answerCertTrust(listOf(h)) { throw IOException("/data/x: disk full") }
        assertNotNull(failure)
        assertEquals(listOf("cancel"), h.log)
    }

    @Test
    fun `cancellation during the save propagates and never opens the page`() {
        val h = Handler()
        try {
            runBlocking { answerCertTrust(listOf(h)) { throw CancellationException("stop") } }
            fail("swallowed")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
        assertEquals(listOf("cancel"), h.log)
    }
}
