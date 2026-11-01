package ru.adsrv.frpjump.mqtt.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.mqtt.plugins.PluginIds.PRESET_STATE_SET
import ru.adsrv.frpjump.mqtt.plugins.PluginIds.PRESET_TASMOTA
import ru.adsrv.frpjump.mqtt.plugins.PluginIds.PRESET_WIREN_BOARD
import ru.adsrv.frpjump.mqtt.plugins.PluginIds.PRESET_ZIGBEE2MQTT

class StatusCommandTest {
    private val presets = STATUS_COMMAND_PLUGIN.presets
    private val allPresets = presets.map { it.id }.toSet()

    private fun rules(vararg presetIds: String, own: List<RuleSpec> = emptyList(), on: Boolean = true) =
        rulesInForce(StatusCommandSettings(on, presetIds.toSet(), own), presets)

    /** The topic a value for [topic] goes to under [rules]; null: no rule (the value goes to the topic). */
    private fun commandTopic(topic: String, rules: List<Rule>): String? = when (val t = commandFor(topic, rules)) {
        is CommandTarget.Elsewhere -> t.topic
        is CommandTarget.Itself -> topic
        null -> null
    }

    @Test
    fun `presets are shipped most specific first and every rule is valid`() {
        assertEquals(listOf(PRESET_WIREN_BOARD, PRESET_ZIGBEE2MQTT, PRESET_TASMOTA, PRESET_STATE_SET), presets.map { it.id })
        presets.flatMap { it.rules }.forEach { assertTrue("$it", checkRule(it) is RuleCheck.Ok) }
        assertEquals(9, presets.first { it.id == PRESET_TASMOTA }.rules.size)
    }

    @Test
    fun `Wiren Board preset sends to the control's on topic`() {
        val r = rules(PRESET_WIREN_BOARD)
        val target = commandFor("/devices/wb-mr6c_2/controls/K1", r)
        assertEquals("/devices/wb-mr6c_2/controls/K1/on", (target as CommandTarget.Elsewhere).topic)
        assertEquals("Wiren Board", target.rule.label)
        assertNull(commandFor("/devices/wb-mr6c_2/controls/K1/on", r))
        assertNull(commandFor("/devices/wb-mr6c_2/meta", r))
        assertNull(commandFor("devices/wb-mr6c_2/controls/K1", r))
    }

    @Test
    fun `generic preset and an own rule above it`() {
        assertEquals("home/light/set", commandTopic("home/light/state", rules(PRESET_STATE_SET)))
        val own = RuleSpec("home/{x...}/state", "home/{x...}/cmd", id = "1")
        val r = rules(PRESET_STATE_SET, own = listOf(own))
        assertEquals("home/a/b/cmd", commandTopic("home/a/b/state", r))
        // Not matched by the own rule: the preset.
        assertEquals("garden/set", commandTopic("garden/state", r))
    }

    @Test
    fun `Zigbee2MQTT preset with names holding a slash, bridge and own topics excluded`() {
        val r = rules(*allPresets.toTypedArray())
        assertEquals("zigbee2mqtt/kitchen/lamp/set", commandTopic("zigbee2mqtt/kitchen/lamp", r))
        assertEquals("zigbee2mqtt/lamp/set", commandTopic("zigbee2mqtt/lamp", r))
        // Excluded here and, for bridge/state, by the generic preset too: no switch.
        assertNull(commandFor("zigbee2mqtt/bridge/state", r))
        assertNull(commandFor("zigbee2mqtt/kitchen/lamp/availability", r))
        assertNull(commandFor("zigbee2mqtt/bridge", r))
        assertNull(commandFor("zigbee2mqtt/bridge/devices", r))
        assertNull(commandFor("zigbee2mqtt/kitchen/lamp/set", r))
        assertNull(commandFor("zigbee2mqtt/kitchen/lamp/get", r))
        assertNull(commandFor("zigbee2mqtt", r))
    }

    @Test
    fun `Tasmota preset matches POWER and POWER1 to POWER8 only`() {
        val r = rules(PRESET_TASMOTA)
        assertEquals("cmnd/plug/POWER", commandTopic("stat/plug/POWER", r))
        assertEquals("cmnd/plug/POWER1", commandTopic("stat/plug/POWER1", r))
        assertEquals("cmnd/plug/POWER8", commandTopic("stat/plug/POWER8", r))
        assertNull(commandFor("stat/plug/POWER9", r))
        assertNull(commandFor("stat/plug/RESULT", r))
        assertNull(commandFor("stat/plug/STATUS", r))
        assertNull(commandFor("stat/a/b/POWER", r))
    }

    @Test
    fun `an excluded topic falls through to the next rule`() {
        val own = RuleSpec("{x...}/state", "{x...}/cmd", except = listOf("home/{y...}/state"), id = "1")
        val r = rules(PRESET_STATE_SET, own = listOf(own))
        assertEquals("home/light/set", commandTopic("home/light/state", r))
        assertEquals("garden/cmd", commandTopic("garden/state", r))
    }

