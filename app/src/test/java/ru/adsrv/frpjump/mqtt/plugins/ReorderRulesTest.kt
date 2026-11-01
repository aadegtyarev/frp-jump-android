package ru.adsrv.frpjump.mqtt.plugins

import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderRulesTest {
    private fun r(id: String) = RuleSpec("a/$id", id = id)

    private val rules = listOf(r("a"), r("b"), r("c"), r("d"))

    @Test
    fun `the shown rules take the new order`() {
        assertEquals(listOf("c", "a", "b", "d"), reorderRules(rules, listOf("c", "a", "b", "d")).map { it.id })
        assertEquals(listOf("b", "a", "c", "d"), moveRule(rules, rules.map { it.id }, "a", 1).map { it.id })
        assertEquals(listOf("d", "a", "b", "c"), moveRule(rules, rules.map { it.id }, "d", -3).map { it.id })
    }

    @Test
    fun `a hidden rule keeps its place, moves past the ends do nothing`() {
        // "b" waits for Undo: not shown, stays second.
        assertEquals(listOf("c", "b", "a", "d"), moveRule(rules, listOf("a", "c", "d"), "a", 1).map { it.id })
        assertEquals(rules, moveRule(rules, listOf("a", "b", "c", "d"), "a", -1))
        assertEquals(rules, moveRule(rules, listOf("a", "b", "c", "d"), "x", 1))
    }
}
