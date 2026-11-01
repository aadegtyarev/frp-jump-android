package ru.adsrv.frpjump.files.text

/**
 * How the viewer colours a text: by its name, else by its first line. Each
 * format but [PLAIN] is a TextMate grammar's [scope] (assets/textmate/).
 */
enum class TextFormat(val scope: String?) {
    PLAIN(null),
    MARKDOWN("text.html.markdown"),
    LOG("text.log"),
    JSON("source.json"),
    YAML("source.yaml"),
    INI("source.ini"),
    SHELL("source.shell"),
    JS("source.js"),
    PYTHON("source.python"),
    GO("source.go"),
    C("source.c"),
    LUA("source.lua"),
    DOCKERFILE("source.dockerfile"),
    SQL("source.sql"),
    XML("text.xml"),
    HTML("text.html.basic"),
    CSS("source.css"),
}

private val BY_EXTENSION = mapOf(
    "md" to TextFormat.MARKDOWN, "markdown" to TextFormat.MARKDOWN,
    "log" to TextFormat.LOG,
    "json" to TextFormat.JSON, "jsonl" to TextFormat.JSON, "geojson" to TextFormat.JSON,
    "yml" to TextFormat.YAML, "yaml" to TextFormat.YAML,
    "ini" to TextFormat.INI, "conf" to TextFormat.INI, "cfg" to TextFormat.INI, "cnf" to TextFormat.INI,
    "properties" to TextFormat.INI, "env" to TextFormat.INI, "toml" to TextFormat.INI,
    "service" to TextFormat.INI, "socket" to TextFormat.INI, "timer" to TextFormat.INI, "mount" to TextFormat.INI,
    "automount" to TextFormat.INI, "target" to TextFormat.INI, "path" to TextFormat.INI, "slice" to TextFormat.INI,
    "network" to TextFormat.INI, "netdev" to TextFormat.INI, "link" to TextFormat.INI, "desktop" to TextFormat.INI,
    "nmconnection" to TextFormat.INI,
    "sh" to TextFormat.SHELL, "bash" to TextFormat.SHELL, "zsh" to TextFormat.SHELL, "ksh" to TextFormat.SHELL,
    "js" to TextFormat.JS, "mjs" to TextFormat.JS, "cjs" to TextFormat.JS,
    "py" to TextFormat.PYTHON, "pyw" to TextFormat.PYTHON,
    "go" to TextFormat.GO,
    "c" to TextFormat.C, "h" to TextFormat.C,
    "lua" to TextFormat.LUA,
    "dockerfile" to TextFormat.DOCKERFILE,
    "sql" to TextFormat.SQL,
    "xml" to TextFormat.XML, "svg" to TextFormat.XML, "xsd" to TextFormat.XML, "plist" to TextFormat.XML,
    "html" to TextFormat.HTML, "htm" to TextFormat.HTML,
    "css" to TextFormat.CSS,
)

/** Whole names (dotfiles and logs without an extension). */
private val BY_NAME = mapOf(
    ".bashrc" to TextFormat.SHELL, ".bash_profile" to TextFormat.SHELL, ".bash_logout" to TextFormat.SHELL,
    ".profile" to TextFormat.SHELL, ".zshrc" to TextFormat.SHELL, ".bash_aliases" to TextFormat.SHELL,
    ".env" to TextFormat.INI, ".gitconfig" to TextFormat.INI, ".editorconfig" to TextFormat.INI,
    "syslog" to TextFormat.LOG, "messages" to TextFormat.LOG, "dmesg" to TextFormat.LOG,
    "dockerfile" to TextFormat.DOCKERFILE, "containerfile" to TextFormat.DOCKERFILE,
)

/**
 * The format [name] names, ignoring case, or null when it says nothing:
 * rotated logs (`syslog.1`, `app.log.3`) count as logs, `.env.local` as
 * `.env`, `Dockerfile.dev` as a Dockerfile.
 */
fun formatByName(name: String): TextFormat? {
    val lower = name.lowercase()
    BY_NAME[lower]?.let { return it }
    if (lower.startsWith(".env.")) return TextFormat.INI
    if (lower.startsWith("dockerfile.")) return TextFormat.DOCKERFILE
    // Rotated logs: syslog.1, app.log.3.
    val unrotated = lower.replace(ROTATION, "")
    if (unrotated != lower) {
        BY_NAME[unrotated]?.let { if (it == TextFormat.LOG) return it }
        if (unrotated.endsWith(".log")) return TextFormat.LOG
    }
    val dot = lower.lastIndexOf('.')
    if (dot < 0 || dot == lower.length - 1) return null
    return BY_EXTENSION[lower.substring(dot + 1)]
}

