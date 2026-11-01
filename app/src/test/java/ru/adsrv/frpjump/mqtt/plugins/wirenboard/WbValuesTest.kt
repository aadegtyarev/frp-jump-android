package ru.adsrv.frpjump.mqtt.plugins.wirenboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneOffset

class WbValuesTest {
    private fun meta(json: String) = parseControlMeta(json, emptyMap())!!

    @Test
    fun `ASCII units are drawn with symbols`() {
        assertEquals("°C", unitsSymbols("deg C"))
        assertEquals("°", unitsSymbols("deg"))
        assertEquals("%RH", unitsSymbols("%, RH"))
        assertEquals("m³/h", unitsSymbols("m^3/h"))
        assertEquals("m³", unitsSymbols("m^3"))
        assertEquals("Ω", unitsSymbols("Ohm"))
        assertEquals("mΩ", unitsSymbols("mOhm"))
        assertEquals("kWh", unitsSymbols("kWh"))
        assertEquals("degree", unitsSymbols("degree"))
    }

    @Test
    fun `units come from meta, else from a deprecated typed control`() {
        assertEquals("°C", unitsOf(meta("""{"type":"temperature"}""")))
        assertEquals("%RH", unitsOf(meta("""{"type":"rel_humidity"}""")))
        assertEquals("m³/h", unitsOf(meta("""{"type":"water_flow"}""")))
        assertEquals("V", unitsOf(meta("""{"type":"value","units":"V"}""")))
        // Meta's units win over the type's.
        assertEquals("mV", unitsOf(meta("""{"type":"voltage","units":"mV"}""")))
        assertNull(unitsOf(meta("""{"type":"value"}""")))
        assertEquals(18, TYPED_UNITS.size)
    }

    @Test
    fun `decimals follow the precision`() {
        assertEquals(2, decimalsOf(BigDecimal("0.01")))
        assertEquals(6, decimalsOf(BigDecimal("1e-06")))
        assertEquals(1, decimalsOf(BigDecimal("0.5")))
        assertEquals(0, decimalsOf(BigDecimal("1")))
        assertEquals(0, decimalsOf(BigDecimal("10")))
        assertEquals(1, decimalsOf(BigDecimal("0.10")))
    }

    @Test
    fun `values round to the nearest multiple of the precision, half away from zero, in decimal`() {
        assertEquals("21.35", roundToPrecision(BigDecimal("21.345"), BigDecimal("0.01")).toPlainString())
        assertEquals("-21.35", roundToPrecision(BigDecimal("-21.345"), BigDecimal("0.01")).toPlainString())
        // 0.1 + 0.2 in binary floating point is 0.30000000000000004; here it is exact.
        assertEquals("0.3", roundToPrecision(BigDecimal("0.30000000000000004"), BigDecimal("0.1")).toPlainString())
        assertEquals("2.5", roundToPrecision(BigDecimal("2.3"), BigDecimal("0.5")).toPlainString())
        assertEquals("2.5", roundToPrecision(BigDecimal("2.25"), BigDecimal("0.5")).toPlainString())
        assertEquals("24", roundToPrecision(BigDecimal("23.5"), BigDecimal("1")).toPlainString())
        assertEquals("0.000123", roundToPrecision(BigDecimal("0.0001234"), BigDecimal("1e-06")).toPlainString())
        assertEquals("20", roundToPrecision(BigDecimal("15"), BigDecimal("10")).toPlainString())
        assertEquals("1.00", roundToPrecision(BigDecimal("1"), BigDecimal("0.01")).toPlainString())
    }

    @Test
    fun `shown digits keep the value as received without a precision or a number`() {
        assertEquals("21.3456", valueDigits("21.3456", null))
        assertEquals("21.35", valueDigits("21.3456", BigDecimal("0.01")))
        assertEquals("n/a", valueDigits("n/a", BigDecimal("0.01")))
        assertEquals("230.0", valueDigits("230", BigDecimal("0.1")))
    }

    @Test
    fun `enum titles replace the value, numeric keys matched as numbers, hex too`() {
        val m = meta("""{"type":"value","enum":{"0":{"en":"Off","ru":"Выкл"},"0x10":{"en":"Sixteen"},"2.5":"Half"}}""")
        assertEquals(ShownValue("Выкл", null), shownValue(m, "0", "ru"))
        assertEquals(ShownValue("Off", null), shownValue(m, "0.0", "en"))
        assertEquals(ShownValue("Sixteen", null), shownValue(m, "16", "en"))
        assertEquals(ShownValue("Sixteen", null), shownValue(m, "0x10", "en"))
        assertEquals(ShownValue("Half", null), shownValue(m, "2.50", "en"))
        assertEquals(ShownValue("7", null), shownValue(m, "7", "en"))
    }

    @Test
    fun `text enums match as strings`() {
        val m = meta("""{"type":"text","enum":{"auto":{"en":"Automatic"},"01":"One"}}""")
        assertEquals("Automatic", shownValue(m, "auto", "en").text)
        assertEquals("1", shownValue(m, "1", "en").text)
        assertEquals("One", shownValue(m, "01", "en").text)
    }

    @Test
    fun `a value shows its digits and units`() {
        val m = meta("""{"type":"value","units":"deg C","precision":0.1}""")
        assertEquals(ShownValue("21.4", "°C"), shownValue(m, "21.35", "ru"))
        assertEquals(ShownValue("23.456", "°C"), shownValue(meta("""{"type":"temperature"}"""), "23.456", "ru"))
    }

    @Test
    fun `local time is seconds shown as a date and time without the zone`() {
        val m = meta("""{"type":"local_time"}""")
        assertEquals("2026-11-01 12:00:00", shownValue(m, "1793534400", "en", ZoneOffset.UTC).text)
        assertEquals("2026-11-01 15:00:00", shownValue(m, "1793534400", "en", ZoneOffset.ofHours(3)).text)
        assertEquals("soon", shownValue(m, "soon", "en", ZoneOffset.UTC).text)
    }

    @Test
    fun `numbers read decimal and, for enum keys, hex`() {
        assertEquals(BigDecimal("16"), numberOf("0x10", hex = true))
        assertEquals(BigDecimal("-16"), numberOf("-0x10", hex = true))
        assertNull(numberOf("0x10"))
        assertNull(numberOf("0x", hex = true))
        assertNull(numberOf("NaN"))
        assertEquals(BigDecimal("1E-6"), numberOf("1e-06"))
    }
}
