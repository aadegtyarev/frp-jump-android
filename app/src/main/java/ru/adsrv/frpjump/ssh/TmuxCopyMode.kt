package ru.adsrv.frpjump.ssh

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps typing out of tmux's copy mode. With `mouse on` a swipe up is a
 * mouse-wheel-up event, and tmux answers it by entering copy mode, where
 * typed keys are copy-mode commands, not the shell's input.
 *
 * The app sees both: the wheel events pass through the terminal's outgoing
 * bytes, and the owner's own input (keys, extra keys, snippets, a paste)
 * calls [ownerTyped] before its bytes are queued. So: after a wheel-up, the
 * owner's next input is held on the terminal's writer thread until one exec
 * of [tmuxLeaveModeCommand] has run (tmux leaves the mode only if the pane
 * is in one), then sent. One round trip on the first key after scrolling;
 * none otherwise. Scrolling back to the bottom leaves copy mode by itself,
 * and the exec is then a harmless no-op.
 *
 * Thread-safe: [ownerTyped] from the main thread, [beforeWrite] from the
 * writer thread.
 */
class CopyModeGuard {
    private val typed = AtomicBoolean(false)

    @Volatile
    private var scrolled = false

    /** The connection now open runs tmux; a new connection starts outside copy mode. */
    @Volatile
    var active = false
        set(value) {
            field = value
            scrolled = false
        }

    /** The owner's own input is about to be queued. */
    fun ownerTyped() = typed.set(true)

    /**
     * Before [count] bytes of [bytes] go to the device: whether to leave
     * tmux's copy mode first.
     */
    fun beforeWrite(bytes: ByteArray, count: Int): Boolean {
        val owner = typed.getAndSet(false)
        if (!active) return false
        if (hasWheelUp(bytes, count)) {
            scrolled = true
            return false
        }
        if (owner && scrolled) {
            scrolled = false
            return true
        }
        return false
    }
}

/**
 * Whether [bytes] (the first [count]) hold a mouse-wheel-up report as the
 * Termux emulator sends it: SGR `ESC [ < 64 ; x ; y M`, or the legacy
 * `ESC [ M` with button byte 32 + 64.
 */
fun hasWheelUp(bytes: ByteArray, count: Int): Boolean {
    val n = minOf(count, bytes.size)
    for (i in 0 until n - 2) {
        if (bytes[i] != ESC || bytes[i + 1] != '['.code.toByte()) continue
        if (startsWith(bytes, n, i + 2, SGR_WHEEL_UP)) return true
        if (bytes[i + 2] == 'M'.code.toByte() && i + 3 < n && bytes[i + 3] == LEGACY_WHEEL_UP) return true
    }
    return false
}

private fun startsWith(bytes: ByteArray, n: Int, at: Int, prefix: ByteArray): Boolean =
    at + prefix.size <= n && prefix.indices.all { bytes[at + it] == prefix[it] }

private const val ESC: Byte = 0x1b
private val SGR_WHEEL_UP = "<64;".toByteArray(Charsets.US_ASCII)
private const val LEGACY_WHEEL_UP: Byte = (32 + 64).toByte()
