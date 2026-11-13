package ru.adsrv.frpjump.settings

/**
 * The owner's own standing instructions for the assistant: free text for the model that refines style and preferences.
 * It is never read by permission, confirmation or tool-limit code.
 */
object AssistantOwnerInstructions {
    /** At most this many UTF-16 units. */
    const val MAX_CHARS = 2000

    /** Control characters other than `\n` are removed; the text is cut to [MAX_CHARS] without splitting a surrogate pair. */
    fun normalize(text: String): String {
        val clean = text.filterNot { it.isISOControl() && it != '\n' }
        if (clean.length <= MAX_CHARS) return clean
        val cut = if (clean[MAX_CHARS - 1].isHighSurrogate() && clean[MAX_CHARS].isLowSurrogate()) MAX_CHARS - 1 else MAX_CHARS
        return clean.substring(0, cut)
    }

    /** What a Send carries: whitespace only means none. */
    fun effective(text: String): String = if (text.isBlank()) "" else text
}
