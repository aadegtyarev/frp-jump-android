package ru.adsrv.frpjump.files.text

/**
 * The colours of [doc]'s lines, worked out in order (each line needs the
 * state the one before left) a batch at a time by [advance], off the main
 * thread; the screen shows lines up to [done] coloured and the rest plain
 * meanwhile. Each line's pieces are kept once worked out.
 */
class LineColours(private val doc: TextDocument, private val highlighter: Highlighter) {
    private val spans = arrayOfNulls<IntArray>(doc.lineCount)
    private var state = 0
    private val scratch = Spans()

    /** Lines coloured so far, from the first. */
    @Volatile
    var done = 0
        private set

    val finished: Boolean get() = done >= doc.lineCount

    /**
     * Colours up to [lines] more lines, stopping early once [keepGoing]
     * says no (checked before each line: a closed viewer stops at once);
     * false once all are coloured.
     */
    fun advance(lines: Int, keepGoing: () -> Boolean = { true }): Boolean {
        val until = minOf(doc.lineCount, done + lines)
        for (line in done until until) {
            if (!keepGoing()) break
            scratch.clear()
            state = highlighter.safeLine(doc.line(line), state, scratch)
            spans[line] = if (scratch.size == 0) EMPTY else scratch.toArray()
            done = line + 1
        }
        return !finished
    }

    /** [line]'s pieces (offsets in the line), or null while it is not coloured yet. */
    fun of(line: Int): IntArray? = if (line < done) spans[line] else null

    private companion object {
        val EMPTY = IntArray(0)
    }
}
