package ru.adsrv.frpjump.agent.openai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.*
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*

class OpenAiAgentProviderTest {
    private val key = "synthetic-test-key"
    /** Negative observation: a caller that wrongly finishes before its blocking call returns would do so well inside this window. */
    private suspend fun assertHeld(job: Job) = assertNull("the caller must still wait for the blocking call", withTimeoutOrNull(300) { job.join() })
    private val final = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"done"}}]}"""
    private val request = AgentRequest("skill", emptyList(), listOf(AgentTurn("user")), 512)
    private data class Reply(val text: String, val status: Int = 200, val chunked: Boolean = false, val gzip: Boolean = false, val gate: CountDownLatch? = null, val retryAfterZero: Boolean = false)
    private data class Seen(val path: String, val method: String, val authorization: String?, val body: String)
    private class Endpoint : AutoCloseable {
        val replies = LinkedBlockingQueue<Reply>()
        val seen = LinkedBlockingQueue<Seen>()
        private val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, "synthetic-provider").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { exchange ->
                try {
                    seen.put(Seen(exchange.requestURI.toString(), exchange.requestMethod, exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.readBytes().toString(Charsets.UTF_8)))
                    val reply = replies.poll(5, TimeUnit.SECONDS) ?: Reply("{}", 500)
                    reply.gate?.await(5, TimeUnit.SECONDS)
                    var bytes = reply.text.toByteArray()
                    if (reply.gzip) {
                        val output = ByteArrayOutputStream()
                        GZIPOutputStream(output).use { it.write(bytes) }
                        bytes = output.toByteArray()
                        exchange.responseHeaders.add("Content-Encoding", "gzip")
                    }
                    if (reply.retryAfterZero) exchange.responseHeaders.add("Retry-After", "0")
                    if (reply.status == 307) exchange.responseHeaders.add("Location", "/must-not-follow")
                    exchange.sendResponseHeaders(reply.status, if (reply.chunked) 0 else bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (_: Exception) { /* Cancellation/early body cap closes the synthetic connection. */ }
                finally { exchange.close() }
            }
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}/v1"
        override fun close() { server.stop(0); pool.shutdownNow() }
    }
    private fun failure(kind: ProviderFailure, block: () -> Unit): OpenAiProviderException {
        val error = assertThrows(OpenAiProviderException::class.java, block)
        assertEquals(kind, error.failure)
        assertFalse(error.toString().contains(key)); assertNull(error.cause)
        return error
    }

    @Test fun `posts exact root path model effort output limit and authorization without putting key in payload`() = runBlocking {
        Endpoint().use { e ->
            e.replies += Reply(final)
            val provider = OpenAiAgentProvider(OpenAiConfig(e.url, "manual-model", "high", OutputLimitField.MAX_COMPLETION_TOKENS), key)
            assertEquals(AgentReply("done"), provider.complete(request))
            val seen = e.seen.poll(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/chat/completions", seen.path); assertEquals("POST", seen.method)
            assertEquals("Bearer $key", seen.authorization); assertFalse(seen.body.contains(key))
            val body = Json.parseToJsonElement(seen.body).jsonObject
            assertEquals("manual-model", body["model"]!!.jsonPrimitive.content)
            assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
            assertEquals(512, body["max_completion_tokens"]!!.jsonPrimitive.int)
            assertNull(body["max_tokens"]); assertEquals(false, body["stream"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun `default effort is omitted and legacy compatible max tokens is selectable`() {
        val body = Json.parseToJsonElement(OpenAiCodec.encode(request, OpenAiConfig("https://example.invalid/v1", "m"))).jsonObject
        assertNull(body["reasoning_effort"]); assertEquals(512, body["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test fun `discovers deduplicated model IDs before any model is selected`() = runBlocking {
        Endpoint().use { e ->
            e.replies += Reply("""{"data":[{"id":"a"},{"id":"a"},{"id":"b"}]}""")
            assertEquals(listOf(ProviderModel("a"), ProviderModel("b")), OpenAiAgentProvider(OpenAiConfig(e.url), key).listModels())
            val seen = e.seen.poll(2, TimeUnit.SECONDS)!!
            assertEquals("GET", seen.method); assertEquals("/v1/models", seen.path)
        }
    }

    @Test fun `round trips tool calls arguments results and truncation metadata without orphan messages`() = runBlocking {
        Endpoint().use { e ->
            val arguments = buildJsonObject { put("connection", "synthetic"); put("command", "printf test") }
            val response = buildJsonObject { putJsonArray("choices") { add(buildJsonObject {
                put("finish_reason", "tool_calls"); putJsonObject("message") {
                    put("role", "assistant"); put("content", JsonNull)
                    putJsonArray("tool_calls") { add(buildJsonObject {
                        put("id", "source-call"); put("type", "function")
                        putJsonObject("function") { put("name", "fake"); put("arguments", arguments.toString()) }
                    }) }
                }
            }) } }.toString()
            e.replies += Reply(response); e.replies += Reply(final)
            val provider = OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key)
            val reply = provider.complete(request)
            assertEquals(listOf(ToolCall("source-call", "fake", arguments)), reply.calls)
            val turn = AgentTurn("user", listOf(AgentExchange(reply, listOf(ToolResult("source-call", "fake", "head[…]tail", ToolOutcome.SUCCESS, 100)))))
            provider.complete(request.copy(tools = listOf(ToolSpec("fake", "description", buildJsonObject { put("type", "object") })), turns = listOf(turn)))
            e.seen.poll(2, TimeUnit.SECONDS)
            val body = Json.parseToJsonElement(e.seen.poll(2, TimeUnit.SECONDS)!!.body).jsonObject
            val messages = body["messages"]!!.jsonArray
            assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals("source-call", messages.last().jsonObject["tool_call_id"]!!.jsonPrimitive.content)
            val result = Json.parseToJsonElement(messages.last().jsonObject["content"]!!.jsonPrimitive.content).jsonObject
            assertEquals(100, result["omitted_chars"]!!.jsonPrimitive.int)
            assertEquals("success", result["outcome"]!!.jsonPrimitive.content)
            assertEquals("function", body["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    private fun toolResponse(finish: String, content: JsonElement?, count: Int): String = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject {
            put("finish_reason", finish)
            putJsonObject("message") {
                put("role", "assistant")
                content?.let { put("content", it) }
                putJsonArray("tool_calls") {
                    repeat(count) { index -> add(buildJsonObject {
                        put("id", "call-$index"); put("type", "function")
                        putJsonObject("function") { put("name", "fake"); put("arguments", "{}") }
                    }) }
                }
            }
        }) }
    }.toString()

    private fun rejectsStopCalls(content: JsonElement?, count: Int): Unit = runBlocking {
        Endpoint().use { e ->
            val response = toolResponse("stop", content, count)
            e.replies += Reply(response); e.replies += Reply(final)
            var executions = 0
            val tool = object : AgentTool {
                override val spec = ToolSpec("fake", "synthetic", buildJsonObject { put("type", "object") })
                override suspend fun execute(arguments: JsonObject): ToolOutput {
                    executions++
                    return ToolOutput("synthetic result")
                }
            }
            val result = AgentModule(true).run(OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key), listOf(tool), "skill", "user")
            assertEquals("Contradictory stop response must execute zero tools", 0, executions)
            assertEquals(AgentFinish.PROVIDER_FAILED, result.finish)
            assertTrue(result.turn.exchanges.isEmpty())
            assertEquals("Malformed response must not be retried", 1, e.seen.size)
            failure(ProviderFailure.INVALID_RESPONSE) { OpenAiCodec.decode(response) }
        }
    }

    @Test fun `stop with one call and text never executes tools`() = rejectsStopCalls(JsonPrimitive("answer"), 1)
    @Test fun `stop with multiple calls and text never executes tools`() = rejectsStopCalls(JsonPrimitive("answer"), 2)
    @Test fun `stop with one call and no text never executes tools`() {
        for (content in listOf(null, JsonNull, JsonPrimitive(""))) rejectsStopCalls(content, 1)
    }
    @Test fun `stop with multiple calls and no text never executes tools`() {
        for (content in listOf(null, JsonNull, JsonPrimitive(""))) rejectsStopCalls(content, 2)
    }

    @Test fun `valid tool finish executes matching results through kernel with optional text`() = runBlocking {
        for (content in listOf(null, JsonNull, JsonPrimitive(""), JsonPrimitive("working"))) {
            for (count in listOf(1, 2)) Endpoint().use { e ->
                e.replies += Reply(toolResponse("tool_calls", content, count)); e.replies += Reply(final)
                var executions = 0
                val tool = object : AgentTool {
                    override val spec = ToolSpec("fake", "synthetic", buildJsonObject { put("type", "object") })
                    override suspend fun execute(arguments: JsonObject): ToolOutput {
                        executions++
                        return ToolOutput("synthetic result")
                    }
                }
                val result = AgentModule(true).run(OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key), listOf(tool), "skill", "user")
                assertEquals(AgentFinish.COMPLETE, result.finish)
                assertEquals(count, executions)
                val exchange = result.turn.exchanges.first()
                assertEquals(exchange.reply.calls.map { it.id }, exchange.results.map { it.callId })
                assertTrue(exchange.results.all { it.outcome == ToolOutcome.SUCCESS && it.text == "synthetic result" })
                assertEquals("done", result.turn.exchanges.last().reply.text)
                assertEquals(2, e.seen.size)
                e.seen.poll(2, TimeUnit.SECONDS)
                val messages = Json.parseToJsonElement(e.seen.poll(2, TimeUnit.SECONDS)!!.body).jsonObject["messages"]!!.jsonArray
                assertEquals(listOf("system", "user", "assistant") + List(count) { "tool" }, messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
                assertEquals(exchange.reply.calls.map { it.id }, messages.drop(3).map { it.jsonObject["tool_call_id"]!!.jsonPrimitive.content })
            }
        }
    }

    @Test fun `stop final text accepts absent null or empty calls while tool finish requires calls`() = runBlocking {
        for (calls in listOf(null, JsonNull, JsonArray(emptyList()))) {
            val response = buildJsonObject { putJsonArray("choices") { add(buildJsonObject {
                put("finish_reason", "stop")
                putJsonObject("message") {
                    put("role", "assistant"); put("content", "done")
                    calls?.let { put("tool_calls", it) }
                }
            }) } }.toString()
            Endpoint().use { e ->
                e.replies += Reply(response)
                val result = AgentModule(true).run(OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key), emptyList(), "skill", "user")
                assertEquals(AgentFinish.COMPLETE, result.finish)
                assertEquals(AgentReply("done"), result.turn.exchanges.single().reply)
                assertEquals(1, e.seen.size)
            }
            failure(ProviderFailure.INVALID_RESPONSE) { OpenAiCodec.decode(response.replace("\"stop\"", "\"tool_calls\"")) }
        }
    }

    @Test fun `provider continuation is private budgeted and returned only to the same provider instance`() = runBlocking {
        Endpoint().use { e ->
            val opaque = "synthetic-continuation"
            e.replies += Reply("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"answer","reasoning_content":"$opaque"}}]}""")
            e.replies += Reply(final)
            val provider = OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key)
            val reply = provider.complete(request)
            assertEquals("answer", reply.text)
            assertFalse(reply.toString().contains(opaque))
            assertEquals(opaque.length, reply.continuation!!.sizeChars)
            val history = request.copy(turns = listOf(AgentTurn("user", listOf(AgentExchange(reply))), AgentTurn("next")))
            e.seen.poll(2, TimeUnit.SECONDS)
            failure(ProviderFailure.INCOMPATIBLE_CONTEXT) { runBlocking { OpenAiAgentProvider(OpenAiConfig(e.url, "other"), key).complete(history) } }
            assertTrue(e.seen.isEmpty())
            provider.complete(history)
            val messages = Json.parseToJsonElement(e.seen.poll(2, TimeUnit.SECONDS)!!.body).jsonObject["messages"]!!.jsonArray
            assertEquals(opaque, messages[2].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `HTTP errors redact body and never implicitly retry`() {
        Endpoint().use { e ->
            e.replies += Reply("private diagnostic $key", 429)
            val error = failure(ProviderFailure.HTTP) { runBlocking { OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key).complete(request) } }
            assertEquals(429, error.status); assertEquals(1, e.seen.size)
            assertFalse(error.message!!.contains("private diagnostic"))
        }
    }

    @Test fun `503 retry after zero cannot silently issue another paid completion`() {
        Endpoint().use { e ->
            e.replies += Reply("busy", 503, retryAfterZero = true)
            e.replies += Reply(final)
            val error = failure(ProviderFailure.HTTP) { runBlocking { OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key).complete(request) } }
            assertEquals(503, error.status); assertEquals(1, e.seen.size)
        }
    }

    @Test fun `redirects do not retarget keyed requests even to same origin`() {
        Endpoint().use { e ->
            e.replies += Reply("redirect", 307)
            failure(ProviderFailure.HTTP) { runBlocking { OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key).complete(request) } }
            assertEquals(1, e.seen.size)
        }
    }

    @Test fun `response cap applies to known chunked and decompressed bodies`() {
        for ((chunked, gzip) in listOf(false to false, true to false, false to true)) Endpoint().use { e ->
            e.replies += Reply("x".repeat(4096), chunked = chunked, gzip = gzip)
            failure(ProviderFailure.RESPONSE_TOO_LARGE) { runBlocking { OpenAiAgentProvider(OpenAiConfig(e.url, "m", maxResponseBytes = 1024), key).complete(request) } }
        }
    }

    @Test fun `request cap fails before network dispatch`() {
        Endpoint().use { e ->
            failure(ProviderFailure.REQUEST_TOO_LARGE) { runBlocking { OpenAiAgentProvider(OpenAiConfig(e.url, "m", maxRequestBytes = 1024), key).complete(request.copy(instructions = "x".repeat(2048))) } }
            assertTrue(e.seen.isEmpty())
        }
    }

    @Test fun `cancellation of exact HTTP call does not produce a late agent tool execution`() = runBlocking {
        Endpoint().use { e ->
            val release = CountDownLatch(1)
            e.replies += Reply(final, gate = release)
            val provider = OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key)
            val module = AgentModule(true)
            val run = async(start = CoroutineStart.UNDISPATCHED) { module.run(provider, emptyList(), "skill", "user") }
            assertNotNull(withContext(Dispatchers.IO) { e.seen.poll(3, TimeUnit.SECONDS) })
            module.setEnabled(false)
            withTimeout(2000) { try { run.await(); fail("Expected cancellation") } catch (_: kotlinx.coroutines.CancellationException) { } }
            release.countDown()
            assertFalse(module.isRunning); assertTrue(currentCoroutineContext().isActive)
            module.setEnabled(true)
            e.replies += Reply(final)
            assertEquals(AgentFinish.COMPLETE, module.run(provider, emptyList(), "skill", "new").finish)
        }
    }

    @Test fun `malformed and excessive nesting are rejected including inside arguments`() {
        for (text in listOf("not json", "[]", "{" + " ", "[".repeat(65) + "0" + "]".repeat(65))) {
            failure(ProviderFailure.INVALID_RESPONSE) { OpenAiCodec.decode(text) }
        }
        val nested = "[".repeat(65) + "0" + "]".repeat(65)
        val text = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"x","type":"function","function":{"name":"fake","arguments":${JsonPrimitive(nested)}}}]}}]}"""
        failure(ProviderFailure.INVALID_RESPONSE) { OpenAiCodec.decode(text) }
    }

    @Test fun `incomplete responses never become executable tool calls`() {
        for (finish in listOf("length", "content_filter")) {
            failure(ProviderFailure.INCOMPLETE_RESPONSE) { OpenAiCodec.decode("""{"choices":[{"finish_reason":"$finish","message":{"role":"assistant","content":"partial"}}]}""") }
        }
    }

    @Test fun `unsupported tool types missing IDs nonobject arguments and duplicate calls are invalid`() {
        fun response(calls: String) = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":$calls}}]}"""
        val valid = """{"id":"x","type":"function","function":{"name":"fake","arguments":"{}"}}"""
        for (calls in listOf("[]", "[$valid,$valid]", "[${valid.replace("function\",\"function", "other\",\"function")}]", "[${valid.replace("\"id\":\"x\",", "")}]", "[${valid.replace("\"{}\"", "\"[]\"")}]")) {
            failure(ProviderFailure.INVALID_RESPONSE) { OpenAiCodec.decode(response(calls)) }
        }
    }

    @Test fun `invalid credentials and insecure remote or credential URLs are rejected without echoing values`() {
        for (url in listOf("http://example.invalid/v1", "https://user:password@example.invalid/v1", "https://example.invalid/v1?key=synthetic", "https://example.invalid/v1#fragment")) {
            val error = assertThrows(IllegalArgumentException::class.java) { OpenAiConfig(url, "m") }
            assertFalse(error.toString().contains(url))
        }
        for (bad in listOf("", "synthetic\nkey", "syntheticЖ")) {
            val error = assertThrows(IllegalArgumentException::class.java) { OpenAiAgentProvider(OpenAiConfig("https://example.invalid/v1", "m"), bad) }
            assertFalse(error.toString().contains("synthetic"))
        }
    }

    @Test fun `completion without selected model is rejected locally while discovery remains possible`() {
        Endpoint().use { e ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { OpenAiAgentProvider(OpenAiConfig(e.url), key).complete(request) } }
            assertTrue(e.seen.isEmpty())
        }
    }
    private fun imageReply(content: String, refusal: JsonElement? = null): String = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject {
            put("finish_reason", "stop")
            putJsonObject("message") { put("role", "assistant"); put("content", content); refusal?.let { put("refusal", it) } }
        }) }
    }.toString()

    @Test fun `optimistic image uses same root model effort credential and one user image without tools`() = runBlocking {
        Endpoint().use { e ->
            e.replies += Reply(imageReply("done"))
            val provider = OpenAiAgentProvider(OpenAiConfig(e.url, "same-model", "high", OutputLimitField.MAX_COMPLETION_TOKENS), key)
            assertEquals("done", provider.inspectImage("image/png", byteArrayOf(1, 2, 3), "What is visible?"))
            assertEquals(1, e.seen.size)
            val seen = e.seen.poll(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/chat/completions", seen.path); assertEquals("Bearer $key", seen.authorization)
            val body = Json.parseToJsonElement(seen.body).jsonObject
            assertEquals("same-model", body["model"]!!.jsonPrimitive.content)
            assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
            assertEquals(512, body["max_completion_tokens"]!!.jsonPrimitive.int); assertNull(body["tools"])
            val message = body["messages"]!!.jsonArray.single().jsonObject
            assertEquals("user", message["role"]!!.jsonPrimitive.content)
            assertEquals("data:image/png;base64,AQID", message["content"]!!.jsonArray[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            assertFalse(seen.body.contains(key))
        }
    }

    @Test fun `image rejection malformed tool output and payload overflow never retry strip image or fall back`() = runBlocking {
        for (reply in listOf(Reply("private", 400), Reply("invalid"), Reply(toolResponse("tool_calls", null, 1)), Reply(final.replace("done", "x".repeat(4001))))) Endpoint().use { e ->
            e.replies += reply
            val provider = OpenAiAgentProvider(OpenAiConfig(e.url, "same"), key)
            assertThrows(OpenAiProviderException::class.java) { runBlocking { provider.inspectImage("image/jpeg", byteArrayOf(1), "q") } }
            assertEquals(1, e.seen.size)
        }
        Endpoint().use { e ->
            val provider = OpenAiAgentProvider(OpenAiConfig(e.url, "same", maxRequestBytes = 1024), key)
            failure(ProviderFailure.REQUEST_TOO_LARGE) { runBlocking { provider.inspectImage("image/png", ByteArray(1024), "q") } }
            assertTrue(e.seen.isEmpty())
            assertTrue(e.seen.isEmpty())
            // A coroutine that is already cancelled starts no request (the refused-admission half lives in the app tests).
            assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking { cancelled { provider.inspectImage("image/png", byteArrayOf(1), "q") }; Unit } }
            assertTrue(e.seen.isEmpty())
        }
    }

    @Test fun `WEB-IMAGE-05 ordinary model text and structured textual refusal are unverified data`() = runBlocking {
        for (mime in listOf("image/jpeg", "image/png")) {
            for (text in listOf("Красный квадрат / red square", "I cannot process images", "Не могу видеть изображение", "[]", "x".repeat(4000))) {
                Endpoint().use { e ->
                    e.replies += Reply(imageReply(text))
                    assertEquals(text, OpenAiAgentProvider(OpenAiConfig(e.url, "same"), key).inspectImage(mime, byteArrayOf(1), "q"))
                    val encoded = e.seen.poll(2, TimeUnit.SECONDS)!!.body
                    assertFalse(encoded.contains("response_format")); assertFalse(encoded.contains("observed"))
                    assertFalse(encoded.contains("unavailable"))
                }
            }
            Endpoint().use { e ->
                val refusal = "I cannot process this image"
                e.replies += Reply(imageReply("", JsonPrimitive(refusal)))
                assertEquals(refusal, OpenAiAgentProvider(OpenAiConfig(e.url, "same"), key).inspectImage(mime, byteArrayOf(1), "q"))
                assertEquals(1, e.seen.size)
            }
            for (response in listOf(imageReply(""), imageReply(" "), imageReply("x".repeat(4001)),
                imageReply("", buildJsonObject { put("reason", "refused") }),
                imageReply("partial").replace("\"stop\"", "\"length\""), imageReply("filtered").replace("\"stop\"", "\"content_filter\""))) Endpoint().use { e ->
                e.replies += Reply(response)
                assertThrows(OpenAiProviderException::class.java) { runBlocking { OpenAiAgentProvider(OpenAiConfig(e.url, "same"), key).inspectImage(mime, byteArrayOf(1), "q") } }
                assertEquals(1, e.seen.size)
            }
        }
    }

    @Test fun `WEB-IMAGE-05 image timeout cancels exact call without retry or alternate request`() = runBlocking {
        Endpoint().use { e ->
            val release = CountDownLatch(1)
            e.replies += Reply(imageReply("late"), gate = release)
            val provider = OpenAiAgentProvider(OpenAiConfig(e.url, "same"), key)
            val call = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(1000) { provider.inspectImage("image/png", byteArrayOf(1), "q") } }
            assertNotNull(withContext(Dispatchers.IO) { e.seen.poll(2, TimeUnit.SECONDS) })
            try { call.await(); fail() } catch (_: TimeoutCancellationException) { }
            release.countDown(); assertTrue(e.seen.isEmpty()) // The one original request was consumed above; no retry remained.
        }
    }

    // ---- Structured cancellation: the exchange is cancelled by Call.cancel() and the caller waits for its exit ----

    /** Runs [block] in a coroutine that cancels itself first. */
    private suspend fun <T> cancelled(block: suspend () -> T): T = coroutineScope {
        val deferred = async(start = CoroutineStart.UNDISPATCHED) { currentCoroutineContext().job.cancel(); block() }
        deferred.await()
    }
    private class Latched {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val canceled = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<okhttp3.Call>()
        val template: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
            .eventListener(object : okhttp3.EventListener() { override fun canceled(call: okhttp3.Call) { canceled.countDown() } }).addInterceptor { chain ->
            calls += chain.call()
            val response = chain.proceed(chain.request())
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); response
        }.build()
    }
    private fun template(interceptor: okhttp3.Interceptor) = okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).addInterceptor(interceptor).build()

    @Test fun `stalled response is cancelled promptly by call cancel`() = runBlocking {
        Endpoint().use { e ->
            val gate = CountDownLatch(1)
            e.replies += Reply(final, gate = gate)
            val calls = CopyOnWriteArrayList<okhttp3.Call>()
            val provider = OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key, template { calls += it.call(); it.proceed(it.request()) })
            val job = launch(Dispatchers.Default) { provider.complete(request) }
            assertNotNull(withContext(Dispatchers.IO) { e.seen.poll(2, TimeUnit.SECONDS) })
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled); assertTrue(calls.single().isCanceled())
            gate.countDown()
        }
    }

    @Test fun `stalled body read is cancelled promptly`() = runBlocking {
        val stalled = CountDownLatch(1); val release = CountDownLatch(1); val received = CountDownLatch(1)
        val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, "synthetic-stalled").apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { exchange ->
                try {
                    exchange.requestBody.readBytes(); received.countDown()
                    exchange.sendResponseHeaders(200, 100_000)
                    exchange.responseBody.write("{\"choices\"".toByteArray()); exchange.responseBody.flush()
                    stalled.countDown(); release.await(5, TimeUnit.SECONDS)
                } catch (_: Exception) { /* The client closes the synthetic connection. */ }
                finally { exchange.close() }
            }
            start()
        }
        try {
            val provider = OpenAiAgentProvider(OpenAiConfig("http://127.0.0.1:${server.address.port}/v1", "m"), key)
            val job = launch(Dispatchers.Default) { provider.complete(request) }
            assertTrue(withContext(Dispatchers.IO) { stalled.await(3, TimeUnit.SECONDS) })
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { release.countDown(); server.stop(0); pool.shutdownNow() }
    }

    @Test fun `cancel holds the provider call until the exchange thread exits`() = runBlocking {
        Endpoint().use { e ->
            e.replies += Reply(final)
            val latched = Latched()
            val provider = OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key, latched.template)
            val job = launch(Dispatchers.Default) { provider.complete(request) }
            assertTrue(withContext(Dispatchers.IO) { latched.entered.await(3, TimeUnit.SECONDS) })
            job.cancel()
            assertTrue("Call.cancel() must reach the exact call promptly", latched.canceled.await(3, TimeUnit.SECONDS))
            assertHeld(job)
            latched.release.countDown(); job.join()
            assertTrue(job.isCancelled); assertTrue(latched.calls.single().isCanceled())
        }
    }

    @Test fun `already cancelled calls send no request`() = runBlocking {
        Endpoint().use { e ->
            e.replies += Reply(final)
            val provider = OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key)
            assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking { cancelled { provider.complete(request) }; Unit } }
            assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking { cancelled { provider.inspectImage("image/png", byteArrayOf(1), "q") }; Unit } }
            assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking { cancelled { provider.listModels() }; Unit } }
            assertTrue(e.seen.isEmpty())
        }
    }

    @Test fun `cancel after admission but before the network step sends no request`() = runBlocking {
        Endpoint().use { e ->
            e.replies += Reply(final)
            // The application interceptor runs before RetryAndFollowUpInterceptor: the cancel lands in the check-then-start window.
            val provider = OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key, template { chain -> chain.call().cancel(); chain.proceed(chain.request()) })
            failure(ProviderFailure.NETWORK) { runBlocking { provider.complete(request) } }
            assertTrue(e.seen.isEmpty())
        }
    }

    @Test fun `non IO runtime failure of the exchange maps to NETWORK and interrupted IO stays TIMEOUT`() = runBlocking {
        Endpoint().use { e ->
            val runtime = OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key, template { throw IllegalStateException("private-detail") })
            failure(ProviderFailure.NETWORK) { runBlocking { runtime.complete(request) } }
            val timeout = OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key, template { throw java.io.InterruptedIOException("timeout") })
            failure(ProviderFailure.TIMEOUT) { runBlocking { timeout.complete(request) } }
            val io = OpenAiAgentProvider(OpenAiConfig(e.url, "m"), key, template { throw java.io.IOException("synthetic") })
            failure(ProviderFailure.NETWORK) { runBlocking { io.complete(request) } }
            assertTrue(e.seen.isEmpty())
        }
    }

    @Test fun `listModels works without ambient admission and cancellation waits for exit`() = runBlocking {
        Endpoint().use { e ->
            e.replies += Reply("""{"data":[{"id":"a"}]}""")
            assertEquals(listOf(ProviderModel("a")), OpenAiAgentProvider(OpenAiConfig(e.url), key).listModels())
            e.replies += Reply("""{"data":[{"id":"a"}]}""")
            val latched = Latched()
            val provider = OpenAiAgentProvider(OpenAiConfig(e.url), key, latched.template)
            val job = launch(Dispatchers.Default) { provider.listModels() }
            assertTrue(withContext(Dispatchers.IO) { latched.entered.await(3, TimeUnit.SECONDS) })
            job.cancel(); assertTrue(latched.canceled.await(3, TimeUnit.SECONDS)); assertHeld(job)
            latched.release.countDown(); job.join()
            assertTrue(job.isCancelled)
        }
    }

}
