package ru.adsrv.frpjump.api

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.SideEffectAdmission
import ru.adsrv.frpjump.devices.DisplayChange
import ru.adsrv.frpjump.devices.DisplayValue
import ru.adsrv.frpjump.profiles.credentials

/**
 * The single request path of [FrpJumpApi]: one structured call for ordinary and owned (ambient admission) callers.
 * "Nothing sent" is asserted on a real loopback listener, not on an application interceptor (those run even for cancelled calls).
 */
class FrpJumpApiCancellationTest {
    /** A loopback listener that accepts and then stays silent: the TLS handshake of a client stalls in a real socket read. */
    private class StalledServer : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val sockets = CopyOnWriteArrayList<Socket>()
        val acceptedPorts = CopyOnWriteArrayList<Int>()
        val connected = CountDownLatch(1)
        private val acceptor = Thread {
            try { while (true) { val s = server.accept(); sockets += s; acceptedPorts += s.port; connected.countDown() } } catch (_: IOException) { }
        }.apply { isDaemon = true; name = "stalled-server-acceptor"; start() }
        val authority get() = "localhost:${server.localPort}"
        val url get() = "https://$authority"
        val dns: Dns = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }
        /** A probe connection is accepted after every earlier one (FIFO backlog): once it was, no earlier connection exists. */
        fun connectionsBeforeNow(): Int {
            val probe = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (probe.localPort !in acceptedPorts && System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue("the probe connection was accepted", probe.localPort in acceptedPorts)
                return acceptedPorts.indexOf(probe.localPort)
            } finally { probe.close() }
        }
        override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; acceptor.join(2000) }
    }

    private fun serverClient(server: StalledServer, readTimeoutSeconds: Long = 1) = OkHttpClient.Builder().dns(server.dns)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    private fun json(chain: Interceptor.Chain, body: String, code: Int = 200) = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(code).message("synthetic").body(body.toResponseBody("application/json".toMediaType())).build()
    private val versionBody = """{"protocol_version":1,"package_version":"synthetic"}"""

    /** An in-process client: [handler] is the whole "server"; the listener records starts and cancellations (with their thread). */
    private class Probe(handler: (Interceptor.Chain) -> Response) {
        val starts = AtomicInteger()
        val canceledOn = CopyOnWriteArrayList<String>()
        val canceled = CountDownLatch(1)
        val client: OkHttpClient = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { starts.incrementAndGet() }
                override fun canceled(call: Call) { canceledOn += Thread.currentThread().name; canceled.countDown() }
            })
            .addInterceptor { chain -> handler(chain) }.build()
    }

    @Test fun `ambient admission refusal sends nothing for every endpoint`() = runBlocking {
        StalledServer().use { server ->
            val api = FrpJumpApi(serverClient(server))
            val c = credentials(server.authority)
            val asked = CopyOnWriteArrayList<String>()
            val calls = listOf<Pair<String, suspend () -> Unit>>(
                "version" to { api.version(server.url); Unit },
                "version" to { api.enroll(server.url, "token", null); Unit },
                "devices" to { api.devices(c); Unit },
                "delete" to { api.deleteDevice(c, "d") },
                "display" to { api.setDisplay(c, "d", DisplayChange(DisplayValue("x"), null)); Unit },
                "heartbeat" to { api.heartbeat(c) },
                "desired-state" to { api.desiredState(c); Unit },
                "connect" to { api.connect(c, "d", 22, "tcp"); Unit },
                "disconnect" to { api.disconnect(c, "d", 22); Unit },
            )
            for ((operation, call) in calls) {
                try {
                    withTimeout(5000) { withContext(SideEffectAdmission { asked += it; false }) { call() } }
                    fail("$operation: refusal must cancel")
                } catch (cancelled: CancellationException) {
                    assertFalse("$operation: refusal, not a timeout", cancelled is TimeoutCancellationException)
                    assertEquals("Not admitted: $operation", cancelled.message)
                }
            }
            assertEquals(calls.map { it.first }, asked.toList())
            assertEquals("nothing reached the listener", 0, server.connectionsBeforeNow())
        }
    }

    @Test fun `production default asks the ambient admission for version then devices`() = runBlocking {
        val api = FrpJumpApi()
        val asked = mutableListOf<String>()
        for (operation in listOf("version", "devices")) {
            try {
                withTimeout(2000) {
                    withContext(SideEffectAdmission { asked += it; false }) {
                        if (operation == "version") api.version("https://synthetic.example") else api.devices(credentials("synthetic.example"))
                    }
                }
                fail("Refused start must cancel")
            } catch (cancelled: CancellationException) { assertFalse(cancelled is TimeoutCancellationException) }
        }
        assertEquals(listOf("version", "devices"), asked)
    }

    @Test fun `invalid control URL fails before admission and before any call`() = runBlocking {
        val probe = Probe { error("must not be called") }
        val asked = mutableListOf<String>()
        for (url in listOf("http://synthetic.example", "ftp://x", "not a url")) {
            val failure = try {
                withContext(SideEffectAdmission { asked += it; true }) { FrpJumpApi(probe.client).version(url) }; null
            } catch (e: NetworkException) { e }
            assertEquals(url, NetworkException.Reason.NOT_HTTPS, failure?.reason)
        }
        assertEquals(emptyList<String>(), asked); assertEquals(0, probe.starts.get())
    }

    @Test fun `already cancelled caller sends nothing`() = runBlocking {
        StalledServer().use { server ->
            val api = FrpJumpApi(serverClient(server))
            val outcome = AtomicReference<Throwable?>()
            val job = launch(Dispatchers.Default) {
                currentCoroutineContext().job.cancel()
                try { api.devices(credentials(server.authority)) } catch (e: Throwable) { outcome.set(e) }
            }
            job.join()
            assertTrue(outcome.get() is CancellationException)
            assertEquals(0, server.connectionsBeforeNow())
        }
    }

    @Test fun `injected plain client serves ambient and ordinary calls alike`() = runBlocking {
        val probe = Probe { chain -> json(chain, versionBody) }
        val api = FrpJumpApi(probe.client)
        assertEquals("synthetic", api.version("https://synthetic.example").packageVersion)
        assertEquals("synthetic", withContext(SideEffectAdmission { true }) { api.version("https://synthetic.example") }.packageVersion)
        assertEquals(2, probe.starts.get())
    }

    private suspend fun outcomeOf(kind: String, ambient: Boolean): String {
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            when (kind) {
                "network" -> throw IOException("synthetic-network")
                "runtime" -> throw IllegalStateException("synthetic-runtime")
                "redirect" -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("synthetic")
                    .header("Location", "https://other.example/x").body("".toResponseBody()).build()
                "body" -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(object : ResponseBody() {
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = -1L
                    override fun source(): okio.BufferedSource = object : okio.ForwardingSource(okio.Buffer().also { it.writeUtf8("not-json") }) {
                        override fun read(sink: okio.Buffer, byteCount: Long): Long = throw IOException("synthetic-body")
                    }.buffer()
                }).build()
                "http" -> json(chain, """{"detail":"synthetic-http"}""", 503)
                else -> json(chain, versionBody)
            }
        }.build()
        val api = FrpJumpApi(client)
        return try {
            val value = if (ambient) withContext(SideEffectAdmission { true }) { api.version("https://synthetic.example") }
                else api.version("https://synthetic.example")
            "ok:${value.packageVersion}"
        } catch (e: NetworkException) { "network:${e.reason}:${e.detail}:${e.cause?.javaClass?.simpleName}"
        } catch (e: ApiException) { "api:${e.status}:${e.detail}"
        } catch (e: Throwable) { "other:${e.javaClass.simpleName}" }
    }

    @Test fun `ordinary and ambient calls map success HTTP body network redirect and non-IO failures identically`() = runBlocking {
        val expected = mapOf(
            "success" to "ok:synthetic",
            "http" to "api:503:synthetic-http",
            "body" to "network:BROKE:synthetic-body:IOException",
            "network" to "network:UNREACHABLE:synthetic-network:IOException",
            "redirect" to "network:REDIRECT:other.example:null",
            // What OkHttp's AsyncCall.run did for any failure of an interceptor.
            "runtime" to "network:UNREACHABLE:canceled due to java.lang.IllegalStateException: synthetic-runtime:IOException",
        )
        for ((kind, want) in expected) for (ambient in listOf(false, true)) assertEquals("$kind ambient=$ambient", want, outcomeOf(kind, ambient))
    }

    /** [ambient]: with the owned-run element; otherwise an ordinary UI call. [caller] is a non-IO "main" thread. */
    private fun cancelWaitsForExit(ambient: Boolean) = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val interceptorThread = AtomicReference<String?>()
        val bodyThread = AtomicReference<String?>()
        val probe = Probe { chain ->
            interceptorThread.set(Thread.currentThread().name)
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(object : ResponseBody() {
                private val delegate = versionBody.toResponseBody("application/json".toMediaType())
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                override fun source(): okio.BufferedSource = object : okio.ForwardingSource(delegate.source()) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long { bodyThread.set(Thread.currentThread().name); return super.read(sink, byteCount) }
                }.buffer()
            }).build()
        }
        val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        val publisher = Executors.newSingleThreadExecutor { Thread(it, "fake-publisher") }
        var callerJob: Job? = null
        try {
            val outcome = AtomicReference<Throwable?>()
            val caller = launch(main.asCoroutineDispatcher()) {
                try {
                    val body: suspend () -> Unit = { FrpJumpApi(probe.client).version("https://synthetic.example"); Unit }
                    if (ambient) withContext(SideEffectAdmission { true }) { body() } else body()
                } catch (e: Throwable) { outcome.set(e); throw e }
            }
            callerJob = caller
            try {
                assertTrue("exchange entered", entered.await(3, TimeUnit.SECONDS))
                publisher.submit { caller.cancel() }.get(3, TimeUnit.SECONDS)
                // G1: the exact call is cancelled from an IO worker, while the exchange has not returned.
                assertTrue("Call.cancel() reached the exact call", probe.canceled.await(3, TimeUnit.SECONDS))
                assertEquals(1, probe.canceledOn.size)
                assertTrue("cancel ran on ${probe.canceledOn}", probe.canceledOn.single().startsWith("DefaultDispatcher-worker"))
                // Extra detection only: the exit is awaited (the real proof is that join below needs the release).
                assertNull(withTimeoutOrNull(100) { caller.join() })
            } finally { release.countDown() }
            withTimeout(5000) { caller.join() }
            assertTrue(caller.isCancelled); assertTrue(outcome.get() is CancellationException)
            assertEquals(0, probe.client.dispatcher.runningCallsCount())
            assertTrue("exchange on IO, never on the caller: ${interceptorThread.get()}", interceptorThread.get()!!.startsWith("DefaultDispatcher-worker"))
            assertTrue("body read on IO or never reached: ${bodyThread.get()}", bodyThread.get()?.startsWith("DefaultDispatcher-worker") != false)
        } finally { release.countDown(); callerJob?.let { withTimeoutOrNull(5000) { it.cancelAndJoin() } }; main.shutdownNow(); publisher.shutdownNow() }
    }
    @Test fun `cancel cancels the exact call and waits for the actual exit of an owned call`() = cancelWaitsForExit(ambient = true)
    @Test fun `cancel cancels the exact call and waits for the actual exit of an ordinary call`() = cancelWaitsForExit(ambient = false)

    @Test fun `body of an ordinary call is read on IO even for a non-IO caller`() = runBlocking {
        val bodyThread = AtomicReference<String?>()
        val probe = Probe { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(object : ResponseBody() {
                private val delegate = versionBody.toResponseBody("application/json".toMediaType())
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                override fun source(): okio.BufferedSource = object : okio.ForwardingSource(delegate.source()) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long { bodyThread.set(Thread.currentThread().name); return super.read(sink, byteCount) }
                }.buffer()
            }).build()
        }
        val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        try {
            withContext(main.asCoroutineDispatcher()) { FrpJumpApi(probe.client).version("https://synthetic.example") }
            assertTrue(bodyThread.get(), bodyThread.get()!!.startsWith("DefaultDispatcher-worker"))
        } finally { main.shutdownNow() }
    }

    @Test fun `cancel while the exchange stalls in a socket ends without the server's help`() = runBlocking {
        for (ambient in listOf(true, false)) StalledServer().use { server ->
            // The 30 s read timeout of the production client: only the cancel can end this within the test's bound.
            val api = FrpJumpApi(serverClient(server, readTimeoutSeconds = 30))
            val outcome = AtomicReference<Throwable?>()
            val caller = launch(Dispatchers.Default) {
                try {
                    val body: suspend () -> Unit = { api.devices(credentials(server.authority)); Unit }
                    if (ambient) withContext(SideEffectAdmission { true }) { body() } else body()
                } catch (e: Throwable) { outcome.set(e); throw e }
            }
            assertTrue("the request reached the listener", server.connected.await(3, TimeUnit.SECONDS))
            caller.cancel()
            withTimeout(5000) { caller.join() }
            assertTrue("ambient=$ambient: a cancellation, never NetworkException: ${outcome.get()}", outcome.get() is CancellationException)
        }
    }

    @Test fun `IOException after cancel surfaces as cancellation not NetworkException`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val probe = Probe { _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); throw IOException("late failure after cancel") }
        val outcome = AtomicReference<Throwable?>()
        val caller = launch(Dispatchers.Default) {
            try { FrpJumpApi(probe.client).version("https://synthetic.example") } catch (e: Throwable) { outcome.set(e); throw e }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            caller.cancel(); assertTrue(probe.canceled.await(3, TimeUnit.SECONDS))
        } finally { release.countDown() }
        withTimeout(5000) { caller.join() }
        assertTrue("${outcome.get()}", outcome.get() is CancellationException)
        assertFalse(outcome.get() is NetworkException)
    }
}
