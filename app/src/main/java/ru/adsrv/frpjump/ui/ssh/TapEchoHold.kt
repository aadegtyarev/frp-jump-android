package ru.adsrv.frpjump.ui.ssh

/**
 * After a tap's arrows went, the screen still shows the cursor where it
 * was until the shell echoes the move; a tap counted from that screen would
 * be off by the arrows on their way. So taps on the command line are
 * dropped while arrows are being written and after them, until the
 * cursor's line ([cursorLineState]) changes or [ECHO_MS] pass. Main
 * thread.
 */
class TapEchoHold(private val clock: () -> Long) {
    private var sending = false
    private var line: String? = null
    private var at = 0L

    /** Arrows are being written. */
    fun sending() {
        sending = true
        line = null
    }

    /** The last arrow went; [lineNow] is the cursor's line before the echo. */
    fun sent(lineNow: String) {
        sending = false
        line = lineNow
        at = clock()
    }

    /** Whether a tap now, with the cursor's line [lineNow], is dropped. */
    fun holds(lineNow: String): Boolean {
        if (sending) return true
        val before = line ?: return false
        if (lineNow != before || clock() - at >= ECHO_MS) {
            line = null
            return false
        }
        return true
    }

    companion object {
        /** The longest a tap waits for the echo of the arrows. */
        const val ECHO_MS = 400L
    }
}

/**
 * [count] times the arrow [code], in pieces of at most [perPiece] whole
 * codes, so a key typed between two pieces never lands inside one.
 */
fun arrowPieces(code: String, count: Int, perPiece: Int = ARROWS_PER_PIECE): List<String> {
    if (count <= 0 || code.isEmpty()) return emptyList()
    return (0 until count step perPiece).map { code.repeat(minOf(perPiece, count - it)) }
}

/** Arrows per write: a few hundred bytes, far below the terminal's 4 KiB queue. */
const val ARROWS_PER_PIECE = 64
