package ru.adsrv.frpjump.mqtt

/**
 * What the explorer's search field holds, read once per query (see the
 * contract's "The tree", search). A query without any of `* ? + #` is a
 * [Substring]; one with them is a [Pattern] or, when the wildcards are
 * misplaced, [Invalid].
 */
sealed interface SearchQuery {
    /** Today's search: a case-insensitive part of the path. */
    data class Substring(val text: String) : SearchQuery

    /** A pattern the search can run; [problem]-free by construction. */
    data class Pattern(
        /** Started with `/`: matched from the root, the leading empty level included. */
        val absolute: Boolean,
        /** The pattern's levels, as typed; for an absolute one the first is the empty level. */
        val levels: List<LevelPattern>,
    ) : SearchQuery {
        /** Only whole-level `+` and `#`: the broker can subscribe it as is. */
        val isMqttFilter: Boolean get() = levels.none { it is LevelPattern.Glob }

        /** The level the pattern starts at: the root for an absolute one, else [scope]. */
        fun base(scope: MqttPath): MqttPath = if (absolute) MqttPath.ROOT else scope

        /**
         * The absolute topic filter of a pattern that [isMqttFilter], with a
         * relative one put under [scope]; null for one with `*` or `?`.
         */
        fun filter(scope: MqttPath): String? {
            if (!isMqttFilter) return null
            return (base(scope).levels + levels.map { it.text }).joinToString("/")
        }
    }

    data class Invalid(val problem: PatternProblem) : SearchQuery
}

enum class PatternProblem {
    /** `+` or `#` with other characters in its level (`K+`, `a#`). */
    WILDCARD_IN_LEVEL,

    /** `#` before the last level. */
    HASH_NOT_LAST,
}

/** One level of a search pattern. */
sealed interface LevelPattern {
    /** The level as typed. */
    val text: String

    /** No wildcard: the level must equal it exactly. */
    data class Exact(override val text: String) : LevelPattern

    /** `*` (any run of characters, none too) and `?` (exactly one) within the level. */
    data class Glob(override val text: String) : LevelPattern {
        /** The level's code points, matched against a candidate's. */
        internal val points: IntArray = text.codePoints().toArray()
    }

    /** `+`: exactly one level, an empty one too. */
    data object OneLevel : LevelPattern {
        override val text = "+"
    }

    /** `#`: this level and all below, and the level above it (as in MQTT). */
    data object Rest : LevelPattern {
        override val text = "#"
    }
}

private fun isWildcard(c: Char) = c == '*' || c == '?' || c == '+' || c == '#'

/**
 * Reads a trimmed search [query]. There is no escaping: `*`, `?`, `+` and
 * `#` are always wildcards in a pattern (a topic holding them is found by
 * a `?` in their place).
 */
fun parseSearch(query: String): SearchQuery {
    if (query.none(::isWildcard)) return SearchQuery.Substring(query)
    val absolute = query.startsWith('/')
    val parts = query.split('/')
    val levels = parts.mapIndexed { k, part ->
        when {
            part == "+" -> LevelPattern.OneLevel
            part == "#" -> if (k == parts.lastIndex) LevelPattern.Rest else return SearchQuery.Invalid(PatternProblem.HASH_NOT_LAST)
            part.any { it == '+' || it == '#' } -> return SearchQuery.Invalid(PatternProblem.WILDCARD_IN_LEVEL)
            part.any { it == '*' || it == '?' } -> LevelPattern.Glob(part)
            else -> LevelPattern.Exact(part)
        }
    }
    return SearchQuery.Pattern(absolute, levels)
}

/**
 * Whether [level] matches the glob [pattern] (code points): `*` any run,
 * `?` exactly one. Greedy with one backtrack point, so at most
 * pattern × level steps, never exponential.
 */
internal fun globMatches(pattern: IntArray, level: String): Boolean {
    var p = 0
    var t = 0
    var star = -1
    var starAt = 0
    while (t < level.length) {
        val c = level.codePointAt(t)
        if (p < pattern.size && pattern[p] == '*'.code) {
            star = p++
            starAt = t
        } else if (p < pattern.size && (pattern[p] == '?'.code || pattern[p] == c)) {
            p++
            t += Character.charCount(c)
        } else if (star >= 0) {
            p = star + 1
            starAt += Character.charCount(level.codePointAt(starAt))
            t = starAt
        } else {
            return false
        }
    }
    while (p < pattern.size && pattern[p] == '*'.code) p++
    return p == pattern.size
}

/** Whether one tree level [name] matches [pattern]. */
internal fun levelMatches(pattern: LevelPattern, name: String): Boolean = when (pattern) {
    is LevelPattern.Exact -> pattern.text == name
    is LevelPattern.Glob -> globMatches(pattern.points, name)
    LevelPattern.OneLevel, LevelPattern.Rest -> true
}

/**
 * A pattern level at the tree's first level that starts with a wildcard
 * does not match a `$` level (`$SYS`), as MQTT's `+` and `#` do not.
 */
internal fun skipsDollar(pattern: LevelPattern): Boolean = when (pattern) {
    is LevelPattern.Exact -> false
    is LevelPattern.Glob -> pattern.text.first().let { it == '*' || it == '?' }
    LevelPattern.OneLevel, LevelPattern.Rest -> true
}

/** What the search offers for subscribing the pattern it ran. */
sealed interface FilterOffer {
    /** [filter] can be pinned; [pinned] when it already is. */
    data class Subscribe(val filter: String, val pinned: Boolean) : FilterOffer

    /** The pattern holds `*` or `?`, which the broker does not understand. */
    data object NotForBroker : FilterOffer

    /** The filter is longer than a topic may be. */
    data object TooLong : FilterOffer
}

/** The offer under the hits of [query] run at [scope]; null for a query that is no pattern. */
fun filterOffer(query: SearchQuery, scope: MqttPath, pins: Collection<String>): FilterOffer? {
    if (query !is SearchQuery.Pattern) return null
    val filter = query.filter(scope) ?: return FilterOffer.NotForBroker
    if (filter.toByteArray(Charsets.UTF_8).size > MAX_TOPIC_BYTES) return FilterOffer.TooLong
    return FilterOffer.Subscribe(filter, filter in pins)
}
