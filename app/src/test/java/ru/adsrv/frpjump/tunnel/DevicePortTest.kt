package ru.adsrv.frpjump.tunnel

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test

/** [touchLocalPort] against real sockets, standing in for frpc's visitor. */
class DevicePortTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun `nothing listening - not connected`() {
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        assertEquals(Touch.NOT_CONNECTED, touchLocalPort(port))
    }

    @Test
    fun `closed at once, as a refusal - closed`() {
        ServerSocket(0, 1, loopback).use { server ->
            thread(isDaemon = true) { runCatching { server.accept().close() } }
            assertEquals(Touch.CLOSED, touchLocalPort(server.localPort))
        }
    }

    @Test
    fun `kept open, as a device's service waiting for its client - open`() {
        ServerSocket(0, 1, loopback).use { server ->
            thread(isDaemon = true) { runCatching { server.accept().use { Thread.sleep(2_000) } } }
            assertEquals(Touch.OPEN, touchLocalPort(server.localPort))
        }
    }

    @Test
    fun `the service speaks first - open`() {
        ServerSocket(0, 1, loopback).use { server ->
            thread(isDaemon = true) { runCatching { server.accept().use { it.getOutputStream().write("SSH-2.0-x\r\n".toByteArray()); Thread.sleep(1_000) } } }
            assertEquals(Touch.OPEN, touchLocalPort(server.localPort))
        }
    }
}
