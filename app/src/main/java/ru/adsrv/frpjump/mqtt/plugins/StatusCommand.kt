package ru.adsrv.frpjump.mqtt.plugins

import kotlinx.serialization.Serializable

/**
 * A rule of the "Status and command topics" plugin as written: in a
 * shipped preset or by the owner. [command] null: the topic is its own
 * command topic. [id] tells own rules apart (for Undo); presets leave it empty.
 */
@Serializable
data class RuleSpec(
    val status: String,
    val command: String? = null,
    val except: List<String> = emptyList(),
    val name: String? = null,
    val id: String = "",
)

/** A [RuleSpec] checked: templates parsed. */
class Rule(
    val spec: RuleSpec,
    val status: TopicTemplate,
    val command: TopicTemplate?,
    val except: List<TopicTemplate>,
) {
    /** What the publish sheet names the rule by: its name, else its templates. */
    val label: String
        get() = spec.name?.takeIf { it.isNotBlank() } ?: if (command == null) status.text else "${status.text} → ${command.text}"
}

/** Which field of a rule failed its check, and why. */
data class RuleProblem(val field: RuleField, val problem: TemplateProblem, val index: Int = 0)

enum class RuleField { STATUS, COMMAND, EXCEPT }

/** A rule checked, or the first field that failed. */
sealed interface RuleCheck {
    data class Ok(val rule: Rule) : RuleCheck

    data class Bad(val problem: RuleProblem) : RuleCheck
}

/**
 * [spec] checked as the plugin's settings check an own rule: the status
 * template, the command template (blank: none) using only names of the
 * status template and not equal to it, each exclusion a template.
 */
fun checkRule(spec: RuleSpec): RuleCheck {
    val status = when (val p = TopicTemplate.parse(spec.status)) {
        is TemplateParse.Ok -> p.template
        is TemplateParse.Bad -> return RuleCheck.Bad(RuleProblem(RuleField.STATUS, p.problem))
    }
    val command = spec.command?.takeIf { it.isNotEmpty() }?.let { text ->
        when (val p = TopicTemplate.parse(text)) {
            is TemplateParse.Ok -> p.template
            is TemplateParse.Bad -> return RuleCheck.Bad(RuleProblem(RuleField.COMMAND, p.problem))
        }
    }
    if (command != null) {
        if (command.names.any { it !in status.names }) return RuleCheck.Bad(RuleProblem(RuleField.COMMAND, TemplateProblem.UNKNOWN_NAME))
        if (command == status) return RuleCheck.Bad(RuleProblem(RuleField.COMMAND, TemplateProblem.SAME_AS_STATUS))
    }
    val except = spec.except.mapIndexed { i, text ->
        when (val p = TopicTemplate.parse(text)) {
            is TemplateParse.Ok -> p.template
            is TemplateParse.Bad -> return RuleCheck.Bad(RuleProblem(RuleField.EXCEPT, p.problem, i))
        }
    }
    return RuleCheck.Ok(Rule(spec, status, command, except))
}

/** Where a value written for a topic goes, by the rule that matched it. */
sealed interface CommandTarget {
    val rule: Rule

    /** Two templates: the command goes to [topic]. */
    data class Elsewhere(val topic: String, override val rule: Rule) : CommandTarget

    /** One template: the topic is its own command topic. */
    data class Itself(override val rule: Rule) : CommandTarget
}

/**
 * The first of [rules] matching [topic] not excluded by its own
 * exclusions: rules are tried top to bottom; a rule whose exclusion matches
 * is skipped and the search goes on. A one-template rule ends it too. Null:
 * no rule matches, the value goes to the topic.
 */
fun commandFor(topic: String, rules: List<Rule>): CommandTarget? {
    for (rule in rules) {
        val values = rule.status.match(topic) ?: continue
        if (rule.except.any { it.matches(topic) }) continue
        return rule.command?.let { CommandTarget.Elsewhere(it.fill(values), rule) } ?: CommandTarget.Itself(rule)
    }
    return null
}

/** The plugin's settings, for the whole app (docs/contract.md scenario 12). */
@Serializable
data class StatusCommandSettings(
    /** On at app level until turned off; a connection may have its own on or off. */
    val on: Boolean = true,
    /** Ids of the presets turned on (preset ids, not plugin ids); all off at first. */
    val presets: Set<String> = emptySet(),
    /** The owner's own rules, in order; tried before the presets. */
    val rules: List<RuleSpec> = emptyList(),
    /**
     * The Wiren Board preset has turned on by itself once (a connection
     * showed the WB tab with this plugin on there); it never does again,
     * so turned off by hand it stays off.
     */
    val wirenBoardPresetAutoDone: Boolean = false,
)

/**
 * The rules in force for [settings] (its `on` the state resolved for the
 * connection): none with the plugin off; else the
 * own rules not in [hidden] (ids deleted, waiting for Undo) that pass
 * their check, then the enabled presets' rules in the shipped order of [presets].
 */
fun rulesInForce(settings: StatusCommandSettings, presets: List<Preset>, hidden: Set<String> = emptySet()): List<Rule> {
    if (!settings.on) return emptyList()
    val own = settings.rules.filter { it.id !in hidden }.mapNotNull { (checkRule(it) as? RuleCheck.Ok)?.rule }
    val shipped = presets.filter { it.id in settings.presets }.flatMap { p -> p.rules.mapNotNull { (checkRule(it) as? RuleCheck.Ok)?.rule } }
    return own + shipped
}

/**
 * [rules] with those whose ids are in [order] put in that order, in the
 * places they held among [rules]; a rule not in [order] (one waiting for
 * Undo, not shown) keeps its place. Ids in [order] that are not rules are ignored.
 */
fun reorderRules(rules: List<RuleSpec>, order: List<String>): List<RuleSpec> {
    val byId = rules.associateBy { it.id }
    val moved = order.distinct().mapNotNull { byId[it] }.iterator()
    val inOrder = order.toSet()
    return rules.map { if (it.id in inOrder && moved.hasNext()) moved.next() else it }
}

/** [rules] with the shown rule [id] moved by [by] places among [shown] (the ids on screen, in order). */
fun moveRule(rules: List<RuleSpec>, shown: List<String>, id: String, by: Int): List<RuleSpec> {
    val from = shown.indexOf(id)
    if (from < 0) return rules
    val to = (from + by).coerceIn(0, shown.size - 1)
    if (to == from) return rules
    val order = shown.toMutableList().apply { add(to, removeAt(from)) }
    return reorderRules(rules, order)
}
