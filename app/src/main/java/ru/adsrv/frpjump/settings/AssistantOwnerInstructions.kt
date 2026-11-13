package ru.adsrv.frpjump.settings

/**
 * The owner's own standing instructions for the assistant: free text for the model that refines style and preferences.
 * It is never read by permission, confirmation or tool-limit code.
 */
object AssistantOwnerInstructions {
    /** At most this many UTF-16 units. */
    const val MAX_CHARS = 2000

    /** The field after an edit: its text and where the cursor goes. */
    data class Edit(val text: String, val cursor: Int)

    /**
     * Removes what the model could be given invisibly or what only breaks lines: control characters other than `\n`, the invisible
     * Unicode tag characters U+E0000-E007F, and the bidirectional override and isolate controls U+202A-202E and U+2066-2069.
     * Zero-width joiners and non-joiners stay: they are part of ordinary text in several scripts.
     */
    fun sanitize(text: String): String {
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

    /** [sanitize], then cut to [MAX_CHARS] without splitting a surrogate pair. */
    fun normalize(text: String): String {
        val clean = sanitize(text)
        if (clean.length <= MAX_CHARS) return clean
        val cut = if (clean[MAX_CHARS - 1].isHighSurrogate() && clean[MAX_CHARS].isLowSurrogate()) MAX_CHARS - 1 else MAX_CHARS
        return clean.substring(0, cut)
    }

    /**
     * The field's text after [old] became [new] (typed or pasted). What was inserted is [sanitize]d, and when the whole is over
     * [MAX_CHARS] only the inserted part is cut, so the text before and after it stays as it was (typing at the start of a full
     * field changes nothing; a long paste into the middle keeps both ends). A surrogate pair is never split. [cursor] is where the
     * edit put the cursor; when the insertion was changed it goes right after the kept insertion.
     */
    fun limitEdit(old: String, new: String, cursor: Int): Edit {
        // The inserted part is what lies between the common start and the common end.
        var start = 0
        val shortest = minOf(old.length, new.length)
        while (start < shortest && old[start] == new[start]) start++
        var end = 0
        while (end < shortest - start && old[old.length - 1 - end] == new[new.length - 1 - end]) end++
        if (start > 0 && new[start - 1].isHighSurrogate() && start < new.length && new[start].isLowSurrogate()) start--
        if (end > 0 && new[new.length - end].isLowSurrogate() && new.length - end > 0 && new[new.length - end - 1].isHighSurrogate()) end--
        val head = new.substring(0, start)
        val tail = new.substring(new.length - end)
        var inserted = sanitize(new.substring(start, new.length - end))
        val room = MAX_CHARS - head.length - tail.length
        if (room < 0) return normalize(new).let { Edit(it, it.length) }
        if (inserted.length > room) {
            var keep = room
            if (keep > 0 && inserted[keep - 1].isHighSurrogate() && inserted[keep].isLowSurrogate()) keep--
            inserted = inserted.substring(0, keep)
        }
        val text = head + inserted + tail
        if (text == new) return Edit(text, cursor.coerceIn(0, text.length))
        return Edit(text, head.length + inserted.length)
    }

    /** What a Send carries: whitespace only means none. */
    fun effective(text: String): String = if (text.isBlank()) "" else text
}
