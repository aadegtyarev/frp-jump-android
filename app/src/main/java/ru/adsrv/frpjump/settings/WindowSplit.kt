package ru.adsrv.frpjump.settings

/**
 * How the owner splits the model's context window (Settings → LLM assistant → Assistant provider → «Окно модели»), in percent of the window:
 * the [history] kept before it is shortened, the model's [reply] (the request's output limit) and the working [summary]'s own size.
 * What is left, the margin, is derived and never a setting. Two rules keep the margin: history + reply and history + summary stay within
 * [SUM_MAX] (the summary request reads the whole history).
 */
data class WindowSplit(val history: Int, val reply: Int, val summary: Int) {
    /** The margin: what is left of the window after the history and the reply. */
    val margin: Int get() = 100 - history - reply

    /** The shortening starts when the request (history and the reply reserve) reaches this share of the window, i.e. when the history reaches its piece. */
    val compactAt: Int get() = history + reply

    /** Old tool output is left out of what the model sees until the history is down to this share (a summary's size below its piece). */
    val pruneTo: Int get() = history - summary

    val rangesValid: Boolean get() = history in HISTORY_RANGE && reply in REPLY_RANGE && summary in SUMMARY_RANGE
    val rulesHold: Boolean get() = history + reply <= SUM_MAX && history + summary <= SUM_MAX
    val valid: Boolean get() = rangesValid && rulesHold

    /** The highest history the two rules and its range leave. */
    fun historyMax(): Int = minOf(HISTORY_RANGE.last, SUM_MAX - maxOf(reply, summary))
    fun replyMax(): Int = minOf(REPLY_RANGE.last, SUM_MAX - history)
    fun summaryMax(): Int = minOf(SUMMARY_RANGE.last, SUM_MAX - history)

    /** Both rules hold: the history is lowered first (it is the one that can always give way: its lowest is 40, the others leave at least 55). */
    fun fitted(): WindowSplit = copy(
        history = history.coerceIn(HISTORY_RANGE).coerceAtMost(SUM_MAX - reply.coerceIn(REPLY_RANGE)).coerceAtMost(SUM_MAX - summary.coerceIn(SUMMARY_RANGE)),
        reply = reply.coerceIn(REPLY_RANGE), summary = summary.coerceIn(SUMMARY_RANGE))

    /** The request's output limit: the reply's share of [window] (at least 1). */
    fun replyTokens(window: Int): Int = tokens(window, reply).coerceAtLeast(1)

    /** The working summary's own output limit: its share of [window] (at least 1). */
    fun summaryTokens(window: Int): Int = tokens(window, summary).coerceAtLeast(1)

    /** The agent kernel's reply cap in characters: never below what it was fixed at, and four characters for each token of the reply share. */
    fun replyChars(window: Int): Int = maxOf(MIN_REPLY_CHARS.toLong(), replyTokens(window).toLong() * CHARS_PER_TOKEN).coerceAtMost(MAX_REPLY_CHARS.toLong()).toInt()

    /** The result of converting the old values: the split, and the old reply reserve (tokens) when the reply share had to be cut below it. */
    class Converted(val split: WindowSplit, val limitedFrom: Int?)

    companion object {
        const val DEFAULT_HISTORY = 75
        const val DEFAULT_REPLY = 5
        const val DEFAULT_SUMMARY = 15
        /** The margin: history + reply and history + summary stay within this share of the window. */
        const val SUM_MAX = 95
        val HISTORY_RANGE = 40..90
        val REPLY_RANGE = 1..20
        const val SUMMARY_MAX = 40
        val SUMMARY_RANGE = 5..SUMMARY_MAX
        const val MIN_REPLY_CHARS = 32_000
        const val CHARS_PER_TOKEN = 4
        /** The agent kernel's own upper bound of a reply (AgentLimits). */
        const val MAX_REPLY_CHARS = 1_000_000
        val DEFAULT = WindowSplit(DEFAULT_HISTORY, DEFAULT_REPLY, DEFAULT_SUMMARY)

        fun tokens(window: Int, percent: Int): Int = (window.toLong() * percent / 100).toInt()

        /**
         * The old values become the three shares, once: the reply share is the old output reserve over the window, rounded up and held in 1..20 (when held,
         * the old tokens are returned); the history is the old trigger less the reply share, held in 40..90; the summary share is the old summary size.
         * Then both rules hold, the history lowered first. A share that is already set ([history], [reply]) is kept as it is.
         */
        fun fromLegacy(window: Int, outputTokens: Int, trigger: Int, summarySize: Int, history: Int? = null, reply: Int? = null): Converted {
            val wanted = ((outputTokens.toLong() * 100 + window - 1) / window).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            val replyShare = reply ?: wanted.coerceIn(REPLY_RANGE)
            val limited = if (reply == null && wanted > REPLY_RANGE.last) outputTokens else null
            val historyShare = history ?: (trigger - replyShare).coerceIn(HISTORY_RANGE)
            return Converted(WindowSplit(historyShare, replyShare, summarySize).fitted(), limited)
        }
    }
}
