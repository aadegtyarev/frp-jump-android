package ru.adsrv.frpjump.tunnel

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** How long a connection made only to see whether the device takes it is kept open at most. */
const val TOUCH_READ_MS = 1_500

/**
 * Opens a connection to a tunnel's local port and closes it: frpc carries
 * it to the device, or logs that the device refused it
 * ([FrpcLogParser.deviceReady] tells which). Waits up to [TOUCH_READ_MS]
 * for the relay's answer: a refusal closes the connection at once, one
 * that got through stays open (or the device's service speaks first).
 * Blocking; IO dispatcher.
 */
fun touchLocalPort(localPort: Int) {
    try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", localPort), TOUCH_READ_MS)
            socket.soTimeout = TOUCH_READ_MS
            socket.getInputStream().read()
        }
    } catch (_: IOException) {
        // Refused, closed or timed out alike: frpc's log says what happened.
    }
}
