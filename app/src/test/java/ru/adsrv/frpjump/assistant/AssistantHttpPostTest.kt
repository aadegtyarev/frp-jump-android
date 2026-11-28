package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.tools.ChangeCheck
import ru.adsrv.frpjump.assistant.chat.*
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AssistantHttpPostTest {
    private val authority = ChangeDispatchAuthority { _, _ -> { true } }
    private fun args(path: String = "/api?x=1", body: String = "hello ✓", type: String = "text/plain") = buildJsonObject {
        put("profile_id", "p"); put("connection_id", "c"); put("path", path); put("content_type", type); put("body", body)
    }
    private fun target(port: Int) = WebHttpTarget(port, false, null, "identity", "period", "Profile", "Panel", "device", 8080)
    private fun readLine(input: java.io.InputStream): String {
        val b = java.io.ByteArrayOutputStream()
        while (true) { val v = input.read(); if (v < 0 || v == 10) break; if (v != 13) b.write(v) }
        return b.toString("US-ASCII")
    }
    private data class Request(val first: String, val headers: Map<String, String>, val body: ByteArray)
    private fun read(input: java.io.InputStream): Request {
        val first = readLine(input); val headers = mutableMapOf<String, String>()
        while (true) { val line = readLine(input); if (line.isEmpty()) break; val split = line.indexOf(':'); headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim() }
        return Request(first, headers, input.readNBytes(headers["content-length"]!!.toInt()))
    }
    private fun reply(code: String, body: String = "ok") = "HTTP/1.1 $code\r\nContent-Type: text/plain\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray()
    @Test fun `one confirmed POST sends exact UTF8 body and shows pinned identity`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val seen = executor.submit<Request> { server.accept().use { socket -> val request = read(socket.getInputStream()); socket.getOutputStream().write(reply("201 Created")); request } }
            try {
                val tool = AssistantHttpPostTool(AssistantWebHttp { target(server.localPort) }, 1000)
                val a = args(); val change = tool.changeCheck(a) as ChangeCheck.Change
                assertEquals("hello ✓", change.request.command)
                assertEquals(listOf("Profile", "Panel", "device", 8080, "/api?x=1", "text/plain"), change.request.http!!.let { listOf(it.profile, it.name, it.device, it.port, it.path, it.contentType) })
                val result = withContext(SideEffectAdmission { true } + authority) { tool.execute(a) }
                assertEquals(ToolOutcome.SUCCESS, result.outcome); assertTrue(result.text.contains("\"status\":201"))
                val request = seen.get(3, TimeUnit.SECONDS)
                assertEquals("POST /api?x=1 HTTP/1.1", request.first); assertEquals("text/plain", request.headers["content-type"])
                assertArrayEquals("hello ✓".toByteArray(Charsets.UTF_8), request.body)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun `confirmation presents the exact request and a declined call never sends`() = runBlocking {
        val tool = AssistantHttpPostTool(AssistantWebHttp { target(12345) }, 1000)
        val a = args(body = "{\"a\":1}", type = "application/json")
        val support = ConfirmationSupport({ ru.adsrv.frpjump.settings.ConfirmationPolicy() }, { true },
            grants = ru.adsrv.frpjump.settings.SessionGrants(ru.adsrv.frpjump.settings.FakeGrantWorld()))
        var seen: ru.adsrv.frpjump.assistant.tools.ChangeRequest? = null
        val denied = authorizeCall(support, mapOf("http_post" to tool), ToolCall("post", "http_post", a),
            wait = { request, _ -> seen = request; ConfirmationDecision.DENY }, onNotSaved = {}, io = Dispatchers.Unconfined)
        assertEquals(ToolOutcome.DECLINED, denied!!.outcome)
        assertEquals("{\"a\":1}", seen!!.command)
        assertEquals("/api?x=1", seen!!.http!!.path)
        assertEquals("application/json", seen!!.http!!.contentType)
        assertEquals(setOf(AssistantToolId.HTTP_POST), seen!!.sessionTools)
    }
    @Test fun `invalid and normalized paths or malformed body ask nothing`() {
        val tool = AssistantHttpPostTool(AssistantWebHttp { target(12345) }, 1000)
        for (a in listOf(args("//host/path"), args("/a/%2e%2e/b"), args("/a b"), args(body = "\uD800"), args(body = "я".repeat(5000)), args(type = "application/xml")))
            assertSame(ChangeCheck.Invalid, tool.changeCheck(a))
    }
    @Test fun `changed target and revoked dispatch send nothing`() = runBlocking {
        for (changed in listOf("identity", "period", "port", "pin", "revision", "hidden", "authority")) {
            var current: WebHttpTarget? = target(12345)
            var allowed = true
            val tool = AssistantHttpPostTool(AssistantWebHttp { current }, 1000)
            val a = args(); assertTrue(tool.changeCheck(a) is ChangeCheck.Change)
            current = when (changed) {
                "identity" -> current!!.copy(identity = "changed"); "period" -> current!!.copy(period = "changed"); "port" -> current!!.copy(localPort = 12346)
                "pin" -> current!!.copy(pin = "new"); "revision" -> current!!.copy(certRevision = 1); "hidden" -> null
                else -> current.also { allowed = false }
            }
            val guard = ChangeDispatchAuthority { _, _ -> { allowed } }
            val result = withContext(SideEffectAdmission { true } + guard) { tool.execute(a) }
            assertEquals(changed, ToolOutcome.FAILED, result.outcome); assertTrue(changed, result.text.contains("unavailable"))
        }
    }
    @Test fun `HTTPS refuses an unreadable certificate store before confirmation`() = runBlocking {
        val unknown = target(12345).copy(https = true, pin = null, certReadable = false)
        val tool = AssistantHttpPostTool(AssistantWebHttp { unknown }, 1000)
        val a = args(); assertSame(ChangeCheck.ReadOnly, tool.changeCheck(a))
        assertEquals(ToolOutcome.FAILED, withContext(SideEffectAdmission { true } + authority) { tool.execute(a) }.outcome)
        val knownAbsent = AssistantHttpPostTool(AssistantWebHttp { unknown.copy(certReadable = true) }, 1000)
        assertTrue(knownAbsent.changeCheck(a) is ChangeCheck.Change)
    }
    @Test fun `dispatch probe rechecks target and authorization before request bytes`() = runBlocking {
        for (reason in listOf("target", "permission")) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val executor = Executors.newSingleThreadExecutor()
                val bytes = executor.submit<Int> { server.accept().use { it.soTimeout = 3000; it.getInputStream().read() } }
                try {
                    var current = target(server.localPort); var allowed = true; var probes = 0
                    val tool = AssistantHttpPostTool(AssistantWebHttp { current }, 1000); val a = args()
                    tool.changeCheck(a)
                    val admission = SideEffectAdmission { category ->
                        if (category == "http-post-dispatch" && ++probes == 2) {
                            if (reason == "target") current = current.copy(period = "changed") else allowed = false
                        }
                        true
                    }
                    val guard = ChangeDispatchAuthority { _, _ -> { allowed } }
                    val result = withContext(admission + guard) { tool.execute(a) }
                    assertEquals(reason, ToolOutcome.FAILED, result.outcome); assertEquals(reason, 2, probes)
                    assertEquals(reason, -1, bytes.get(4, TimeUnit.SECONDS))
                } finally { executor.shutdownNow() }
            }
        }
    }
    @Test fun `redirect and retry after never cause a second POST`() = runBlocking {
        for (code in listOf("307 Temporary Redirect\r\nLocation: /other", "308 Permanent Redirect\r\nLocation: /other", "503 Unavailable\r\nRetry-After: 0")) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 700; val executor = Executors.newSingleThreadExecutor()
                val count = executor.submit<Int> {
                    server.accept().use { socket -> read(socket.getInputStream()); socket.getOutputStream().write(reply(code)) }
                    try { server.accept().close(); 2 } catch (_: java.net.SocketTimeoutException) { 1 }
                }
                try {
                    val tool = AssistantHttpPostTool(AssistantWebHttp { target(server.localPort) }, 1000); val a = args()
                    tool.changeCheck(a); val result = withContext(SideEffectAdmission { true } + authority) { tool.execute(a) }
                    assertEquals(ToolOutcome.SUCCESS, result.outcome); assertEquals(1, count.get(3, TimeUnit.SECONDS))
                } finally { executor.shutdownNow() }
            }
        }
    }
    @Test fun `disconnect after dispatch reports uncertain outcome`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val sent = executor.submit { server.accept().use { read(it.getInputStream()) } }
            try {
                val tool = AssistantHttpPostTool(AssistantWebHttp { target(server.localPort) }, 1000); val a = args()
                tool.changeCheck(a); val result = withContext(SideEffectAdmission { true } + authority) { tool.execute(a) }
                sent.get(3, TimeUnit.SECONDS); assertEquals(ToolOutcome.FAILED, result.outcome); assertTrue(result.text.contains("unknown"))
            } finally { executor.shutdownNow() }
        }
    }
}
