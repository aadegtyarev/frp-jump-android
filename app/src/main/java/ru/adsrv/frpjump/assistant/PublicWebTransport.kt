package ru.adsrv.frpjump.assistant

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer

internal enum class WebFailure { URL, NETWORK, HTTP, TOO_LARGE, TYPE, EMPTY, LIMIT, BLOCKED, IMAGE, ARGUMENTS }
internal class WebException(val reason: WebFailure, val status: Int? = null,
    val imageFailure: ru.adsrv.frpjump.agent.ProviderErrorKind? = null) : Exception("Public request failed: $reason")
internal fun webFail(reason: WebFailure, status: Int? = null): Nothing = throw WebException(reason, status)

/** Fail closed: IPv6 global unicast only, excluding transition/documentation/special allocations. */
internal object PublicWebPolicy {
    fun url(raw: String): HttpUrl {
        if (raw.length > 2048 || raw.any { it.isISOControl() }) webFail(WebFailure.URL)
        val url = raw.toHttpUrlOrNull() ?: webFail(WebFailure.URL)
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) webFail(WebFailure.URL)
        val host = url.host.lowercase().trimEnd('.')
        if (!host.contains('.') && !host.contains(':') || host == "localhost" ||
            listOf(".localhost", ".local", ".internal", ".home", ".lan", ".test", ".invalid", ".example", ".onion").any(host::endsWith)) webFail(WebFailure.URL)
        return url.newBuilder().fragment(null).build()
    }
    fun publicAddress(address: InetAddress): Boolean {
        val b = address.address.map { it.toInt() and 255 }
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        if (b.size == 4) return when {
            b[0] in setOf(0, 10, 127) || b[0] >= 224 -> false
            b[0] == 100 && b[1] in 64..127 -> false
            b[0] == 169 && b[1] == 254 || b[0] == 172 && b[1] in 16..31 -> false
            b[0] == 192 && (b[1] == 168 || b[1] == 0 || b[1] == 2 || b[1] == 88 && b[2] == 99 || b[1] == 31 && b[2] == 196 || b[1] == 52 && b[2] == 193 || b[1] == 175 && b[2] == 48) -> false
            b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100) -> false
            b[0] == 203 && b[1] == 0 && b[2] == 113 -> false
            else -> true
        }
        if (b.size != 16 || b[0] !in 0x20..0x3f) return false
        if (b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || b[2] == 0x0d && b[3] == 0xb8 || b[2] in 0x20..0x3f)) return false
        if (b[0] == 0x20 && b[1] == 0x02 || b[0] == 0x3f && b[1] == 0xff) return false
        if (b[0] == 0x26 && b[1] == 0x20 && b[2] == 0 && b[3] == 0x4f && b[4] == 0x80 && b[5] == 0) return false
        return true
    }
    fun resolve(host: String, dns: Dns): List<InetAddress> {
        val addresses = dns.lookup(host)
        if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) webFail(WebFailure.URL)
        return addresses.toList()
    }
}

internal class WebBudget { var requests = 0; var inspections = 0; var searches = 0; var searchBlocked = false }
internal class WebResponse(val url: String, val mime: String, val bytes: ByteArray, val status: Int, val retryAfter: String?, val charset: String? = null)

/** The guard only encloses enqueue, never DNS, body reading or parsing. */
internal typealias WebDispatch = (String, () -> Unit) -> Unit
internal fun interface PublicFetcher {
    suspend fun fetch(url: String, budget: WebBudget, dispatch: WebDispatch): WebResponse
}

/** Dedicated credential-free transport. Validated resolution is the ONLY DNS used by that connection.
 * A fresh pool per hop prevents connection coalescing from bypassing the validated address set.
 */