private val ROTATION = Regex("""(\.\d+)+$""")

private val LOG_START = Regex(
    """^(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}|[A-Z][a-z]{2} [ \d]\d \d{2}:\d{2}:\d{2}|\[\s*\d+\.\d+]|\d{2}:\d{2}:\d{2})""",
)

/**
 * The format a text with no telling name has, by its first non-blank line
 * ([head] is the text's start): a shebang of a shell, `node` or `python`, `{` or `[`
 * of JSON, `---` of YAML, `[section]` of INI, a time stamp of a log;
 * else plain.
 */
fun sniffFormat(head: String): TextFormat {
    val first = head.lineSequence().firstOrNull { it.isNotBlank() }?.trimEnd() ?: return TextFormat.PLAIN
    if (first.startsWith("#!")) {
        val command = first.removePrefix("#!").trim().split(Regex("\\s+"))
        val program = (if (command.firstOrNull()?.endsWith("/env") == true) command.drop(1).firstOrNull { !it.startsWith("-") } else command.firstOrNull())
            ?.substringAfterLast('/').orEmpty()
        return when {
            program in SHELLS -> TextFormat.SHELL
            program == "node" || program == "nodejs" -> TextFormat.JS
            PYTHON_PROGRAM.matches(program) -> TextFormat.PYTHON
            else -> TextFormat.PLAIN
        }
    }
    val trimmed = first.trimStart()
    return when {
        trimmed.startsWith("{") || trimmed.startsWith("[{") || trimmed == "[" || trimmed.startsWith("[\"") -> TextFormat.JSON
        first == "---" || first.startsWith("%YAML") -> TextFormat.YAML
        SECTION.matches(trimmed) -> TextFormat.INI
        LOG_START.containsMatchIn(first) -> TextFormat.LOG
        else -> TextFormat.PLAIN
    }
}

private val SECTION = Regex("""\[[\w .:"-]+]""")

private val PYTHON_PROGRAM = Regex("""python[0-9.]*""")

private val SHELLS = setOf("sh", "bash", "ash", "dash", "zsh", "ksh", "busybox")

/**
 * [name]'s format, else what [text] looks like. A `.conf` or `.cfg` name
 * says little (Wiren Board keeps JSON in `wb-mqtt-serial.conf`): one
 * whose text [looksLikeJson] is JSON, else INI.
 */
fun detectFormat(name: String, text: String): TextFormat {
    val byName = formatByName(name) ?: return sniffFormat(text.take(SNIFF_CHARS))
    val ext = name.substringAfterLast('.', "").lowercase()
    return if (ext in LOOSE_EXTENSIONS && looksLikeJson(text)) TextFormat.JSON else byName
}

/** Extensions shared by INI-like and JSON files. */
private val LOOSE_EXTENSIONS = setOf("conf", "cfg", "cnf")

/** How much of a text's start [sniffFormat] gets. */
private const val SNIFF_CHARS = 512

/**
 * Whether [text] looks like a JSON object or array, cheaply: it starts
 * with `{` and ends with `}`, or starts with `[` followed by what an array
 * holds (not `[section]` of INI) and ends with `]`.
 */
fun looksLikeJson(text: String): Boolean {
    val first = text.indexOfFirst { !it.isWhitespace() && it != '\uFEFF' }
    if (first < 0) return false
    val last = text.indexOfLast { !it.isWhitespace() }
    return when (text[first]) {
        '{' -> text[last] == '}'
        '[' -> {
            var at = first + 1
            while (at < text.length && text[at].isWhitespace()) at++
            text[last] == ']' && at < text.length && (
                text[at] in "{[\"]-" || text[at].isDigit() ||
                    literalAt(text, at, "true") || literalAt(text, at, "false") || literalAt(text, at, "null")
                )
        }
        else -> false
    }
}

/** Whether [word] stands alone at [at] in [text]: `null,` yes, `[null-sink]` no. */
private fun literalAt(text: String, at: Int, word: String): Boolean {
    if (!text.startsWith(word, at)) return false
    val after = text.getOrNull(at + word.length) ?: return true
    return !after.isLetterOrDigit() && after != '_' && after != '-'
}
