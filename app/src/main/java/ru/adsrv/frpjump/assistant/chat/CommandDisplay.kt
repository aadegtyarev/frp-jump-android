package ru.adsrv.frpjump.assistant.chat

/**
 * What the owner reads before allowing a command: the command with every character that could hide or disguise something shown as a
 * visible escape. The command that runs is never changed; this is display only.
 *
 * Escaped: control characters except the line break (tab, carriage return, ESC, NUL, DEL, NEL ...), format characters (bidi marks and
 * overrides U+200E/F, U+202A-202E, U+2066-2069, zero-width U+200B-200D, U+2060, U+FEFF, soft hyphen ...), line and paragraph
 * separators, any space other than a plain one (no-break, ideographic, thin ...), lone surrogates, private-use and unassigned code points.
 * Short forms: carriage return, tab, ESC, NUL and DEL get one-character glyphs; the rest are written as an angle-bracketed U+ escape.
 * A line feed stays a line break, so a carriage return before it shows as the glyph and then the break.
 */
internal fun displayCommand(raw: String): String {
    val out = StringBuilder(raw.length + 8)
    var i = 0
    while (i < raw.length) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == '\n'.code || cp == ' '.code -> out.appendCodePoint(cp)
            cp == '\r'.code -> out.append('␍')
            cp == '\t'.code -> out.append('␉')
            cp == 0x1B -> out.append('␛')
            cp == 0x00 -> out.append('␀')
            cp == 0x7F -> out.append('␡')
            needsEscape(cp) -> out.append("⟨U+").append("%04X".format(cp)).append('⟩')
            else -> out.appendCodePoint(cp)
        }
    }
    return out.toString()
}

private fun needsEscape(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
    Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR,
    Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true
    else -> false
}

/** Lines of the original command as the owner sees them (a final line break does not add an empty line to the count). */
internal fun commandLineCount(raw: String): Int = if (raw.isEmpty()) 0 else raw.removeSuffix("\n").count { it == '\n' } + 1
