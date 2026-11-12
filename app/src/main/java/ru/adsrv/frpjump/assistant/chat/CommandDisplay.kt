package ru.adsrv.frpjump.assistant.chat

/** A piece of the displayed command: [escape] pieces are our visible escapes (drawn highlighted), never characters of the command. */
internal data class DisplaySpan(val text: String, val escape: Boolean)

/**
 * What the owner reads before allowing a command: the command with every character that could hide or disguise something shown as a
 * visible, highlighted escape. The command that runs is never changed; this is display only.
 *
 * Escaped: control characters except the line break (tab, carriage return, ESC, NUL, DEL, NEL ...), every format character, the whole
 * Default_Ignorable_Code_Point set (soft hyphen, combining grapheme joiner, Hangul fillers, Mongolian and variation selectors, bidi marks and
 * controls, zero-width characters, invisible operators, tag characters ...), the Braille blank U+2800, line and paragraph separators, any space
 * other than a plain one (no-break, ideographic, thin ...), lone surrogates, private-use and unassigned code points, the angle brackets and
 * control pictures that the escapes themselves use (so a typed look-alike cannot pass for one), and the combining marks beyond the second in a
 * run (a stack of marks can hide the base letter). Short forms: carriage return, tab, ESC, NUL and DEL get one-character glyphs; the rest are
 * written as an angle-bracketed U+ escape. A line feed stays a line break.
 */
internal fun displayCommandSpans(raw: String): List<DisplaySpan> {
    val spans = ArrayList<DisplaySpan>()
    val plain = StringBuilder()
    fun flush() { if (plain.isNotEmpty()) { spans += DisplaySpan(plain.toString(), false); plain.setLength(0) } }
    fun escape(text: String) { flush(); spans += DisplaySpan(text, true) }
    var i = 0
    var marks = 0
    while (i < raw.length) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        val mark = isCombiningMark(cp)
        marks = if (mark) marks + 1 else 0
        when {
            cp == '\n'.code || cp == ' '.code -> plain.appendCodePoint(cp)
            cp == '\r'.code -> escape("␍")
            cp == '\t'.code -> escape("␉")
            cp == 0x1B -> escape("␛")
            cp == 0x00 -> escape("␀")
            cp == 0x7F -> escape("␡")
            needsEscape(cp) || (mark && marks > MAX_MARKS) -> escape("⟨U+" + "%04X".format(cp) + "⟩")
            else -> plain.appendCodePoint(cp)
        }
    }
    flush()
    return spans
}

/** The displayed command as plain text (the escapes without their highlight). */
internal fun displayCommand(raw: String): String = displayCommandSpans(raw).joinToString("") { it.text }

private const val MAX_MARKS = 2

private fun isCombiningMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
    Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> !isDefaultIgnorable(cp)
    else -> false
}

private fun needsEscape(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
    Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR,
    Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true
    else -> isDefaultIgnorable(cp) || cp == 0x2800 || cp in OWN_GLYPHS
}

/** The characters our own escapes are made of; a command that contains them is shown with them escaped. */
private val OWN_GLYPHS = setOf(0x27E8, 0x27E9, 0x2409, 0x240D, 0x241B, 0x2400, 0x2421)

/** Unicode Default_Ignorable_Code_Point, written out so that it does not depend on the runtime's Unicode version. */
internal fun isDefaultIgnorable(cp: Int): Boolean =
    cp == 0x00AD || cp == 0x034F || cp == 0x061C || cp in 0x115F..0x1160 || cp in 0x17B4..0x17B5 || cp in 0x180B..0x180F ||
        cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2060..0x206F || cp == 0x3164 || cp in 0xFE00..0xFE0F || cp == 0xFEFF ||
        cp == 0xFFA0 || cp in 0xFFF0..0xFFF8 || cp in 0x1BCA0..0x1BCA3 || cp in 0x1D173..0x1D17A || cp in 0xE0000..0xE0FFF

/**
 * Whether the command has a strong right-to-left letter (Hebrew, Arabic ...). The command is always laid out left to right, but two
 * right-to-left letters on one line are still shown in reverse order of each other, so the owner is told.
 */
internal fun containsStrongRtl(raw: String): Boolean {
    var i = 0
    while (i < raw.length) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        val d = Character.getDirectionality(cp)
        if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true
    }
    return false
}

/** Lines of the original command as the owner sees them (a final line break does not add an empty line to the count). */
internal fun commandLineCount(raw: String): Int = if (raw.isEmpty()) 0 else raw.removeSuffix("\n").count { it == '\n' } + 1
