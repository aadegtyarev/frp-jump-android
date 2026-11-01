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

/**
 * [text] without the line breaks and blank lines at its end: a copied line
 * does not run by itself. Spaces at the end of the last real line stay.
 */
fun dropTrailingBreaks(text: String): String {
    var tail = text.length
    while (tail > 0 && text[tail - 1].isWhitespace()) tail--
    // The first break in the whitespace at the end: from there on it all goes.
    for (i in tail until text.length) {
        if (text[i] == '\n' || text[i] == '\r') return text.substring(0, i)
    }
    return text
}

/**
 * Whether the remote side will take a paste as bracketed, so its lines do not
 * run: the emulator's DECSET 2004 [flag], but only without tmux. tmux (3.5)
 * keeps bracketed paste on toward the app whatever its pane asked, and strips
 * the markers for a pane that did not ask (`sh`), whose shell then runs each
 * line; the pane's own mode is not visible from here.
 */
fun pasteBracketed(flag: Boolean, underTmux: Boolean): Boolean = flag && !underTmux

/** How many lines [text] has (any of CR LF, LF, CR breaks one). */
fun pasteLines(text: String): Int = text.split(LINE_BREAK).size

/** [text]'s lines as one: each trimmed, blank ones left out, joined by a space. */
fun joinLines(text: String): String =
    text.split(LINE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

/**
 * The plan for pasting [clip] when the remote side has [bracketed] paste on
 * or off: trailing line breaks and blank lines always go; several lines without
 * bracketed paste are asked about. Null when nothing is left to paste
 * (nothing, or only spaces and line breaks).
 */
fun planPaste(clip: String, bracketed: Boolean): PastePlan? {
    val text = dropTrailingBreaks(clip)
    if (text.isBlank()) return null
    val lines = pasteLines(text)
    return if (lines > 1 && !bracketed) PastePlan.Ask(text, lines) else PastePlan.Paste(text)
}
