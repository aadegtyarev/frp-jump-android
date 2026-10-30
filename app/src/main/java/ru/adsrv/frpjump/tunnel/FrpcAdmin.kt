package ru.adsrv.frpjump.tunnel

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.Base64

/**
 * frpc's admin API (`webServer`) for one frpc run: on 127.0.0.1, a port
 * that was free a moment ago, a random user and password. Kept in memory
 * and in the 0600 config file only; a new run gets new ones.
 */
class FrpcAdmin(val port: Int, val user: String, val password: String) {
    override fun toString() = "FrpcAdmin(127.0.0.1:$port)"

    /**
     * `GET /api/reload` (frp 0.70 `client/api_router.go`): frpc re-reads its
     * config file and swaps visitors (`visitor.Manager.UpdateAll`). Visitors
     * whose config is unchanged are left running with their connections;
     * changed ones are closed and started again, gone ones closed. Common
     * settings (server, TLS, metadatas, log, webServer) are not re-applied.
     * True on 200.
     */
    fun reload(connectTimeoutMs: Int = 2_000, readTimeoutMs: Int = 10_000): Boolean = try {
        val connection = URL("http://127.0.0.1:$port/api/reload?strictConfig=true").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            val basic = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
            connection.setRequestProperty("Authorization", "Basic $basic")
            connection.responseCode == HttpURLConnection.HTTP_OK
        } finally {
            connection.disconnect()
        }
    } catch (_: IOException) {
        false
    }

    companion object {
        private val random = SecureRandom()

        fun random(): FrpcAdmin = FrpcAdmin(freeLoopbackPort(), hex(12), hex(24))

        private fun freeLoopbackPort(): Int =
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        private fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
