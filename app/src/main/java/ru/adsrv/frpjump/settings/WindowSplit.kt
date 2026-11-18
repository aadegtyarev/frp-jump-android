package ru.adsrv.frpjump.settings

/**
 * How the owner splits the model's context window (Settings → LLM assistant → Assistant provider → «Окно модели»), in percent of the window:
 * the [history] kept before it is shortened, the model's [reply] (the request's output limit) and the working [summary]'s own size.
 * What is left, the margin, is derived and never a setting. Two rules keep the margin: history + reply and history + summary stay within
 * [SUM_MAX] (the summary request reads the whole history); and the summary is at least [SUMMARY_GAP] smaller than the history (a shortening frees at
 * least that much of the window).
 */
data class WindowSplit(val history: Int, val reply: Int, val summary: Int) {
    /** The margin: what is left of the window after the history and the reply. */
    val margin: Int get() = 100 - history - reply

    /** The shortening starts when the request (history and the reply reserve) reaches this share of the window, i.e. when the history reaches its piece. */
    val compactAt: Int get() = history + reply

    /** Old tool output is left out of what the model sees until the history is down to this share (a summary's size below its piece). */
    val pruneTo: Int get() = history - summary

    val rangesValid: Boolean get() = history in HISTORY_RANGE && reply in REPLY_RANGE && summary in SUMMARY_RANGE
    val rulesHold: Boolean get() = history + reply <= SUM_MAX && history + summary <= SUM_MAX && summary <= history - SUMMARY_GAP
    val valid: Boolean get() = rangesValid && rulesHold

    /** The highest history the two rules and its range leave. */
    fun historyMax(): Int = minOf(HISTORY_RANGE.last, SUM_MAX - maxOf(reply, summary))
    fun replyMax(): Int = minOf(REPLY_RANGE.last, SUM_MAX - history)
    fun summaryMax(): Int = minOf(SUMMARY_RANGE.last, SUM_MAX - history, history - SUMMARY_GAP)
    /** The lowest history its range and the summary leave. */
    fun historyMin(): Int = maxOf(HISTORY_RANGE.first, summary + SUMMARY_GAP)

    /**
     * The rules hold: the history is lowered first for the two sums with the margin (its lowest is 40, the others leave at least 55); then the summary
     * gives way to what the history leaves (at least 30, never under its own lowest 5).
     */
    fun fitted(): WindowSplit {
        val r = reply.coerceIn(REPLY_RANGE); val m = summary.coerceIn(SUMMARY_RANGE)
        val h = history.coerceIn(HISTORY_RANGE).coerceAtMost(SUM_MAX - r).coerceAtMost(SUM_MAX - m)
        return WindowSplit(h, r, m.coerceAtMost(h - SUMMARY_GAP).coerceAtLeast(SUMMARY_RANGE.first))
    }

    /** The request's output limit: the reply's share of [window] (at least 1). */
    fun replyTokens(window: Int): Int = tokens(window, reply).coerceAtLeast(1)

    /** The working summary's own output limit: its share of [window] (at least 1). */
    fun summaryTokens(window: Int): Int = tokens(window, summary).coerceAtLeast(1)

    /** The agent kernel's reply cap in characters: never below what it was fixed at, and eight characters for each token of the reply share. */
    fun replyChars(window: Int): Int = maxOf(MIN_REPLY_CHARS.toLong(), replyTokens(window).toLong() * CHARS_PER_TOKEN).coerceAtMost(MAX_REPLY_CHARS.toLong()).toInt()

    /** The result of converting the old values: the split, and the old reply reserve (tokens) when the reply share had to be cut below it. */
    class Converted(val split: WindowSplit, val limitedFrom: Int?)

