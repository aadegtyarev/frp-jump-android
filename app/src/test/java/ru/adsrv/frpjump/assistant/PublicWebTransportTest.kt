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

class PublicWebTransportTest {
    private val dispatch: WebDispatch = { _, start -> start() }
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
        try { PublicWebTransport(dns("8.8.8.8"), client).fetch("https://public.org/start", budget, dispatch); fail() }
        catch (failure: WebException) { assertEquals(WebFailure.LIMIT, failure.reason) }
        assertEquals(4, seen.size); assertEquals(4, budget.requests)
        assertTrue(seen.all { it.method == "GET" && it.header("Authorization") == null && it.header("Cookie") == null && it.header("Referer") == null })
        var resolutions = 0
        val rebinding = object : Dns { override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName(if (++resolutions == 1) "8.8.8.8" else "127.0.0.1")) }
        val b = WebBudget()
        try { PublicWebTransport(rebinding, client).fetch("https://public.org", b, dispatch); fail() }
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
                transport.fetch("https://public.org/start", WebBudget(), dispatch)
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
        try { PublicWebTransport(dns("8.8.8.8"), client { response(it, body = body) }).fetch("https://public.org", WebBudget(), dispatch); fail() }
        catch (failure: WebException) { assertEquals(WebFailure.TOO_LARGE, failure.reason) }
        assertTrue(closed)
    }

    @Test fun `revoked dispatch never starts exchange or consumes request budget`() = runBlocking {
        var calls = 0
        val budget = WebBudget()
        try { PublicWebTransport(dns("8.8.8.8"), client { calls++; response(it) }).fetch("https://public.org", budget) { _, _ -> throw CancellationException() }; fail() }
        catch (_: CancellationException) { }
        assertEquals(0, calls); assertEquals(0, budget.requests)
    }

    @Test fun `cancellation holds transport until callback body cleanup exits`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val transport = PublicWebTransport(dns("8.8.8.8"), client { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); response(it) })
        val job = launch(Dispatchers.Default) { transport.fetch("https://public.org", WebBudget(), dispatch) }
        assertTrue(entered.await(2, TimeUnit.SECONDS)); job.cancel(); assertFalse(job.isCompleted)
        release.countDown(); job.join(); assertTrue(job.isCompleted)
    }
    @Test fun `decompressed stream cap applies before parser and public GET budget refuses a thirteenth request`() = runBlocking {
        val compressed = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(ByteArray(1_048_577)) } }.toByteArray()
        val decoded = object : ResponseBody() {
            private val stream = okio.GzipSource(Buffer().write(compressed)).let { source -> source.buffer() }
            override fun contentType() = "text/plain".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = stream
        }
        try { PublicWebTransport(dns("8.8.8.8"), client { response(it, body = decoded) }).fetch("https://public.org", WebBudget(), dispatch); fail() }
        catch (failure: WebException) { assertEquals(WebFailure.TOO_LARGE, failure.reason) }
        var calls = 0
        val transport = PublicWebTransport(dns("8.8.8.8"), client { calls++; response(it) })
        val budget = WebBudget()
        repeat(12) { transport.fetch("https://public.org", budget, dispatch) }
        try { transport.fetch("https://public.org", budget, dispatch); fail() } catch (failure: WebException) { assertEquals(WebFailure.LIMIT, failure.reason) }
        assertEquals(12, calls)
    }

    @Test fun `WEB-READER-03 public transport preserves declared charset after bounded capture`() = runBlocking {
        val text = "Version / Версия"
        val bytes = text.toByteArray(java.nio.charset.Charset.forName("windows-1251"))
        val transport = PublicWebTransport(dns("8.8.8.8"), client { response(it, body = bytes.toResponseBody("text/plain; charset=windows-1251".toMediaType())) })
        val captured = transport.fetch("https://public.org", WebBudget(), dispatch)
        assertEquals("windows-1251", captured.charset)
        assertEquals(text, WebDocuments.page(captured).text)
    }

}
