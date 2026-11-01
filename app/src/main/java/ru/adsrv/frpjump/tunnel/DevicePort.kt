package ru.adsrv.frpjump.tunnel

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** How long a check connection waits to connect locally, and then for the relay's refusal. */
const val TOUCH_MS = 500

/** What a check connection to a tunnel's local port saw. */
enum class Touch {
    /** frpc did not take it (not listening yet, or busy): it proves nothing. */
    NOT_CONNECTED,

    /** Closed: the relay refused it, or the device's service closed it ([FrpcLogParser.deviceReady] tells which). */
    CLOSED,

    /** Still open after [TOUCH_MS], or the device's service spoke first. */
    OPEN,
}

/**
 * Opens a connection to a tunnel's local port and closes it: frpc carries
 * it to the device, or logs that the device refused it. A refusal closes
 * the connection within a round trip to the relay. The device's service
 * sees one connection that sends nothing. Blocking; IO dispatcher.
 */
fun touchLocalPort(localPort: Int): Touch {
    Socket().use { socket ->
        try {
            socket.connect(InetSocketAddress("127.0.0.1", localPort), TOUCH_MS)
        } catch (_: IOException) {
            return Touch.NOT_CONNECTED
        }
        return try {
            socket.soTimeout = TOUCH_MS
            if (socket.getInputStream().read() < 0) Touch.CLOSED else Touch.OPEN
        } catch (_: SocketTimeoutException) {
            Touch.OPEN
        } catch (_: IOException) {
            // Reset: closed as a refusal is.
            Touch.CLOSED
        }
    }
}
