package ru.adsrv.frpjump.assistant

import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.SideEffectAdmission
import java.util.concurrent.atomic.AtomicReference

class PublicWebTransportTest {
    /** Negative observation: a caller that wrongly finishes before its blocking call returns would do so well inside this window. */
    private suspend fun assertHeld(job: Job) = assertNull("the caller must still wait for the blocking call", withTimeoutOrNull(300) { job.join() })
    private val attempt: (String) -> Unit = { }
    private fun dns(vararg ips: String) = object : Dns {
        override fun lookup(hostname: String) = ips.map { InetAddress.getByName(it) }
    }
    private fun client(action: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { action(it.request()) }.build()
    private fun response(request: Request, code: Int = 200, body: ResponseBody = "document".toResponseBody("text/plain".toMediaType()), location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic").body(body)
            .apply { location?.let { header("Location", it) } }.build()

    @Test fun `URL and address policy rejects local aliases ports schemes and all special ranges`() {
        for (url in listOf("http://public.org", "https://user@public.org", "https://public.org:444", "file:///tmp/a", "https://localhost", "https://a.local", "https://local", "https://a.onion")) {
            assertThrows(url, WebException::class.java) { PublicWebPolicy.url(url) }
        }
        val denied = listOf("0.0.0.0", "10.0.0.1", "127.0.0.1", "100.64.0.1", "169.254.1.1", "172.16.0.1", "192.168.1.1", "192.0.0.9", "192.0.2.1", "192.88.99.1", "198.18.1.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::", "::1", "fe80::1", "fc00::1", "ff02::1", "::ffff:127.0.0.1", "64:ff9b::a00:1", "2001:db8::1", "2001::1", "2002:7f00:1::1", "3fff::1")
        denied.forEach { assertFalse(it, PublicWebPolicy.publicAddress(InetAddress.getByName(it))) }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach { assertTrue(it, PublicWebPolicy.publicAddress(InetAddress.getByName(it))) }
        assertThrows(WebException::class.java) { PublicWebPolicy.resolve("public.org", dns("8.8.8.8", "127.0.0.1")) }
    }

    @Test fun `manual redirects are revalidated charged and bounded with no credentials or state`() = runBlocking {
        val seen = mutableListOf<Request>()
        val client = client { request -> seen += request; response(request, 302, location = "https://public.org/next") }
        val budget = WebBudget()
        try { PublicWebTransport(dns("8.8.8.8"), client).fetch("https://public.org/start", budget, attempt); fail() }
        catch (failure: WebException) { assertEquals(WebFailure.LIMIT, failure.reason) }
        assertEquals(4, seen.size); assertEquals(4, budget.requests)
        assertTrue(seen.all { it.method == "GET" && it.header("Authorization") == null && it.header("Cookie") == null && it.header("Referer") == null })
        var resolutions = 0
        val rebinding = object : Dns { override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName(if (++resolutions == 1) "8.8.8.8" else "127.0.0.1")) }
        val b = WebBudget()
        try { PublicWebTransport(rebinding, client).fetch("https://public.org", b, attempt); fail() }
        catch (failure: WebException) { assertEquals(WebFailure.URL, failure.reason) }
        assertEquals(1, b.requests)
    }

    @Test fun `public requests use browser headers on every hop with system language and no credentials`() = runBlocking {
        val previous = Locale.getDefault()
        try {
            for ((locale, language) in listOf(Locale.US to "en-US", Locale.forLanguageTag("ru-RU") to "ru-RU", Locale.ROOT to "en")) {
                Locale.setDefault(locale)
                val seen = mutableListOf<Request>()
                val transport = PublicWebTransport(dns("8.8.8.8"), client { request ->
                    seen += request
                    if (seen.size == 1) response(request, 302, location = "https://other.org/document") else response(request)
                })
                transport.fetch("https://public.org/start", WebBudget(), attempt)
                assertEquals(2, seen.size)
                seen.forEach { request ->
                    val agent = request.header("User-Agent").orEmpty()
                    assertTrue("Explicit browser-compatible User-Agent required", agent.startsWith("Mozilla/5.0 "))
                    assertTrue(agent.contains("AppleWebKit/") && agent.contains("Chrome/") && agent.contains("Safari/"))
                    val accept = request.header("Accept").orEmpty()
                    assertTrue(accept.startsWith("text/html, application/xhtml+xml"))
                    for (type in listOf("text/plain", "text/markdown", "image/png", "image/jpeg")) assertTrue(type, accept.contains(type))
                    assertFalse(accept.contains("image/avif")); assertFalse(accept.contains("image/webp"))
                    assertEquals(language, request.header("Accept-Language"))
                    for (header in listOf("Authorization", "Cookie", "Referer")) assertNull(header, request.header(header))
                }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `decoded streaming body is capped before publication even with unknown length and releases body`() = runBlocking {
        var closed = false
        val body = object : ResponseBody() {
            val source = Buffer().write(ByteArray(1_048_577))
            override fun contentType() = "text/html".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = source
            override fun close() { closed = true; super.close() }
        }
        try { PublicWebTransport(dns("8.8.8.8"), client { response(it, body = body) }).fetch("https://public.org", WebBudget(), attempt); fail() }
        catch (failure: WebException) { assertEquals(WebFailure.TOO_LARGE, failure.reason) }
        assertTrue(closed)
    }

    @Test fun `revoked dispatch never starts exchange or consumes request budget`() = runBlocking {
        var calls = 0; var lookups = 0
        val counting = object : Dns { override fun lookup(hostname: String): List<InetAddress> { lookups++; return listOf(InetAddress.getByName("8.8.8.8")) } }
        val transport = PublicWebTransport(counting, client { calls++; response(it) })
        // Refused at the first (DNS) check: no lookup, no exchange, no attempt.
        var attempts = 0
        val budget = WebBudget()
        try { withContext(SideEffectAdmission { false }) { transport.fetch("https://public.org", budget) { attempts++ } }; fail() }
        catch (_: CancellationException) { }
        assertEquals(0, lookups); assertEquals(0, calls); assertEquals(0, budget.requests); assertEquals(0, attempts)
        // Refused at the HTTP check: the lookup ran, nothing started, no HTTP attempt, no budget.
        val categories = ArrayList<String>()
        try { withContext(SideEffectAdmission { it != "public-dispatch" }) { transport.fetch("https://public.org", budget) { categories += it } }; fail() }
        catch (_: CancellationException) { }
        assertEquals(1, lookups); assertEquals(0, calls); assertEquals(0, budget.requests); assertEquals(listOf("dns-dispatch"), categories)
    }

    @Test fun `admission is asked before DNS and before each HTTP start then attempt then budget`() = runBlocking {
        val events = ArrayList<String>()
        val budget = WebBudget()
        var served = 0
        val transport = PublicWebTransport(dns("8.8.8.8"), client { request ->
            if (++served == 1) response(request, 302, location = "https://public.org/next") else response(request)
        })
        withContext(SideEffectAdmission { events += "admit:$it"; true }) {
            transport.fetch("https://public.org/start", budget) { events += "attempt:$it:${budget.requests}" }
        }
        assertEquals(listOf(
            "admit:dns-dispatch", "attempt:dns-dispatch:0", "admit:public-dispatch", "attempt:public-dispatch:0",
            "admit:dns-dispatch", "attempt:dns-dispatch:1", "admit:public-dispatch", "attempt:public-dispatch:1"), events)
        assertEquals(2, budget.requests)
    }

    @Test fun `attempt quota failure stops before the exchange and the budget`() = runBlocking {
        var calls = 0
        val budget = WebBudget()
        val categories = ArrayList<String>()
        try {
            PublicWebTransport(dns("8.8.8.8"), client { calls++; response(it) }).fetch("https://public.org", budget) { category ->
                categories += category
                if (category == "public-dispatch") webFail(WebFailure.LIMIT)
            }
            fail()
        } catch (failure: WebException) { assertEquals(WebFailure.LIMIT, failure.reason) }
        assertEquals(listOf("dns-dispatch", "public-dispatch"), categories)
        assertEquals(0, calls); assertEquals(0, budget.requests)
    }

    @Test fun `non IO and IO failures of the exchange map to network`() = runBlocking {
        for (failure in listOf<() -> Throwable>({ IllegalStateException("private-detail") }, { java.io.IOException("synthetic") })) {
            try { PublicWebTransport(dns("8.8.8.8"), client { throw failure() }).fetch("https://public.org", WebBudget(), attempt); fail() }
            catch (thrown: WebException) { assertEquals(WebFailure.NETWORK, thrown.reason) }
        }
    }

    @Test fun `cancellation holds transport until the exchange thread exits`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val cancelledSeen = AtomicReference<Boolean>(); val canceled = CountDownLatch(1)
        val template = OkHttpClient.Builder().eventListener(object : EventListener() { override fun canceled(call: Call) { canceled.countDown() } })
            .addInterceptor { chain ->
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); cancelledSeen.set(chain.call().isCanceled()); response(chain.request())
            }.build()
        val transport = PublicWebTransport(dns("8.8.8.8"), template)
        val job = launch(Dispatchers.Default) { transport.fetch("https://public.org", WebBudget(), attempt) }
        assertTrue(entered.await(2, TimeUnit.SECONDS)); job.cancel()
        assertTrue("Call.cancel() must reach the exact exchange promptly", canceled.await(3, TimeUnit.SECONDS)); assertHeld(job)
        release.countDown(); job.join(); assertTrue(job.isCompleted)
        assertEquals(true, cancelledSeen.get())
    }

    @Test fun `cancel during DNS holds the transport until the lookup returns and sends nothing`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var calls = 0
        val latched = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); return listOf(InetAddress.getByName("8.8.8.8")) }
        }
        val budget = WebBudget()
        val thrown = AtomicReference<Throwable>()
        val job = launch(Dispatchers.Default) {
            try { PublicWebTransport(latched, client { calls++; response(it) }).fetch("https://public.org", budget, attempt) } catch (failure: Throwable) { thrown.set(failure); throw failure }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS)); job.cancel(); assertHeld(job)
        release.countDown(); job.join()
        assertTrue(thrown.get() is CancellationException)
        assertEquals(0, calls); assertEquals(0, budget.requests)
    }

    @Test fun `cancel during body read holds the transport until the read returns`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val stalled = object : ResponseBody() {
            private val stream = object : okio.Source {
                override fun read(sink: Buffer, byteCount: Long): Long { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); return -1 }
                override fun timeout() = okio.Timeout.NONE
                override fun close() { }
            }.buffer()
            override fun contentType() = "text/plain".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = stream
        }
        val thrown = AtomicReference<Throwable>()
        val job = launch(Dispatchers.Default) {
            try { PublicWebTransport(dns("8.8.8.8"), client { response(it, body = stalled) }).fetch("https://public.org", WebBudget(), attempt) }
            catch (failure: Throwable) { thrown.set(failure); throw failure }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS)); job.cancel(); assertHeld(job)
        release.countDown(); job.join()
        assertTrue(thrown.get().toString(), thrown.get() is CancellationException)
    }

    @Test fun `decompressed stream cap applies before parser and public GET budget refuses a thirteenth request`() = runBlocking {
        val compressed = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(ByteArray(1_048_577)) } }.toByteArray()
        val decoded = object : ResponseBody() {
            private val stream = okio.GzipSource(Buffer().write(compressed)).let { source -> source.buffer() }
            override fun contentType() = "text/plain".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = stream
        }
        try { PublicWebTransport(dns("8.8.8.8"), client { response(it, body = decoded) }).fetch("https://public.org", WebBudget(), attempt); fail() }
        catch (failure: WebException) { assertEquals(WebFailure.TOO_LARGE, failure.reason) }
        var calls = 0
        val transport = PublicWebTransport(dns("8.8.8.8"), client { calls++; response(it) })
        val budget = WebBudget()
        repeat(12) { transport.fetch("https://public.org", budget, attempt) }
        try { transport.fetch("https://public.org", budget, attempt); fail() } catch (failure: WebException) { assertEquals(WebFailure.LIMIT, failure.reason) }
        assertEquals(12, calls)
    }

    @Test fun `WEB-READER-03 public transport preserves declared charset after bounded capture`() = runBlocking {
        val text = "Version / Версия"
        val bytes = text.toByteArray(java.nio.charset.Charset.forName("windows-1251"))
        val transport = PublicWebTransport(dns("8.8.8.8"), client { response(it, body = bytes.toResponseBody("text/plain; charset=windows-1251".toMediaType())) })
        val captured = transport.fetch("https://public.org", WebBudget(), attempt)
        assertEquals("windows-1251", captured.charset)
        assertEquals(text, WebDocuments.page(captured).text)
    }

}
