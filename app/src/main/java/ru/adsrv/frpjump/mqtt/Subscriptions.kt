package ru.adsrv.frpjump.mqtt

/** Where a tap on a subscription leads (see the contract's "Subscriptions tab"). */
sealed interface SubscriptionTarget {
    /** A filter without wildcards names one topic: its card. */
    data class Topic(val path: MqttPath) : SubscriptionTarget

    /**
     * A filter with a fixed start: that branch opened in the tree, and,
     * when wildcards go on below its first wildcard level, [search] (the
     * rest of the filter, relative to the branch) run there.
     */
    data class Branch(val path: MqttPath, val search: String?) : SubscriptionTarget

    /** A filter starting with a wildcard: [pattern] searched in the whole tree. */
    data class Search(val pattern: String) : SubscriptionTarget
}

private fun isWildcardLevel(level: String) = level == "+" || level == "#"

private fun hasGlobChar(level: String) = level.any { it == '*' || it == '?' }

/**
 * Where a tap on the subscription [filter] (a valid topic filter) leads.
 * `*` and `?` are plain characters in a filter but wildcards in the
 * search, so a wildcard filter holding them is never searched: its branch
 * up to the first level with a wildcard or either of them opens (the root
 * when that is the first level).
 */
fun subscriptionTarget(filter: String): SubscriptionTarget {
    val levels = filter.split('/')
    val first = levels.indexOfFirst(::isWildcardLevel)
    if (first < 0) return SubscriptionTarget.Topic(MqttPath(levels))
    if (levels.any(::hasGlobChar)) {
        val fixed = levels.indexOfFirst { isWildcardLevel(it) || hasGlobChar(it) }
        return SubscriptionTarget.Branch(MqttPath(levels.take(fixed)), null)
    }
    if (first == 0) return SubscriptionTarget.Search(filter)
    val rest = levels.drop(first)
    // `<t>/+` and `<t>/#` are the branch's own level; more levels below the wildcard are searched there.
    return SubscriptionTarget.Branch(MqttPath(levels.take(first)), rest.takeIf { it.size > 1 }?.joinToString("/"))
}

/** A pin a topic's card can name: the topic and its mask. */
data class RootedPin(val topic: MqttPath, val mask: PinMask)

/**
 * [filter] as a topic with a mask (`<t>`, `<t>/+`, `<t>/#`), whose mask
 * can be changed as on the topic's card; null for any other filter (`#`,
 * `+/x`, `a/+/b`).
 */
fun rootedPin(filter: String): RootedPin? {
    val levels = filter.split('/')
    val wild = levels.count(::isWildcardLevel)
    if (wild == 0) return RootedPin(MqttPath(levels), PinMask.TOPIC)
    if (wild > 1 || levels.size < 2 || !isWildcardLevel(levels.last())) return null
    val mask = if (levels.last() == "+") PinMask.ONE_LEVEL_BELOW else PinMask.WHOLE_BRANCH
    return RootedPin(MqttPath(levels.dropLast(1)), mask)
}

/** [filter]'s levels as a tree pattern: `+` and `#` wildcards, every other level literal (`*` and `?` too, as MQTT reads them). */
fun filterLevels(filter: String): List<LevelPattern> = filter.split('/').map {
    when (it) {
        "+" -> LevelPattern.OneLevel
        "#" -> LevelPattern.Rest
        else -> LevelPattern.Exact(it)
    }
}

/** One row of the Subscriptions tab. */
data class SubscriptionRow(
    val filter: String,
    val target: SubscriptionTarget,
    /** Non-null when the mask can be changed. */
    val rooted: RootedPin?,
    /** A filter without wildcards: one topic, shown with its value. */
    val single: Boolean,
    /** For [single], the topic's node now; null until it is seen. */
    val node: TopicNode?,
    /** For a wildcard filter, the seen topics it matches; 1 or 0 for [single]. */
    val topics: Int,
    /** For [single], its value's change; else the newest change among the matching topics; null when nothing is seen. */
    val change: LastChange?,
)

/** The rows of [pins] (in their order) read from [tree] now. */
fun subscriptionRows(pins: List<String>, tree: TopicTree): List<SubscriptionRow> = pins.map { filter ->
    val target = subscriptionTarget(filter)
    if (target is SubscriptionTarget.Topic) {
        val node = tree.node(target.path)
        val value = node?.value
        val change = value?.let { v -> v.changedAt?.let { LastChange.At(it) } ?: LastChange.BeforeSession }
        SubscriptionRow(filter, target, rootedPin(filter), single = true, node, if (value != null) 1 else 0, change)
    } else {
        val seen = tree.summary(filterLevels(filter))
        val change = when {
            seen.newestChangeAt > 0 -> LastChange.At(seen.newestChangeAt)
            seen.topics > 0 -> LastChange.BeforeSession
            else -> null
        }
        SubscriptionRow(filter, target, rootedPin(filter), single = false, null, seen.topics, change)
    }
}

/** Why a typed topic filter cannot be subscribed. */
enum class FilterProblem {
    EMPTY,

    /** `+` or `#` with other characters in its level. */
    WILDCARD_IN_LEVEL,

    /** `#` before the last level. */
    HASH_NOT_LAST,

    NUL,

    /** More than [MAX_TOPIC_BYTES] bytes in UTF-8. */
    TOO_LONG,
}

/** What stops [text], taken as is (no trimming), from being a topic filter; null when it is one. */
fun filterProblem(text: String): FilterProblem? {
    if (text.isEmpty()) return FilterProblem.EMPTY
    val levels = text.split('/')
    levels.forEachIndexed { k, level ->
        if (level == "#" && k != levels.lastIndex) return FilterProblem.HASH_NOT_LAST
        if (!isWildcardLevel(level) && level.any { it == '+' || it == '#' }) return FilterProblem.WILDCARD_IN_LEVEL
    }
    if (text.contains('\u0000')) return FilterProblem.NUL
    if (text.toByteArray(Charsets.UTF_8).size > MAX_TOPIC_BYTES) return FilterProblem.TOO_LONG
    return null
}

/** Why a typed filter is not subscribed, as the field says it. */
sealed interface FilterRefusal {
    data class Invalid(val problem: FilterProblem) : FilterRefusal

    /** The filter is a pin already. */
    data object Pinned : FilterRefusal

    /** A broader pin, [by], already delivers all it does (as the search's offer says). */
    data class Covered(val by: String) : FilterRefusal

    /** The session closed while the filter was typed: nothing can be subscribed. */
    data object SessionClosed : FilterRefusal
}

/**
 * Why [text] typed on the Subscriptions tab cannot be pinned next to
 * [pins]; null when it can. [open]: the session can still subscribe.
 */
fun typedFilterRefusal(text: String, pins: Collection<String>, open: Boolean = true): FilterRefusal? {
    filterProblem(text)?.let { return FilterRefusal.Invalid(it) }
    if (!open) return FilterRefusal.SessionClosed
    if (text in pins) return FilterRefusal.Pinned
    return coveringPin(text, pins)?.let { FilterRefusal.Covered(it) }
}

/** A filter whose first level is a wildcard, which never matches `$` topics (`$SYS`): the field reminds of it. */
fun filterSkipsDollar(text: String): Boolean = isWildcardLevel(text.substringBefore('/'))

/** A filter holding `*` or `?`, which MQTT takes as plain characters (unlike the search): the field says so. */
fun filterHasGlobChars(text: String): Boolean = text.any { it == '*' || it == '?' }
