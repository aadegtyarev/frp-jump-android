package ru.adsrv.frpjump.mqtt.plugins.wirenboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class WbMetaTest {
    @Test
    fun `the JSON meta gives every field`() {
        val meta = parseControlMeta(
            """{"type":"value","units":"W","min":0,"max":100.5,"precision":0.01,"order":3,"readonly":true,"hidden":false,
               "title":{"en":"Power","ru":"Мощность"},"enum":{"0":{"en":"Off"},"1":"On"}}""",
            emptyMap(),
        )!!
        assertEquals("value", meta.type)
        assertEquals("W", meta.units)
        assertEquals(0, BigDecimal.ZERO.compareTo(meta.min))
        assertEquals(BigDecimal("100.5"), meta.max)
        assertEquals(BigDecimal("0.01"), meta.precision)
        assertEquals(BigDecimal("3"), meta.order)
        assertTrue(meta.readonly)
        assertFalse(meta.hidden)
        assertEquals("Мощность", meta.title!!.pick("ru"))
        assertEquals(listOf("0", "1"), meta.enum.map { it.key })
        assertEquals("On", meta.enum[1].title.pick("ru"))
    }

    @Test
    fun `the legacy one-field topics alone are meta, with readonly as 1`() {
        val meta = parseControlMeta(null, mapOf("type" to "temperature", "order" to "2", "readonly" to "1", "precision" to "0.1", "units" to "deg C", "min" to "-40", "max" to "85"))!!
        assertEquals("temperature", meta.type)
        assertEquals(BigDecimal("2"), meta.order)
        assertTrue(meta.readonly)
        assertEquals(BigDecimal("0.1"), meta.precision)
        assertEquals("deg C", meta.units)
        assertEquals(BigDecimal("-40"), meta.min)
        assertEquals(BigDecimal("85"), meta.max)
    }

    @Test
    fun `per field the JSON wins and a one-field topic fills what it lacks`() {
        // The JSON has no readonly; the topic says 1: read-only (acceptance 7).
        val meta = parseControlMeta("""{"type":"switch","order":5}""", mapOf("type" to "value", "readonly" to "1", "order" to "9"))!!
        assertEquals("switch", meta.type)
        assertEquals(BigDecimal("5"), meta.order)
        assertTrue(meta.readonly)
        // JSON false wins over a topic's 1.
        assertFalse(parseControlMeta("""{"type":"switch","readonly":false}""", mapOf("readonly" to "1"))!!.readonly)
    }

    @Test
    fun `readonly and hidden default to false and read 1 and true alike`() {
        val plain = parseControlMeta("""{"type":"switch"}""", emptyMap())!!
        assertFalse(plain.readonly)
        assertFalse(plain.hidden)
        assertTrue(parseControlMeta("""{"type":"switch","readonly":1,"hidden":true}""", emptyMap())!!.readonly)
        assertTrue(parseControlMeta("""{"type":"switch","readonly":"1","hidden":"1"}""", emptyMap())!!.hidden)
        assertFalse(parseControlMeta(null, mapOf("type" to "switch", "readonly" to "0"))!!.readonly)
    }

    @Test
    fun `cleared, empty or broken meta is no meta`() {
        assertNull(parseControlMeta(null, emptyMap()))
        assertNull(parseControlMeta("", emptyMap()))
        assertNull(parseControlMeta("{}", emptyMap()))
        assertNull(parseControlMeta("{not json", emptyMap()))
        assertNull(parseControlMeta("[1,2]", emptyMap()))
        assertNull(parseControlMeta("\"text\"", emptyMap()))
        // Cleared one-field topics arrive as empty texts.
        assertNull(parseControlMeta(null, mapOf("type" to "", "order" to "")))
        // meta/error and unknown fields are no meta.
        assertNull(parseControlMeta(null, mapOf("error" to "r", "title" to "x")))
        // A broken JSON next to a one-field topic: the topic is the meta.
        assertEquals("switch", parseControlMeta("{oops", mapOf("type" to "switch"))!!.type)
    }

    @Test
    fun `unreadable numbers and a non-positive precision are left out`() {
        val meta = parseControlMeta("""{"type":"value","min":"low","max":null,"precision":0,"order":"x"}""", mapOf("max" to "50"))!!
        assertNull(meta.min)
        assertEquals(BigDecimal("50"), meta.max)
        assertNull(meta.precision)
        assertNull(meta.order)
        assertEquals(BigDecimal("1E-6"), parseControlMeta("""{"precision":1e-06}""", emptyMap())!!.precision)
    }

    @Test
    fun `a title picks the phone's language, then en, then the first key`() {
        val both = WbTitle.of(kotlinx.serialization.json.Json.parseToJsonElement("""{"en":"Lamp","ru":"Лампа"}"""))!!
        assertEquals("Лампа", both.pick("ru"))
        assertEquals("Lamp", both.pick("de"))
        val ruOnly = parseDeviceTitle("""{"title":{"ru":"Реле"}}""")!!
        // An English phone and a title with only ru: the Russian one.
        assertEquals("Реле", ruOnly.pick("en"))
        val ordered = parseDeviceTitle("""{"title":{"de":"Lampe","fr":"Lampe FR"}}""")!!
        assertEquals("Lampe", ordered.pick("ru"))
        assertEquals("Plain", parseDeviceTitle("""{"title":"Plain"}""")!!.pick("ru"))
    }

    @Test
    fun `an empty title text counts as absent`() {
        val title = parseDeviceTitle("""{"title":{"ru":"","en":"Lamp"}}""")!!
        assertEquals("Lamp", title.pick("ru"))
        assertNull(parseDeviceTitle("""{"title":""}""")!!.pick("ru"))
        assertNull(parseDeviceTitle("""{"driver":"wb-modbus"}"""))
    }

    @Test
    fun `error letters map to plain words, unknown ones as is`() {
        assertEquals(listOf(WbError.Read), parseErrors("r"))
        assertEquals(listOf(WbError.Read, WbError.Write, WbError.PolledLate), parseErrors("rwp"))
        assertEquals(listOf(WbError.Write, WbError.Other("x")), parseErrors("w, x"))
        assertEquals(listOf(WbError.Read), parseErrors("rr"))
        assertEquals(emptyList<WbError>(), parseErrors(""))
        assertEquals(emptyList<WbError>(), parseErrors(null))
    }
}
