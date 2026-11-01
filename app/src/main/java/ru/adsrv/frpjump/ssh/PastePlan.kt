package ru.adsrv.frpjump.ssh

/** What a paste into the terminal does, before anything is sent. */
sealed interface PastePlan {
    /** Sent at once. */
    data class Paste(val text: String) : PastePlan

    /**
     * [lines] lines with no bracketed paste on the remote side: each would run
     * as it arrives, so the owner is asked first (paste, join, or cancel).
     */
    data class Ask(val text: String, val lines: Int) : PastePlan
}

private val LINE_BREAK = Regex("\r\n|\n|\r")

/** [text] without one line break at its end: a copied line does not run by itself. */
fun dropTrailingNewline(text: String): String = when {
    text.endsWith("\r\n") -> text.dropLast(2)
    text.endsWith("\n") || text.endsWith("\r") -> text.dropLast(1)
    else -> text
}

/** How many lines [text] has (any of CR LF, LF, CR breaks one). */
fun pasteLines(text: String): Int = text.split(LINE_BREAK).size

/** [text]'s lines as one: each trimmed, blank ones left out, joined by a space. */
fun joinLines(text: String): String =
    text.split(LINE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

/**
 * The plan for pasting [clip] when the remote side has [bracketed] paste on
 * or off: one trailing line break always goes; several lines without
 * bracketed paste are asked about. Null when nothing is left to paste.
 */
fun planPaste(clip: String, bracketed: Boolean): PastePlan? {
    val text = dropTrailingNewline(clip)
    if (text.isEmpty()) return null
    val lines = pasteLines(text)
    return if (lines > 1 && !bracketed) PastePlan.Ask(text, lines) else PastePlan.Paste(text)
}
