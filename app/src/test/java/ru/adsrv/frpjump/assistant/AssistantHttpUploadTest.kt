package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.chat.*
import ru.adsrv.frpjump.assistant.tools.ChangeCheck
import ru.adsrv.frpjump.settings.AssistantAvailability
import ru.adsrv.frpjump.settings.AssistantTools
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AssistantHttpUploadTest {
    private fun args(id: String = "id", path: String = "/upload?x=1") = buildJsonObject {
        put("profile_id", "p"); put("connection_id", "c"); put("path", path); put("file_id", id)
    }
    private fun target(port: Int) = WebHttpTarget(port, false, null, "identity", "period", "Profile", "Panel", "device", 8080)
    private fun workspace() = TemporaryWorkspace({ AssistantAvailability(true, tools = AssistantTools(httpPost = true, tempFiles = true)) },
        { AssistantProviderSnapshot(AssistantProviderSettings(model = "synthetic"), "provider") })
    private fun line(input: java.io.InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) bytes.write(c) }
        return bytes.toString("US-ASCII")
    }
    @Test fun `confirmed upload sends exact immutable bytes and metadata only before dispatch`() = runBlocking {
        for (body in listOf(byteArrayOf(), byteArrayOf(0, -1, -128, 42), ByteArray(TemporaryWorkspace.MAX_FILE_BYTES) { (it % 251).toByte() })) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val pool = Executors.newSingleThreadExecutor()
                val received = pool.submit<ByteArray> { server.accept().use { socket ->
                    val input = socket.getInputStream(); assertEquals("POST /upload?x=1 HTTP/1.1", line(input))
                    val headers = mutableMapOf<String, String>()
                    while (true) { val text = line(input); if (text.isEmpty()) break; val split = text.indexOf(':'); headers[text.substring(0, split).lowercase()] = text.substring(split + 1).trim() }
                    assertEquals("application/octet-stream", headers["content-type"])
                    val bytes = input.readNBytes(headers.getValue("content-length").toInt())
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 12\r\nConnection: close\r\n\r\nprivate echo".toByteArray())
                    bytes
                } }
                try {
                    val store = workspace(); store.applyLocked(TempAction.Create("id", "archive.bin", body))
                    var reads = 0
                    val access = TemporaryAccess { action, job ->
                        if (action is TempAction.UploadBytes) reads++
                        if (job?.isActive == true && (action !is TempAction.UploadBytes || action.valid())) store.applyLocked(action) else null
                    }
                    val tool = AssistantHttpUploadTool(AssistantWebHttp { target(server.localPort) }, 1000) { id -> (store.applyLocked(TempAction.UploadInfo(id)) as? TempResult.Info)?.file }
                    val change = tool.changeCheck(args()) as ChangeCheck.Change
                    assertEquals(0, reads); assertEquals("", change.request.command)
                    assertEquals("archive.bin", change.request.http!!.fileName); assertEquals(body.size, change.request.http!!.fileBytes)
                    val result = withContext(SideEffectAdmission { true } + ChangeDispatchAuthority { _, _ -> { true } } + access) { tool.execute(args()) }
                    assertEquals(ToolOutcome.SUCCESS, result.outcome); assertEquals("{\"status\":200}", result.text); assertEquals(1, reads)
                    assertArrayEquals(body, received.get(5, TimeUnit.SECONDS))
                } finally { pool.shutdownNow() }
            }
        }
    }
    @Test fun `cancellation after byte lookup sends nothing`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            val first = pool.submit<Int> { server.accept().use { it.soTimeout = 3000; it.getInputStream().read() } }
            try {
                val store = workspace(); store.applyLocked(TempAction.Create("id", "item", byteArrayOf(1, 2)))
                val access = TemporaryAccess { action, job ->
                    if (job?.isActive != true) null else store.applyLocked(action).also { if (action is TempAction.UploadBytes) job.cancel() }
                }
                val tool = AssistantHttpUploadTool(AssistantWebHttp { target(server.localPort) }, 1000) { id -> (store.applyLocked(TempAction.UploadInfo(id)) as? TempResult.Info)?.file }
                assertTrue(tool.changeCheck(args()) is ChangeCheck.Change)
                val task = async(Dispatchers.IO + SideEffectAdmission { true } + ChangeDispatchAuthority { _, _ -> { true } } + access) { tool.execute(args()) }
                try { task.await(); fail("expected cancellation") } catch (_: CancellationException) { }
                assertEquals(-1, first.get(4, TimeUnit.SECONDS))
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun `redirect or server failure returns only failed status and never retries`() = runBlocking {
        for (status in listOf("307 Temporary Redirect\r\nLocation: /other", "503 Unavailable\r\nRetry-After: 0")) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 700; val pool = Executors.newSingleThreadExecutor()
                val count = pool.submit<Int> {
                    server.accept().use { socket ->
                        val input = socket.getInputStream(); line(input)
                        val headers = mutableMapOf<String, String>()
                        while (true) { val text = line(input); if (text.isEmpty()) break; val split = text.indexOf(':'); headers[text.substring(0, split).lowercase()] = text.substring(split + 1).trim() }
                        input.readNBytes(headers.getValue("content-length").toInt())
                        socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 12\r\nConnection: close\r\n\r\nprivate echo".toByteArray())
                    }
                    try { server.accept().close(); 2 } catch (_: java.net.SocketTimeoutException) { 1 }
                }
                try {
                    val store = workspace(); store.applyLocked(TempAction.Create("id", "item", byteArrayOf(1)))
                    val access = TemporaryAccess { action, job -> if (job?.isActive == true) store.applyLocked(action) else null }
                    val tool = AssistantHttpUploadTool(AssistantWebHttp { target(server.localPort) }, 1000) { id -> (store.applyLocked(TempAction.UploadInfo(id)) as? TempResult.Info)?.file }
                    assertTrue(tool.changeCheck(args()) is ChangeCheck.Change)
                    val result = withContext(SideEffectAdmission { true } + ChangeDispatchAuthority { _, _ -> { true } } + access) { tool.execute(args()) }
                    assertEquals(ToolOutcome.FAILED, result.outcome)
                    assertEquals("{\"status\":${status.take(3)}}", result.text)
                    assertEquals(1, count.get(4, TimeUnit.SECONDS))
                } finally { pool.shutdownNow() }
            }
        }
    }

    @Test fun `denial missing file invalid path and late revocation send nothing`() = runBlocking {
        val store = workspace(); store.applyLocked(TempAction.Create("id", "item", byteArrayOf(1, 2)))
        val lookup: (String) -> TempInfo? = { id -> (store.applyLocked(TempAction.UploadInfo(id)) as? TempResult.Info)?.file }
        val support = ConfirmationSupport({ ru.adsrv.frpjump.settings.ConfirmationPolicy() }, { true }, grants = ru.adsrv.frpjump.settings.SessionGrants(ru.adsrv.frpjump.settings.FakeGrantWorld()))
        val noServer = AssistantHttpUploadTool(AssistantWebHttp { target(12345) }, 1000, lookup)
        assertSame(ChangeCheck.Invalid, noServer.changeCheck(args(path = "/a/%2e%2e/b")))
        assertSame(ChangeCheck.ReadOnly, noServer.changeCheck(args("missing")))
        val denied = authorizeCall(support, mapOf("http_upload" to noServer), ToolCall("x", "http_upload", args()),
            wait = { request, _ -> assertEquals(setOf(AssistantToolId.HTTP_UPLOAD), request.sessionTools); ConfirmationDecision.DENY }, onNotSaved = {}, io = Dispatchers.Unconfined)
        assertEquals(ToolOutcome.DECLINED, denied!!.outcome)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            val first = pool.submit<Int> { server.accept().use { it.soTimeout = 3000; it.getInputStream().read() } }
            try {
                var allowed = true
                val access = TemporaryAccess { action, job ->
                    if (job?.isActive != true || !allowed) null else when (action) {
                        is TempAction.UploadBytes -> store.applyLocked(action).also { allowed = false }
                        else -> store.applyLocked(action)
                    }
                }
                val tool = AssistantHttpUploadTool(AssistantWebHttp { target(server.localPort) }, 1000, lookup)
                assertTrue(tool.changeCheck(args()) is ChangeCheck.Change)
                val result = withContext(SideEffectAdmission { true } + ChangeDispatchAuthority { _, _ -> { allowed } } + access) { tool.execute(args()) }
                assertEquals(ToolOutcome.FAILED, result.outcome); assertEquals(-1, first.get(4, TimeUnit.SECONDS))
            } finally { pool.shutdownNow() }
        }
    }
}