    @Test
    fun `a one-template rule makes the topic its own command topic and ends the search`() {
        val own = RuleSpec("home/{x...}/state", id = "1")
        val r = rules(*allPresets.toTypedArray(), own = listOf(own))
        val target = commandFor("home/light/state", r)
        assertTrue(target is CommandTarget.Itself)
        assertEquals("home/{x...}/state", target!!.rule.label)
        assertEquals("home/light/state", commandTopic("home/light/state", r))
        // Elsewhere the presets still apply.
        assertEquals("garden/set", commandTopic("garden/state", r))
    }

    @Test
    fun `a one-template rule's exclusion lets the search go on`() {
        val own = RuleSpec("home/{x...}/state", except = listOf("home/light/state"), id = "1")
        val r = rules(PRESET_STATE_SET, own = listOf(own))
        assertEquals("home/light/set", commandTopic("home/light/state", r))
        assertTrue(commandFor("home/door/state", r) is CommandTarget.Itself)
    }

    @Test
    fun `own rules come first in their order and the first match wins`() {
        val a = RuleSpec("x/{d}", "x/{d}/a", id = "a")
        val b = RuleSpec("x/{d}", "x/{d}/b", id = "b")
        assertEquals("x/1/a", commandTopic("x/1", rules(own = listOf(a, b))))
        assertEquals("x/1/b", commandTopic("x/1", rules(own = listOf(b, a))))
        // An own rule wins over a preset matching the same topic.
        val wb = RuleSpec("/devices/{d}/controls/{c}", "/devices/{d}/controls/{c}/set", name = "mine", id = "w")
        val target = commandFor("/devices/r/controls/K1", rules(PRESET_WIREN_BOARD, own = listOf(wb)))
        assertEquals("/devices/r/controls/K1/set", (target as CommandTarget.Elsewhere).topic)
        assertEquals("mine", target.rule.label)
    }

    @Test
    fun `presets in shipped order whatever order they were turned on`() {
        val r = rulesInForce(StatusCommandSettings(presets = linkedSetOf(PRESET_STATE_SET, PRESET_WIREN_BOARD)), presets)
        assertEquals("Wiren Board", r.first().label)
    }

    @Test
    fun `no rule with nothing on, the plugin off or the rule waiting for Undo`() {
        assertTrue(rules().isEmpty())
        assertNull(commandFor("home/light/state", rules()))
        val own = RuleSpec("home/{x...}/state", "home/{x...}/cmd", id = "1")
        assertTrue(rules(*allPresets.toTypedArray(), own = listOf(own), on = false).isEmpty())
        val hidden = rulesInForce(StatusCommandSettings(rules = listOf(own)), presets, hidden = setOf("1"))
        assertTrue(hidden.isEmpty())
        // A stored rule that fails its check is skipped, not fatal.
        assertTrue(rules(own = listOf(RuleSpec("a/+", id = "2"))).isEmpty())
    }

    @Test
    fun `checking an own rule names the field that failed`() {
        assertEquals(RuleProblem(RuleField.STATUS, TemplateProblem.EMPTY), (checkRule(RuleSpec("")) as RuleCheck.Bad).problem)
        assertEquals(RuleProblem(RuleField.COMMAND, TemplateProblem.WILDCARD), (checkRule(RuleSpec("a/{x}", "a/#")) as RuleCheck.Bad).problem)
        assertEquals(RuleProblem(RuleField.COMMAND, TemplateProblem.UNKNOWN_NAME), (checkRule(RuleSpec("a/{x}", "b/{y}")) as RuleCheck.Bad).problem)
        assertEquals(RuleProblem(RuleField.COMMAND, TemplateProblem.SAME_AS_STATUS), (checkRule(RuleSpec("a/{x}", "a/{x}")) as RuleCheck.Bad).problem)
        assertEquals(RuleProblem(RuleField.EXCEPT, TemplateProblem.BAD_PART, 1), (checkRule(RuleSpec("a/{x}", "b/{x}", listOf("a/b", "a/{"))) as RuleCheck.Bad).problem)
        // A blank command template is none: a one-template rule.
        val one = (checkRule(RuleSpec("a/{x}", "")) as RuleCheck.Ok).rule
        assertNull(one.command)
        // A command template may leave names out.
        assertTrue(checkRule(RuleSpec("a/{x}/{y}", "b/{x}")) is RuleCheck.Ok)
    }

    @Test
    fun `the label is the name, else the templates`() {
        assertEquals("a/{x} → b/{x}", (checkRule(RuleSpec("a/{x}", "b/{x}")) as RuleCheck.Ok).rule.label)
        assertEquals("a/{x}", (checkRule(RuleSpec("a/{x}", name = " ")) as RuleCheck.Ok).rule.label)
        assertEquals("lamp", (checkRule(RuleSpec("a/{x}", name = "lamp")) as RuleCheck.Ok).rule.label)
    }
}
