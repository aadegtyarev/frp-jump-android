package ru.adsrv.frpjump.mqtt.plugins.wirenboard

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.mqtt.MqttPath

class WbCopyTest {
    private fun control(topic: String = "/devices/wb-map6s_34/controls/Irms 1", value: String? = "0.0853", errors: List<WbError> = emptyList()) =
        WbControl(MqttPath.parse(topic).name, MqttPath.parse(topic), "Irms 1", ControlMeta(type = "value", precision = java.math.BigDecimal("0.0001")), value, errors)

    @Test
    fun `the control copies in the short form and the topic whole`() {
        val c = control()
        assertEquals("wb-map6s_34/Irms 1", controlShortName(c))
        assertEquals("/devices/wb-map6s_34/controls/Irms 1", controlTopic(c))
    }

    @Test
    fun `a topic without the leading slash still gives the short form`() {
        assertEquals("system/Serial", controlShortName(control("devices/system/controls/Serial")))
    }

    @Test
    fun `a topic not of the convention's shape is given whole`() {
        assertEquals("/foo/bar", controlShortName(control("/foo/bar")))
    }

    @Test
    fun `the value copies as published, unrounded and without units`() {
        assertEquals(WbValueCopy.Text("0.50"), valueCopy(control(value = "0.50")))
        assertEquals(WbValueCopy.Text("A25NDEMJ"), valueCopy(control(value = "A25NDEMJ")))
    }

    @Test
    fun `a read error copies nothing even with a last value kept`() {
        assertEquals(WbValueCopy.Unreadable, valueCopy(control(value = "0", errors = listOf(WbError.Read))))
    }

    @Test
    fun `write and poll errors keep the value copyable`() {
        assertEquals(WbValueCopy.Text("1"), valueCopy(control(value = "1", errors = listOf(WbError.Write, WbError.PolledLate))))
    }

    @Test
    fun `no value yet and an empty value copy nothing`() {
        assertEquals(WbValueCopy.NoValue, valueCopy(control(value = null)))
        assertEquals(WbValueCopy.Empty, valueCopy(control(value = "")))
    }
}
