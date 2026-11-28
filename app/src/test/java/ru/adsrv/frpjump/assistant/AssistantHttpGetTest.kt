package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.SideEffectAdmission
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AssistantHttpGetTest {
    private val ids = SavedTunnelIds("p", "c")
    private fun args(path: String) = buildJsonObject { put("profile_id", "p"); put("connection_id", "c"); put("path", path) }
    private suspend fun serve(reply: ByteArray, test: suspend (Int, java.util.concurrent.Future<String>) -> Unit) {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val received = executor.submit<String> {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val first = input.readLine()
                    while (!input.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write(reply)
                    first
                }
            }
            try { test(server.localPort, received) } finally { server.close(); executor.shutdownNow() }
        }
    }
    private fun response(body: ByteArray, type: String = "text/plain", code: String = "200 OK") =
        ("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)

    @Test fun `uses exact loopback and root path and returns status and text`() = runBlocking {
        serve(response("hello".toByteArray())) { port, request ->
            val target = WebHttpTarget(port, false, null, "connection", "period")
            val tool = AssistantHttpGetTool(AssistantWebHttp { if (it == ids) target else null }, 1000)
            val result = withContext(SideEffectAdmission { true }) { tool.execute(args("/a?x=1")) }
            assertEquals(ToolOutcome.SUCCESS, result.outcome)
            assertTrue(result.text.contains("\"status\":200")); assertTrue(result.text.contains("hello"))
            assertEquals("GET /a?x=1 HTTP/1.1", request.get(2, TimeUnit.SECONDS))
        }
    }
    @Test fun `rejects authority and changed target without exposing response`() = runBlocking {
        val unavailable = AssistantHttpGetTool(AssistantWebHttp { null }, 1000)
        assertEquals(ToolOutcome.FAILED, withContext(SideEffectAdmission { true }) { unavailable.execute(args("//other/")) }.outcome)
        serve(response("private".toByteArray())) { port, _ ->
            var calls = 0
            val tool = AssistantHttpGetTool(AssistantWebHttp { WebHttpTarget(port, false, null, "connection", if (++calls < 4) 1 else 2) }, 1000)
            val result = withContext(SideEffectAdmission { true }) { tool.execute(args("/")) }
            assertEquals(ToolOutcome.FAILED, result.outcome); assertFalse(result.text.contains("private"))
        }
    }
    @Test fun `binary omitted and text bounded`() = runBlocking {
        serve(response(byteArrayOf(0, 65), "application/octet-stream")) { port, _ ->
            val tool = AssistantHttpGetTool(AssistantWebHttp { WebHttpTarget(port, false, null, 1, 1) }, 1000)
            val result = withContext(SideEffectAdmission { true }) { tool.execute(args("/")) }
            assertTrue(result.text.contains("\"unreadable\":true")); assertTrue(result.text.contains("\"text\":\"\""))
        }
        serve(response("a".repeat(3000).toByteArray())) { port, _ ->
            val tool = AssistantHttpGetTool(AssistantWebHttp { WebHttpTarget(port, false, null, 1, 1) }, 1000)
            val result = withContext(SideEffectAdmission { true }) { tool.execute(args("/")) }
            assertTrue(result.text.length <= 1000); assertTrue(result.text.contains("\"truncated\":true"))
        }
    }
    @Test fun `pinned certificate succeeds and untrusted and changed certificates fail`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("http-get-test").toFile()
        try {
        val keyFile = java.io.File(directory, "server.p12")
        val keytool = java.io.File(System.getProperty("java.home"), "bin/keytool")
        val process = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keystore", keyFile.path,
            "-storetype", "PKCS12", "-storepass", "synthetic", "-keypass", "synthetic", "-dname", "CN=synthetic.test", "-validity", "1", "-noprompt")
            .redirectErrorStream(true).start()
        process.inputStream.readBytes(); assertEquals(0, process.waitFor())
        val store = java.security.KeyStore.getInstance("PKCS12").apply {
            keyFile.inputStream().use { load(it, "synthetic".toCharArray()) }
        }
        val km = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "synthetic".toCharArray()) }
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
        val fingerprint = ru.adsrv.frpjump.web.certFingerprint(store.getCertificate("server").encoded)
        for (pin in listOf(fingerprint, null, "different")) {
            val server = ssl.serverSocketFactory.createServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val executor = Executors.newSingleThreadExecutor()
            executor.submit { runCatching { server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                input.readLine(); while (!input.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().write(response("tls-ok".toByteArray()))
            } } }
            try {
                val tool = AssistantHttpGetTool(AssistantWebHttp { WebHttpTarget(server.localPort, true, pin, 1, 1) }, 1000)
                val result = withContext(SideEffectAdmission { true }) { tool.execute(args("/")) }
                if (pin == fingerprint) { assertEquals(ToolOutcome.SUCCESS, result.outcome); assertTrue(result.text.contains("tls-ok")) }
                else { assertEquals(ToolOutcome.FAILED, result.outcome); assertFalse(result.text.contains("tls-ok")) }
            } finally { server.close(); executor.shutdownNow() }
        }
        } finally { directory.deleteRecursively() }
    }

    @Test fun `redirect and retry after never trigger another request`() = runBlocking {
        for (code in listOf("302 Found\r\nLocation: /other", "503 Unavailable\r\nRetry-After: 0")) {
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 700
                val executor = Executors.newSingleThreadExecutor()
                val requests = executor.submit<Int> {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        input.readLine(); while (!input.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().write(response(byteArrayOf(), code = code))
                    }
                    try { server.accept().close(); 2 } catch (_: java.net.SocketTimeoutException) { 1 }
                }
                try {
                    val tool = AssistantHttpGetTool(AssistantWebHttp { WebHttpTarget(server.localPort, false, null, 1, 1) }, 1000)
                    withContext(SideEffectAdmission { true }) { tool.execute(args("/")) }
                    assertEquals(1, requests.get(2, TimeUnit.SECONDS))
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Test fun `ready resolver excludes pending and unready tunnels`() {
        val key = ru.adsrv.frpjump.ports.localPortKey("device", 80)
        for (status in ru.adsrv.frpjump.tunnel.TunnelStatus.entries) {
            val tunnel = ru.adsrv.frpjump.tunnel.Tunnel("device", 80, "grant", 12345, ru.adsrv.frpjump.tunnel.VisitorState(status))
            val state = ru.adsrv.frpjump.tunnel.TunnelsState(tunnels = listOf(tunnel))
            assertEquals(if (status in setOf(ru.adsrv.frpjump.tunnel.TunnelStatus.DIRECT, ru.adsrv.frpjump.tunnel.TunnelStatus.RELAYED)) 12345 else null, readyWebPort(state, key))
            assertNull(readyWebPort(state.copy(pending = mapOf(key to ru.adsrv.frpjump.tunnel.PendingOp.DISCONNECTING)), key))
        }
    }

    @Test fun `cancellation closes a partially read body and publishes no result`() = runBlocking {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val started = java.util.concurrent.CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val closed = executor.submit<Int> {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    input.readLine(); while (!input.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 1000\r\n\r\npartial".toByteArray())
                    socket.getOutputStream().flush(); started.countDown()
                    input.read()
                }
            }
            try {
                val tool = AssistantHttpGetTool(AssistantWebHttp { WebHttpTarget(server.localPort, false, null, 1, 1) }, 1000)
                val task = async(Dispatchers.IO + SideEffectAdmission { true }) { tool.execute(args("/")) }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                task.cancelAndJoin()
                assertTrue(task.isCancelled)
                assertEquals(-1, closed.get(5, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

}
