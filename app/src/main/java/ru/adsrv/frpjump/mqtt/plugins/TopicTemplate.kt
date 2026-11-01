package ru.adsrv.frpjump.mqtt.plugins

/** One level of a [TopicTemplate]. */
sealed interface TemplateLevel {
    /** Equal text, case sensitive; may be empty (the empty first level of `/devices/…`). */
    data class Literal(val text: String) : TemplateLevel

    /** `{name}`: exactly one level, which may be empty. */
    data class One(val name: String) : TemplateLevel

    /** `{name...}`: one or more levels; at most one in a template. */
    data class Rest(val name: String) : TemplateLevel
}

/** Why a text is not a template (docs/mqtt-contract.md, "Status and command topics"). */
enum class TemplateProblem {
    /** Nothing typed. */
    EMPTY,

    /** `+`, `#` or NUL: a template is no topic filter. */
    WILDCARD,

    /** A level with `{` or `}` that is not a whole `{name}` or `{name...}`, or a name of other than letters, digits and `_`. */
    BAD_PART,

    /** More than one `{name...}`. */
    TWO_RESTS,

    /** A name used twice. */
    NAME_TWICE,

    /** No literal level with text: it would match nearly anything. */
    NO_LITERAL,

    /** A command template using a name the status template does not have. */
    UNKNOWN_NAME,

    /** A command template equal to the status template: leave it empty for a topic that is its own command topic. */
    SAME_AS_STATUS,
}

/** A template parsed, or why not. */
sealed interface TemplateParse {
    data class Ok(val template: TopicTemplate) : TemplateParse

    data class Bad(val problem: TemplateProblem) : TemplateParse
}

/**
 * A topic with named parts, used by plugin rules: levels split by `/`,
 * each literal text, `{name}` or `{name...}`. Neither a topic filter nor a
 * search pattern. Built by [parse] only, so always valid.
 */
class TopicTemplate private constructor(val text: String, val levels: List<TemplateLevel>) {
    /** The names it binds, in order. */
    val names: List<String> = levels.mapNotNull {
        when (it) {
            is TemplateLevel.One -> it.name
            is TemplateLevel.Rest -> it.name
            is TemplateLevel.Literal -> null
        }
    }

    private val rest = levels.indexOfFirst { it is TemplateLevel.Rest }

    /**
     * The values of the names when [topic] matches, else null. Without
     * `{name...}` the topic has as many levels as the template, each
     * matched in turn; with it, the levels before it match the topic's first
     * levels, those after it the topic's last levels, and it takes what is
     * left in between, at least one level.
     */
    fun match(topic: String): Map<String, String>? {
        val parts = topic.split('/')
        val bound = HashMap<String, String>()
        fun take(level: TemplateLevel, part: String): Boolean = when (level) {
            is TemplateLevel.Literal -> level.text == part
            is TemplateLevel.One -> {
                bound[level.name] = part
                true
            }
            is TemplateLevel.Rest -> error("matched as a span")
        }
        if (rest < 0) {
            if (parts.size != levels.size) return null
            return if (levels.indices.all { take(levels[it], parts[it]) }) bound else null
        }
        val after = levels.size - rest - 1
        if (parts.size < rest + after + 1) return null
        for (i in 0 until rest) if (!take(levels[i], parts[i])) return null
        for (j in 0 until after) if (!take(levels[rest + 1 + j], parts[parts.size - after + j])) return null
        bound[(levels[rest] as TemplateLevel.Rest).name] = parts.subList(rest, parts.size - after).joinToString("/")
        return bound
    }

    fun matches(topic: String): Boolean = match(topic) != null

    /** The topic this template names with [values] put in (every name of it must be there). */
    fun fill(values: Map<String, String>): String = levels.joinToString("/") {
        when (it) {
            is TemplateLevel.Literal -> it.text
            is TemplateLevel.One -> values.getValue(it.name)
            is TemplateLevel.Rest -> values.getValue(it.name)
        }
    }

    override fun equals(other: Any?): Boolean = other is TopicTemplate && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = text

    companion object {
        private val NAME = Regex("[A-Za-z0-9_]+")

        /**
         * [text] as a template: no `+`, `#` or NUL; each level literal text
         * or a whole `{name}` / `{name...}`; at most one `{name...}`; no
         * name twice; at least one literal level with text. A leading `/`
         * is an empty first level.
         */
        fun parse(text: String): TemplateParse {
            if (text.isEmpty()) return TemplateParse.Bad(TemplateProblem.EMPTY)
            if (text.any { it == '+' || it == '#' || it == '\u0000' }) return TemplateParse.Bad(TemplateProblem.WILDCARD)
            val levels = text.split('/').map { part ->
                if ('{' !in part && '}' !in part) return@map TemplateLevel.Literal(part)
                if (!part.startsWith('{') || !part.endsWith('}')) return TemplateParse.Bad(TemplateProblem.BAD_PART)
                val inner = part.substring(1, part.length - 1)
                val rest = inner.endsWith("...")
                val name = if (rest) inner.removeSuffix("...") else inner
                if (!NAME.matches(name)) return TemplateParse.Bad(TemplateProblem.BAD_PART)
                if (rest) TemplateLevel.Rest(name) else TemplateLevel.One(name)
            }
            if (levels.count { it is TemplateLevel.Rest } > 1) return TemplateParse.Bad(TemplateProblem.TWO_RESTS)
            val template = TopicTemplate(text, levels)
            if (template.names.size != template.names.toSet().size) return TemplateParse.Bad(TemplateProblem.NAME_TWICE)
            if (levels.none { it is TemplateLevel.Literal && it.text.isNotEmpty() }) return TemplateParse.Bad(TemplateProblem.NO_LITERAL)
            return TemplateParse.Ok(template)
        }

        /** [text] as a template, or null; for shipped rules and tests. */
        fun of(text: String): TopicTemplate? = (parse(text) as? TemplateParse.Ok)?.template
    }
}
