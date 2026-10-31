package ru.adsrv.frpjump.files.text

/** How the viewer colours a text: by its name, else by its first line. */
enum class TextFormat { PLAIN, MARKDOWN, LOG, JSON, YAML, INI, SHELL, JS }

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
)

/** Whole names (dotfiles and logs without an extension). */
private val BY_NAME = mapOf(
    ".bashrc" to TextFormat.SHELL, ".bash_profile" to TextFormat.SHELL, ".bash_logout" to TextFormat.SHELL,
    ".profile" to TextFormat.SHELL, ".zshrc" to TextFormat.SHELL, ".bash_aliases" to TextFormat.SHELL,
    ".env" to TextFormat.INI, ".gitconfig" to TextFormat.INI, ".editorconfig" to TextFormat.INI,
    "syslog" to TextFormat.LOG, "messages" to TextFormat.LOG, "dmesg" to TextFormat.LOG,
)

/**
 * The format [name] names, ignoring case, or null when it says nothing:
 * rotated logs (`syslog.1`, `app.log.3`) count as logs, `.env.local` as
 * `.env`.
 */
fun formatByName(name: String): TextFormat? {
    val lower = name.lowercase()
    BY_NAME[lower]?.let { return it }
    if (lower.startsWith(".env.")) return TextFormat.INI
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
 * ([head] is the text's start): a shebang of a shell or `node`, `{` or `[`
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

private val SHELLS = setOf("sh", "bash", "ash", "dash", "zsh", "ksh", "busybox")

/** [name]'s format, else what [head] (the text's start) looks like. */
fun detectFormat(name: String, head: String): TextFormat = formatByName(name) ?: sniffFormat(head)
