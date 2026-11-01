package ru.adsrv.frpjump.tunnel

/**
 * A minimal TOML writer for the frpc config: nested maps become tables, lists
 * of maps arrays of tables, everything else a `key = value` line. Output is
 * laid out as Python's `tomli_w.dumps` lays it out (the Linux agent writes
 * frpc.toml with it): a table's plain values first, then its sub-tables in
 * insertion order, a blank line before every header.
 *
 * Values: String, Boolean, Int, Long, and lists of those. Keys are frpc's
 * own identifiers (bare keys); anything else is quoted.
 */
object Toml {
    fun write(root: Map<String, Any>): String {
        val out = StringBuilder()
        writeTable(out, root, path = emptyList(), header = null)
        return out.toString()
    }

    private fun writeTable(out: StringBuilder, table: Map<String, Any>, path: List<String>, header: String?) {
        if (header != null) {
            if (out.isNotEmpty()) out.append('\n')
            out.append(header).append('\n')
        }
        for ((key, value) in table) {
            if (!isTable(value) && !isArrayOfTables(value)) out.append(key(key)).append(" = ").append(value(value)).append('\n')
        }
        for ((key, value) in table) {
            val sub = path + key(key)
            if (isTable(value)) {
                @Suppress("UNCHECKED_CAST")
                writeTable(out, value as Map<String, Any>, sub, "[${sub.joinToString(".")}]")
            } else if (isArrayOfTables(value)) {
                @Suppress("UNCHECKED_CAST")
                for (element in value as List<Map<String, Any>>) {
                    writeTable(out, element, sub, "[[${sub.joinToString(".")}]]")
                }
            }
        }
    }

    private fun isTable(value: Any) = value is Map<*, *>

    private fun isArrayOfTables(value: Any) = value is List<*> && value.isNotEmpty() && value.all { it is Map<*, *> }

    private val BARE_KEY = Regex("[A-Za-z0-9_-]+")

    private fun key(k: String) = if (BARE_KEY.matches(k)) k else string(k)

    private fun value(v: Any): String = when (v) {
        is String -> string(v)
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is List<*> -> v.joinToString(", ", "[", "]") { value(requireNotNull(it)) }
        else -> throw IllegalArgumentException("unsupported TOML value: ${v.javaClass.simpleName}")
    }

    private fun string(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == '\u007f' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
