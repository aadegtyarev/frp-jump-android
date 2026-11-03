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
}
