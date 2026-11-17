package ru.adsrv.frpjump.settings

/**
 * Removes what the model could be given invisibly or what only breaks lines: control characters other than `\n`, the invisible
 * Unicode tag characters U+E0000-E007F, and the bidirectional override and isolate controls U+202A-202E and U+2066-2069.
 * Zero-width joiners and non-joiners stay: they are part of ordinary text in several scripts.
 */
fun sanitizeModelText(text: String): String {
    // Removing a character can join the halves of a surrogate pair that were apart (a tag character split by a control one), so
    // repeat until nothing changes: the text shrinks each round, hence this ends, and the result is a fixed point.
    var current = text
    while (true) { val next = sanitizeOnce(current); if (next == current) return current; current = next }
}

private fun sanitizeOnce(text: String): String {
    var clean = true
    var i = 0
    while (i < text.length) { val cp = text.codePointAt(i); if (forbidden(cp)) { clean = false; break }; i += Character.charCount(cp) }
    if (clean) return text
    val out = StringBuilder(text.length)
    i = 0
    while (i < text.length) { val cp = text.codePointAt(i); if (!forbidden(cp)) out.appendCodePoint(cp); i += Character.charCount(cp) }
    return out.toString()
}

private fun forbidden(cp: Int) = (cp <= 0x1F && cp != '\n'.code) || cp in 0x7F..0x9F || cp in 0xE0000..0xE007F ||
    cp in 0x202A..0x202E || cp in 0x2066..0x2069

