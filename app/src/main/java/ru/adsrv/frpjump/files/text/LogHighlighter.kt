package ru.adsrv.frpjump.files.text

/**
 * Logs (syslog, journal output, dmesg, application logs): time stamps;
 * levels as whole words, ignoring case: ERROR, ERR, FATAL, CRIT, PANIC,
 * EMERG, ALERT, FAILED in red, WARN / WARNING in amber, INFO, NOTICE,
 * DEBUG, TRACE muted; IP addresses. Line by line, no state.
 */
object LogHighlighter : Highlighter {
    private val TIME = Regex(
        """\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?(?:Z|[+-]\d{2}:?\d{2})?""" +
            """|\b[A-Z][a-z]{2} [ \d]\d \d{2}:\d{2}:\d{2}(?:\.\d+)?""" +
            """|^\[\s*\d+\.\d+]""" +
            """|\b\d{2}:\d{2}:\d{2}(?:[.,]\d+)?""",
    )
    private val LEVEL = Regex(
        """\b(?:(ERROR|ERR|FATAL|CRIT|CRITICAL|PANIC|EMERG|ALERT|FAILED|FAILURE)|(WARN|WARNING)|(INFO|NOTICE|DEBUG|TRACE|VERBOSE))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val IP = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?\b""")

    override fun line(text: String, state: Int, out: Spans): Int {
        if (text.length > MAX_PATTERN_LINE || text.isEmpty()) return 0
        // Each kind found on its own, then laid out in order; of two that overlap, the one starting first stays.
        val found = ArrayList<Triple<Int, Int, Token>>()
        TIME.findAll(text).forEach { found += Triple(it.range.first, it.range.last + 1, Token.TIME) }
        LEVEL.findAll(text).forEach { m ->
            val token = when {
                m.groups[1] != null -> Token.LEVEL_ERROR
                m.groups[2] != null -> Token.LEVEL_WARN
                else -> Token.LEVEL_QUIET
            }
            found += Triple(m.range.first, m.range.last + 1, token)
        }
        IP.findAll(text).forEach { found += Triple(it.range.first, it.range.last + 1, Token.NUMBER) }
        found.sortBy { it.first }
        var end = 0
        for ((s, e, t) in found) {
            if (s < end) continue
            out.add(s, e, t)
            end = e
        }
        return 0
    }
}
