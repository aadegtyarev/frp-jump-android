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
internal fun globMatches(pattern: IntArray, level: String, ignoreCase: Boolean = false): Boolean {
    var p = 0
    var t = 0
    var star = -1
    var starAt = 0
    while (t < level.length) {
        val c = level.codePointAt(t)
        if (p < pattern.size && pattern[p] == '*'.code) {
            star = p++
            starAt = t
        } else if (p < pattern.size && (pattern[p] == '?'.code || pattern[p] == c || ignoreCase && sameIgnoringCase(pattern[p], c))) {
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

private fun sameIgnoringCase(a: Int, b: Int) =
    Character.toLowerCase(a) == Character.toLowerCase(b) || Character.toUpperCase(a) == Character.toUpperCase(b)

/** Whether one tree level [name] matches [pattern]; [ignoreCase] only for "found without case" (patterns are exact). */
internal fun levelMatches(pattern: LevelPattern, name: String, ignoreCase: Boolean = false): Boolean = when (pattern) {
    is LevelPattern.Exact -> pattern.text.equals(name, ignoreCase)
    is LevelPattern.Glob -> globMatches(pattern.points, name, ignoreCase)
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
    /** [filter] can be pinned. */
    data class Subscribe(val filter: String) : FilterOffer

    /** [filter] is a pin itself: it can be unpinned here. */
    data class Unsubscribe(val filter: String) : FilterOffer

    /** A broader pin, [by], already delivers all [filter] does. */
    data class Covered(val filter: String, val by: String) : FilterOffer

    /** The session is closed: nothing can be subscribed (a pin can still be unpinned). */
    data object SessionClosed : FilterOffer

    /** The pattern holds `*` or `?`, which the broker does not understand. */
    data object NotForBroker : FilterOffer

    /** The filter is longer than a topic may be. */
    data object TooLong : FilterOffer
}

/**
 * The offer under the hits of [query] run at [scope]; null for a query
 * that is no pattern. [open]: the session can still subscribe; a pin
 * itself is offered for unpinning either way.
 */
fun filterOffer(query: SearchQuery, scope: MqttPath, pins: Collection<String>, open: Boolean): FilterOffer? {
    if (query !is SearchQuery.Pattern) return null
    val filter = query.filter(scope) ?: return FilterOffer.NotForBroker
    if (filter.toByteArray(Charsets.UTF_8).size > MAX_TOPIC_BYTES) return FilterOffer.TooLong
    // Unpinning is the phone's own list: also in a closed session, applied on the next connect.
    if (filter in pins) return FilterOffer.Unsubscribe(filter)
    if (!open) return FilterOffer.SessionClosed
    return pins.firstOrNull { filterCovers(it, filter) }?.let { FilterOffer.Covered(filter, it) } ?: FilterOffer.Subscribe(filter)
}

/**
 * Whether the valid topic filter [pin] delivers every topic [filter]
 * does: `#` covers the rest (and the level above it), `+` any one level
 * of [filter] but not its `#`, other levels only themselves. A wildcard
 * first level of [pin] does not cover a `$` first level.
 */
fun filterCovers(pin: String, filter: String): Boolean {
    val p = pin.split('/')
    val f = filter.split('/')
    p.forEachIndexed { k, part ->
        val dollar = k == 0 && f[0].startsWith("$")
        if (part == "#") return !dollar
        if (k >= f.size) return false
        if (part == "+") {
            if (f[k] == "#" || dollar) return false
        } else if (part != f[k]) {
            return false
        }
    }
    return p.size == f.size
}