    companion object {
        const val DEFAULT_HISTORY = 75
        const val DEFAULT_REPLY = 5
        const val DEFAULT_SUMMARY = 15
        /** The margin: history + reply and history + summary stay within this share of the window. */
        const val SUM_MAX = 95
        /** A summary is at least this much of the window smaller than the history. */
        const val SUMMARY_GAP = 10
        /** The old defaults of the answer reserve (tokens) and of the trigger (percent): an owner who kept both gets the new defaults 75 / 5 / 15. */
        const val OLD_DEFAULT_OUTPUT_TOKENS = 2_048
        const val OLD_DEFAULT_TRIGGER = 80
        val HISTORY_RANGE = 40..90
        val REPLY_RANGE = 1..20
        const val SUMMARY_MAX = 40
        val SUMMARY_RANGE = 5..SUMMARY_MAX
        const val MIN_REPLY_CHARS = 32_000
        const val CHARS_PER_TOKEN = 8
        /** The agent kernel's own upper bound of a reply (AgentLimits). */
        const val MAX_REPLY_CHARS = 1_000_000
        val DEFAULT = WindowSplit(DEFAULT_HISTORY, DEFAULT_REPLY, DEFAULT_SUMMARY)

        fun tokens(window: Int, percent: Int): Int = (window.toLong() * percent / 100).toInt()

        /** The old reserve [limitedFrom] still says something: it is more than the biggest reply share (20 %) of [window] (null window: the size field holds no number). */
        fun stillLimited(limitedFrom: Int?, window: Int?): Int? = limitedFrom?.takeIf { window == null || it > tokens(window, REPLY_RANGE.last) }

        /** A token count short for a label: 197K, 2.6K, 1M (the [decimal] separator of the locale); under a thousand as it is. */
        fun compactTokens(value: Int, decimal: Char = '.'): String {
            fun tenths(v: Long, unit: Long, suffix: String): String {
                val t = (v * 10 + unit / 2) / unit
                return (t / 10).toString() + (if (t % 10 == 0L) "" else "$decimal${t % 10}") + suffix
            }
            val v = value.toLong()
            return when {
                v >= 1_000_000 -> tenths(v, 1_000_000, "M")
                v >= 10_000 -> ((v + 500) / 1000).toString() + "K"
                v >= 1_000 -> tenths(v, 1_000, "K")
                else -> v.toString()
            }
        }

        /**
         * The old values the owner CHANGED become the shares, once. An owner who kept BOTH old defaults (answer reserve 2,048 tokens, trigger 80 %) gets the new
         * defaults (reply 5 %, history 75 %), not the formula's 1 % and 79 %. Otherwise: the reply share is the old reserve over the window, rounded up and held in
         * 1..20 (when held, the old tokens are returned); the history is the old trigger less the reply share, held in 40..90. The summary share is the old summary
         * size. Then the rules hold, the history lowered first. A share that is already set ([history], [reply]) is kept as it is.
         */
        fun fromLegacy(window: Int, outputTokens: Int, trigger: Int, summarySize: Int, history: Int? = null, reply: Int? = null): Converted {
            val wanted = ((outputTokens.toLong() * 100 + window - 1) / window).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            // Both old defaults: the new defaults, but never fewer output tokens than before (a small window needs more than 5 %), within 20 %.
            if (history == null && reply == null && outputTokens == OLD_DEFAULT_OUTPUT_TOKENS && trigger == OLD_DEFAULT_TRIGGER)
                return Converted(WindowSplit(DEFAULT_HISTORY, wanted.coerceIn(DEFAULT_REPLY, REPLY_RANGE.last), summarySize).fitted(), if (wanted > REPLY_RANGE.last) outputTokens else null)
            val replyShare = reply ?: wanted.coerceIn(REPLY_RANGE)
            val limited = if (reply == null && wanted > REPLY_RANGE.last) outputTokens else null
            val historyShare = history ?: (trigger - replyShare).coerceIn(HISTORY_RANGE)
            return Converted(WindowSplit(historyShare, replyShare, summarySize).fitted(), limited)
        }
    }
}
