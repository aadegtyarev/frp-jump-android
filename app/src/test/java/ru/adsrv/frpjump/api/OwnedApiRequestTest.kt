package ru.adsrv.frpjump.api

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import okio.buffer
import ru.adsrv.frpjump.profiles.credentials

class OwnedApiRequestTest {
    @Test fun `production default supports owned admission and refusal before enqueue exits without hanging`() = runBlocking {
        // No Application, credential files or actual HTTP: refusal at the actual enqueue boundary.
        val api = FrpJumpApi()
        val operations = mutableListOf<String>()
        for (operation in listOf("version", "devices")) {
            try {
                withTimeout(2000) {
                    val refuse: ApiRequestDispatch = { name, _ -> operations += name; throw CancellationException("Synthetic refusal") }
                    if (operation == "version") api.version("https://synthetic.example", refuse)
                    else api.devices(credentials("synthetic.example"), refuse)
                }
                fail("Refused enqueue must cancel")
            } catch (cancelled: CancellationException) { assertFalse(cancelled is TimeoutCancellationException) }
        }
        assertEquals(listOf("version", "devices"), operations)
    }

    @Test fun `incompatible injected owned client fails before enqueue while ordinary injected calls stay supported`() = runBlocking {
        var starts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            starts++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body("""{"protocol_version":1,"package_version":"synthetic"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val api = FrpJumpApi(client)
            for (operation in listOf("version", "devices")) {
                try {
                    withTimeout(2000) {
                        if (operation == "version") api.version("https://synthetic.example") { _, start -> start() }
                        else api.devices(credentials("synthetic.example")) { _, start -> start() }
                    }
                    fail("Incompatible owned executor must fail explicitly")
                } catch (failure: IllegalStateException) {
                    assertFalse("A timeout/cancellation is not the required setup failure", failure is CancellationException)
                    assertEquals("Owned API requests require callback-exit dispatcher", failure.message)
                }
            }
            assertEquals(0, starts)
            assertEquals("synthetic", api.version("https://synthetic.example").packageVersion)
            assertEquals(1, starts)
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun `owned callback success HTTP body IO failure and cancellation wait for actual worker exit`() = runBlocking {
        for (kind in listOf("success", "http", "body", "network", "cancel")) {
            val executor = ApiCallbackExitExecutor()
            val callbackEntered = CountDownLatch(1); val release = CountDownLatch(1); val awaiting = CountDownLatch(1)
            val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).addInterceptor { chain ->
                if (kind == "network") throw IOException("synthetic-network")
                val body = if (kind == "body") object : ResponseBody() {
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = -1L
                    override fun source(): okio.BufferedSource = okio.Buffer().also { it.writeUtf8("not-json") }.let { source ->
                        object : okio.ForwardingSource(source) {
                            override fun read(sink: okio.Buffer, byteCount: Long): Long = throw IOException("synthetic-body")
                        }.buffer()
                    }
                } else (if (kind == "http") """{"detail":"synthetic-http"}""" else """{"protocol_version":1,"package_version":"synthetic"}""")
                    .toResponseBody("application/json".toMediaType())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (kind == "http") 503 else 200)
                    .message("synthetic").body(body).build()
            }.build()
            val api = FrpJumpApi(client)
            api.beforeOwnedCallbackExit = { callbackEntered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            api.beforeOwnedAwaitExit = { awaiting.countDown() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val call = scope.async { runCatching { api.version("https://synthetic.example") { _, start -> start() } } }
                assertTrue("$kind callback entered", callbackEntered.await(2, TimeUnit.SECONDS))
                if (kind == "cancel") call.cancel()
                assertTrue("$kind coroutine awaiting worker exit", awaiting.await(2, TimeUnit.SECONDS))
                assertFalse("$kind cannot finish while AsyncCall is held", call.isCompleted)
                release.countDown()
                if (kind == "cancel") call.join() else {
                    val outcome = withTimeout(2000) { call.await() }
                    when (kind) {
                        "success" -> assertEquals("synthetic", outcome.getOrThrow().packageVersion)
                        "http" -> assertEquals(503, (outcome.exceptionOrNull() as ApiException).status)
                        "body" -> assertEquals(NetworkException.Reason.BROKE, (outcome.exceptionOrNull() as NetworkException).reason)
                        "network" -> assertEquals(NetworkException.Reason.UNREACHABLE, (outcome.exceptionOrNull() as NetworkException).reason)
                    }
                }
                assertEquals("AsyncCall dispatcher bookkeeping exited", 0, client.dispatcher.runningCallsCount())
            } finally { release.countDown(); scope.cancel(); executor.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    @Test fun `owned rejected executor completes inline callback cleanup rather than leaking caller`() = runBlocking {
        val executor = ApiCallbackExitExecutor().apply { shutdown() }
        val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).build()
        try {
            val result = withTimeout(2000) { runCatching { FrpJumpApi(client).version("https://synthetic.example") { _, start -> start() } } }
            assertTrue(result.exceptionOrNull() is NetworkException)
        } finally { client.connectionPool.evictAll() }
    }
}
