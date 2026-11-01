package ru.adsrv.frpjump.mqtt.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopicTemplateTest {
    private fun t(text: String) = requireNotNull(TopicTemplate.of(text)) { "not a template: $text" }

    private fun problem(text: String) = (TopicTemplate.parse(text) as? TemplateParse.Bad)?.problem

    @Test
    fun `valid templates parse into levels`() {
        assertEquals(
            listOf(TemplateLevel.Literal(""), TemplateLevel.Literal("devices"), TemplateLevel.One("d"), TemplateLevel.Literal("controls"), TemplateLevel.One("c")),
            t("/devices/{d}/controls/{c}").levels,
        )
        assertEquals(listOf(TemplateLevel.Rest("x"), TemplateLevel.Literal("state")), t("{x...}/state").levels)
        assertEquals(listOf("d", "rest"), t("a/{d}/{rest...}").names)
        // A rest at the start, the middle or the end.
        t("{x...}/state")
        t("home/{x...}/state")
        t("zigbee2mqtt/{d...}")
        t("plain/topic")
    }

    @Test
    fun `invalid templates say why`() {
        assertEquals(TemplateProblem.EMPTY, problem(""))
        assertEquals(TemplateProblem.WILDCARD, problem("a/+/b"))
        assertEquals(TemplateProblem.WILDCARD, problem("a/#"))
        assertEquals(TemplateProblem.WILDCARD, problem("a/b+c"))
        assertEquals(TemplateProblem.WILDCARD, problem("a/\u0000"))
        assertEquals(TemplateProblem.BAD_PART, problem("a/x{d}"))
        assertEquals(TemplateProblem.BAD_PART, problem("a/{d}x"))
        assertEquals(TemplateProblem.BAD_PART, problem("a/{}"))
        assertEquals(TemplateProblem.BAD_PART, problem("a/{...}"))
        assertEquals(TemplateProblem.BAD_PART, problem("a/{d-e}"))
        assertEquals(TemplateProblem.BAD_PART, problem("a/{d..}"))
        assertEquals(TemplateProblem.BAD_PART, problem("a/{d"))
        assertEquals(TemplateProblem.BAD_PART, problem("a/d}"))
        assertEquals(TemplateProblem.TWO_RESTS, problem("{a...}/x/{b...}"))
        assertEquals(TemplateProblem.NAME_TWICE, problem("{d}/x/{d}"))
        assertEquals(TemplateProblem.NAME_TWICE, problem("{d}/x/{d...}"))
        assertEquals(TemplateProblem.NO_LITERAL, problem("{x...}"))
        assertEquals(TemplateProblem.NO_LITERAL, problem("{a}/{b}"))
        // An empty level is literal, but text is needed somewhere.
        assertEquals(TemplateProblem.NO_LITERAL, problem("/{x...}"))
    }

    @Test
    fun `without a rest the topic has as many levels, each matched in turn`() {
        val wb = t("/devices/{d}/controls/{c}")
        assertEquals(mapOf("d" to "wb-mr6c_2", "c" to "K1"), wb.match("/devices/wb-mr6c_2/controls/K1"))
        assertNull(wb.match("/devices/wb-mr6c_2/controls/K1/on"))
        assertNull(wb.match("/devices/wb-mr6c_2/controls"))
        // Literals are case sensitive.
        assertNull(wb.match("/Devices/wb-mr6c_2/controls/K1"))
        // A leading `/` matches only topics with an empty first level.
        assertNull(wb.match("devices/wb-mr6c_2/controls/K1"))
        // `{name}` takes one level, which may be empty.
        assertEquals(mapOf("d" to "", "c" to "K1"), wb.match("/devices//controls/K1"))
    }

    @Test
    fun `a rest takes what is left between the levels before and after it, at least one`() {
        val end = t("{x...}/state")
        assertEquals(mapOf("x" to "home/light"), end.match("home/light/state"))
        assertEquals(mapOf("x" to "a"), end.match("a/state"))
        assertNull(end.match("state"))
        assertNull(end.match("home/light/State"))
        // An empty level is a level.
        assertEquals(mapOf("x" to ""), end.match("/state"))

        val middle = t("home/{x...}/state")
        assertEquals(mapOf("x" to "a/b"), middle.match("home/a/b/state"))
        assertEquals(mapOf("x" to "a"), middle.match("home/a/state"))
        assertNull(middle.match("home/state"))
        assertNull(middle.match("house/a/state"))

        val start = t("zigbee2mqtt/{d...}")
        assertEquals(mapOf("d" to "kitchen/lamp"), start.match("zigbee2mqtt/kitchen/lamp"))
        assertNull(start.match("zigbee2mqtt"))

        val both = t("a/{one}/{x...}/z/{last}")
        assertEquals(mapOf("one" to "b", "x" to "c/d", "last" to "e"), both.match("a/b/c/d/z/e"))
        assertNull(both.match("a/b/z/e"))
    }

    @Test
    fun `fill puts the values in`() {
        assertEquals("home/light/set", t("{x...}/set").fill(t("{x...}/state").match("home/light/state")!!))
        assertEquals("/devices/d/controls/c/on", t("/devices/{d}/controls/{c}/on").fill(mapOf("d" to "d", "c" to "c")))
    }
}