internal class PublicWebTransport(
    private val dns: Dns = Dns.SYSTEM,
    // Synthetic tests inject an in-memory exchange client, never a production public-policy bypass.
    private val template: OkHttpClient = base,
) : PublicFetcher {
    override suspend fun fetch(url: String, budget: WebBudget, dispatch: WebDispatch): WebResponse = withContext(Dispatchers.IO) {
        var target = PublicWebPolicy.url(url)
        repeat(4) { hop ->
            currentCoroutineContext().ensureActive()
            if (budget.requests >= 12) webFail(WebFailure.LIMIT)
            val host = target.host
            val addresses = coroutineScope {
                var resolution: Deferred<List<InetAddress>>? = null
                dispatch("dns-dispatch") {
                    // Admit the owned blocking task, not DNS itself, under the short common guard.
                    resolution = async(Dispatchers.IO) { PublicWebPolicy.resolve(host, dns) }
                }
                checkNotNull(resolution).await()
            }
            currentCoroutineContext().ensureActive()
            val client = template.newBuilder().dispatcher(Dispatcher(executor)).connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = if (hostname != host) webFail(WebFailure.URL) else addresses }).build()
            val language = Locale.getDefault().toLanguageTag().takeUnless { it == "und" } ?: "en"
            val request = Request.Builder().url(target)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html, application/xhtml+xml, text/plain;q=0.9, text/markdown;q=0.9, image/png;q=0.8, image/jpeg;q=0.8")
                .header("Accept-Language", language)
                .get().build()
            val response = execute(client.newCall(request), dispatch) {
                if (budget.requests >= 12) webFail(WebFailure.LIMIT)
                budget.requests++
            }
            if (response.status in setOf(301, 302, 303, 307, 308)) {
                if (hop == 3) webFail(WebFailure.LIMIT)
                target = PublicWebPolicy.url(target.resolve(response.retryAfter ?: "")?.toString() ?: webFail(WebFailure.URL))
            } else return@withContext response
        }
        webFail(WebFailure.LIMIT)
    }

    private suspend fun execute(call: Call, dispatch: WebDispatch, charge: () -> Unit): WebResponse {
        val exited = CompletableDeferred<Unit>()
        var enqueued = false
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                var inline = false
                try {
                    dispatch("public-dispatch") {
                        if (!continuation.isActive) throw CancellationException()
                        charge()
                        enqueued = true
                        call.enqueue(object : Callback {
                            private fun callback(block: () -> Unit) {
                                if (!executor.register(exited)) inline = true
                                block()
                            }
                            override fun onFailure(call: Call, e: IOException) = callback {
                                continuation.resumeWithException(WebException(WebFailure.NETWORK))
                            }
                            override fun onResponse(call: Call, response: Response) = callback {
                                response.use {
                                    try {
                                        if (!continuation.isActive) return@callback
                                        val redirect = response.code in setOf(301, 302, 303, 307, 308)
                                        val body = response.body ?: webFail(WebFailure.EMPTY)
                                        val buffer = Buffer()
                                        if (!redirect && response.isSuccessful) {
                                            if (response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true) webFail(WebFailure.TYPE)
                                            if (body.contentLength() > MAX_BYTES) webFail(WebFailure.TOO_LARGE)
                                            val source = body.source()
                                            while (source.read(buffer, minOf(8192L, MAX_BYTES + 1L - buffer.size)) != -1L) {
                                                if (buffer.size > MAX_BYTES) webFail(WebFailure.TOO_LARGE)
                                            }
                                        }
                                        continuation.resume(WebResponse(response.request.url.toString(), body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty(),
                                            buffer.readByteArray(), response.code, response.header(if (redirect) "Location" else "Retry-After"), body.contentType()?.parameter("charset")))
                                    } catch (failure: Exception) {
                                        continuation.resumeWithException(if (failure is WebException) failure else WebException(WebFailure.NETWORK))
                                    }
                                }
                            }
                        })
                    }
                } catch (failure: Throwable) { exited.complete(Unit); continuation.resumeWithException(failure) }
                finally { if (inline || !enqueued) exited.complete(Unit) }
            }
        } finally { withContext(NonCancellable) { exited.await() } }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        const val MAX_BYTES = 1_048_576
        class ExitExecutor : ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "assistant-public-http").apply { isDaemon = true } }) {
            private val pending = ThreadLocal<MutableList<CompletableDeferred<Unit>>>()
            private val worker = ThreadLocal<Boolean>()
            override fun beforeExecute(thread: Thread, task: Runnable) { super.beforeExecute(thread, task); worker.set(true) }
            fun register(done: CompletableDeferred<Unit>): Boolean {
                if (worker.get() != true) return false
                (pending.get() ?: mutableListOf<CompletableDeferred<Unit>>().also { pending.set(it) }).add(done)
                return true
            }
            override fun afterExecute(task: Runnable, failure: Throwable?) {
                val done = pending.get(); pending.remove(); worker.remove()
                try { super.afterExecute(task, failure) } finally { done?.forEach { it.complete(Unit) } }
            }
        }
        val executor = ExitExecutor()
        val base = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
            }.connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    }
}
