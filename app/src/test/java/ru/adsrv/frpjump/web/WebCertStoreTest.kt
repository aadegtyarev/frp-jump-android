package ru.adsrv.frpjump.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.ssh.WriteFailureKind
import ru.adsrv.frpjump.ssh.hostKeyWriteFailure
import ru.adsrv.frpjump.store.TextStorage
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun `cancelling the caller while the save blocks never opens the page`() = runBlocking {
        val h = Handler()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            answerCertTrust(listOf(h)) { started.countDown(); release.await() }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("cancel"), h.log)
    }

    @Test
    fun `a cancellation thrown from the save is rethrown, not returned as a failure`() {
        val h = Handler()
        try {
            runBlocking { answerCertTrust(listOf(h)) { throw CancellationException("stop") } }
            fail("swallowed")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
        assertEquals(listOf("cancel"), h.log)
    }

    @Test
    fun `an Error in the save still refuses the waiting request`() {
        val h = Handler()
        try {
            runBlocking { answerCertTrust(listOf(h)) { throw StackOverflowError() } }
            fail("swallowed")
        } catch (_: StackOverflowError) {
        }
        assertEquals(listOf("cancel"), h.log)
    }

    @Test
    fun `a failed pin trusts nothing, so the next visit asks again`() {
        val store = WebCertStore(BrokenStorage())
        assertNotNull(hostKeyWriteFailure { store.pin("wb", 443, a) })
        assertEquals(CertCheck.Unknown, store.check("wb", 443, a, forTunnel = true))
        assertNull(store.pinned("wb", 443))
    }

    private class BrokenStorage : TextStorage {
        override fun read(): String? = null
        override fun write(text: String) = throw IOException("disk full")
        override fun delete() {}
    }

    @Test
    fun `the banner follows refuse, save failure, save success and try again`() {
        val kind = WriteFailureKind.NO_SPACE
        assertEquals(CertBanner(refused = true), CertBanner().refusedNow())
        val failed = CertBanner().saveFailed(kind)
        assertEquals(CertBanner(refused = true, saveFailure = kind), failed)
        // A refusal after a failed save is a plain refusal, not the old save failure.
        assertEquals(CertBanner(refused = true), failed.refusedNow())
        // A later successful Trust shows nothing.
        assertEquals(CertBanner(), failed.saved())
        assertEquals(CertBanner(), failed.tryAgain())
        assertEquals(CertBanner(), CertBanner().refusedNow().tryAgain())
    }

    @Test
    fun `WebScreen proceeds only a trusted certificate or the adapter of a saved answer`() {
        val lines = java.io.File("src/main/java/ru/adsrv/frpjump/ui/web/WebScreen.kt").readLines()
            .filter { it.contains(".proceed()") }.map { it.trim() }
        assertEquals(
            listOf("CertCheck.Trusted -> handler.proceed()", "val answers = q.handlers.map { h -> object : CertAnswer { override fun proceed() = h.proceed(); override fun cancel() = h.cancel() } }"),
            lines,
        )
    }
}
