package ru.adsrv.frpjump.mqtt.plugins.wirenboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.mqtt.MqttPath
import ru.adsrv.frpjump.mqtt.MqttVersion
import ru.adsrv.frpjump.mqtt.PublishAnswer
import ru.adsrv.frpjump.mqtt.PublishFailure
import ru.adsrv.frpjump.mqtt.TopicValue
import java.math.BigDecimal

class WbWritesTest {
    private fun meta(json: String) = parseControlMeta(json, emptyMap())!!

    private val k1 = MqttPath.parse("/devices/d/controls/K1")

    private fun value(text: String, at: Long) = TopicValue.of(text.toByteArray(), retain = true, qos = 1, at = at, version = MqttVersion.V5, previous = null)

    @Test
    fun `widgets follow the type, read-only wins over any type but an alarm`() {
        assertEquals(WbWidget.SWITCH, widgetOf(meta("""{"type":"switch"}""")))
        assertEquals(WbWidget.WO_SWITCH, widgetOf(meta("""{"type":"wo-switch"}""")))
        assertEquals(WbWidget.PUSHBUTTON, widgetOf(meta("""{"type":"pushbutton"}""")))
        assertEquals(WbWidget.RANGE, widgetOf(meta("""{"type":"range"}""")))
        assertEquals(WbWidget.RGB, widgetOf(meta("""{"type":"rgb"}""")))
        assertEquals(WbWidget.ALARM, widgetOf(meta("""{"type":"alarm"}""")))
        assertEquals(WbWidget.ALARM, widgetOf(meta("""{"type":"alarm","readonly":true}""")))
        assertEquals(WbWidget.NUMBER, widgetOf(meta("""{"type":"value"}""")))
        assertEquals(WbWidget.NUMBER, widgetOf(meta("""{"type":"temperature"}""")))
        assertEquals(WbWidget.NUMBER, widgetOf(meta("""{"type":"local_time"}""")))
        assertEquals(WbWidget.TEXT, widgetOf(meta("""{"type":"text"}""")))
        assertEquals(WbWidget.TEXT, widgetOf(meta("""{"type":"w1-id"}""")))
        assertEquals(WbWidget.ENUM, widgetOf(meta("""{"type":"value","enum":{"0":"Off"}}""")))
        assertEquals(WbWidget.ENUM, widgetOf(meta("""{"type":"text","enum":{"a":"A"}}""")))
        assertEquals(WbWidget.READ_ONLY, widgetOf(meta("""{"type":"switch","readonly":true}""")))
        assertEquals(WbWidget.READ_ONLY, widgetOf(parseControlMeta("""{"type":"switch"}""", mapOf("readonly" to "1"))!!))
        assertEquals(WbWidget.READ_ONLY, widgetOf(meta("""{"order":1}""")))
        assertEquals(WbWidget.READ_ONLY, widgetOf(meta("""{"type":"mystery"}""")))
    }

    @Test
    fun `write payloads`() {
        assertEquals("1", switchPayload(true))
        assertEquals("0", switchPayload(false))
        assertEquals("1", PUSH_PAYLOAD)
        assertEquals("128", rangePayload(128))
        assertEquals("255;0;17", rgbPayload(255, 0, 17))
        assertEquals("255;0;0", rgbPayload(300, -5, 0))
        assertEquals(NumberInput.Ok("21.35"), numberInput("21.345", meta("""{"type":"value","precision":0.01}""")))
        assertEquals(NumberInput.Ok("0.000001"), numberInput("1e-6", meta("""{"type":"value"}""")))
        assertEquals(NumberInput.Ok("2.5"), numberInput("2,5", meta("""{"type":"value"}""")))
        assertEquals(NumberInput.Ok("20"), numberInput("15", meta("""{"type":"value","precision":10}""")))
    }

    @Test
    fun `a typed number is checked against min and max after rounding`() {
        val m = meta("""{"type":"value","min":0,"max":10,"precision":0.5}""")
        assertEquals(NumberInput.Bad(NumberProblem.NotANumber), numberInput("ten", m))
        assertEquals(NumberInput.Bad(NumberProblem.BelowMin(BigDecimal("0"))), numberInput("-1", m))
        assertEquals(NumberInput.Bad(NumberProblem.AboveMax(BigDecimal("10"))), numberInput("10.3", m))
        assertEquals(NumberInput.Ok("10.0"), numberInput("10.2", m))
        assertEquals("21.35", numberPrefill("21.346", meta("""{"type":"value","precision":0.01}""")))
        assertEquals("", numberPrefill(null, m))
    }

    @Test
    fun `a switch is on only for 1`() {
        assertTrue(switchOn("1"))
        assertFalse(switchOn("0"))
        assertFalse(switchOn("2"))
        assertFalse(switchOn(null))
    }

    @Test
    fun `range bounds default to 0 and 255 and positions are clamped integers`() {
        assertEquals(0..255, rangeBounds(meta("""{"type":"range"}""")))
        assertEquals(10..100, rangeBounds(meta("""{"type":"range","min":10,"max":100}""")))
        assertEquals(1..9, rangeBounds(meta("""{"type":"range","min":0.5,"max":9.5}""")))
        assertEquals(128, rangePosition("127.6", 0..255))
        assertEquals(255, rangePosition("999", 0..255))
        assertEquals(0, rangePosition("x", 0..255))
    }

    @Test
    fun `rgb reads three integers, spaces ignored`() {
        assertEquals(Triple(1, 2, 3), rgbOf("1;2;3"))
        assertEquals(Triple(1, 2, 3), rgbOf(" 1 ; 2;3 "))
        assertNull(rgbOf("1;2"))
        assertNull(rgbOf("1;2;300"))
        assertNull(rgbOf(null))
    }

    @Test
    fun `echoes match numbers after rounding, rgb as integers, the rest exactly`() {
        val value = meta("""{"type":"value","precision":0.1}""")
        assertTrue(sameValue(value, "21.3", "21.30"))
        assertTrue(sameValue(value, "21.3", "21.26"))
        assertFalse(sameValue(value, "21.3", "21.4"))
        assertTrue(sameValue(meta("""{"type":"switch"}"""), "1", "1.0"))
        assertTrue(sameValue(meta("""{"type":"range"}"""), "128", "128"))
        assertTrue(sameValue(meta("""{"type":"rgb"}"""), "255;0;17", "255; 0; 17"))
        assertFalse(sameValue(meta("""{"type":"rgb"}"""), "255;0;17", "255;0;18"))
        assertFalse(sameValue(meta("""{"type":"text"}"""), "01", "1"))
        assertTrue(sameValue(meta("""{"type":"text"}"""), "on", "on"))
        // Numeric enum keys, hex too.
        assertTrue(sameValue(meta("""{"type":"value","enum":{"0x10":"Sixteen"}}"""), "0x10", "16"))
        assertTrue(sameValue(meta("""{"type":"local_time"}"""), "1793534400", "1793534400.0"))
    }

    @Test
    fun `writing the value already shown waits for no echo, nor do buttons and write-only switches`() {
        val sw = meta("""{"type":"switch"}""")
        assertFalse(awaitsEcho(sw, WbWidget.SWITCH, "1", "1"))
        assertTrue(awaitsEcho(sw, WbWidget.SWITCH, "0", "1"))
        assertTrue(awaitsEcho(sw, WbWidget.SWITCH, null, "1"))
        assertFalse(awaitsEcho(meta("""{"type":"pushbutton"}"""), WbWidget.PUSHBUTTON, null, "1"))
        assertFalse(awaitsEcho(meta("""{"type":"wo-switch"}"""), WbWidget.WO_SWITCH, "0", "1"))
    }

    @Test
    fun `a write is sending until the status topic brings the asked value`() {
        val sw = meta("""{"type":"switch"}""")
        val write = WbWrite(k1, "1", awaitsEcho = true, sentAt = 1_000)
        assertEquals(WriteView.Sending("1"), writeView(write, sw, listOf(value("0", 500)), 1_100))
        val acked = write.copy(answer = PublishAnswer.Accepted, answeredAt = 1_200)
        assertEquals(WriteView.Sending("1"), writeView(acked, sw, listOf(value("0", 500)), 2_000))
        // An older equal value does not count; one since sending ends it, also before the PUBACK.
        assertEquals(WriteView.Sending("1"), writeView(write, sw, listOf(value("1", 900)), 1_100))
        assertEquals(WriteView.Done, writeView(write, sw, listOf(value("1", 1_050), value("0", 500)), 1_100))
        // A flip back meanwhile still shows the echo came.
        assertEquals(WriteView.Done, writeView(acked, sw, listOf(value("0", 1_400), value("1", 1_300)), 1_500))
    }

    @Test
    fun `no echo within 5 s of the PUBACK says the device did not answer, until the next change`() {
        val sw = meta("""{"type":"switch"}""")
        val acked = WbWrite(k1, "1", awaitsEcho = true, sentAt = 1_000, answer = PublishAnswer.Accepted, answeredAt = 1_200)
        assertEquals(WriteView.Sending("1"), writeView(acked, sw, listOf(value("0", 500)), 1_200 + ECHO_TIMEOUT_MS - 1))
        assertEquals(WriteView.Failed(WriteFailure.NoAnswerFromDevice), writeView(acked, sw, listOf(value("0", 500)), 1_200 + ECHO_TIMEOUT_MS))
        // The next change of the status topic ends the message.
        assertEquals(WriteView.Done, writeView(acked, sw, listOf(value("0", 9_000), value("0", 500)), 9_100))
    }

    @Test
    fun `the broker's refusal and its silence are shown`() {
        val sw = meta("""{"type":"switch"}""")
        val write = WbWrite(k1, "1", awaitsEcho = true, sentAt = 1_000)
        val refused = PublishAnswer.Refused(0x87, null)
        assertEquals(WriteView.Failed(WriteFailure.Refused(refused)), writeView(write.copy(answer = refused, answeredAt = 1_100), sw, listOf(value("0", 500)), 1_200))
        val timeout = PublishAnswer.Failed(PublishFailure.TIMEOUT)
        assertEquals(WriteView.Failed(WriteFailure.NoAnswerFromBroker), writeView(write.copy(answer = timeout, answeredAt = 11_000), sw, listOf(value("0", 500)), 11_100))
        val notConnected = PublishAnswer.Failed(PublishFailure.NOT_CONNECTED)
        assertEquals(WriteView.Failed(WriteFailure.NotSent(notConnected)), writeView(write.copy(answer = notConnected, answeredAt = 1_000), sw, emptyList(), 1_100))
    }

    @Test
    fun `a write of the value already shown ends on the PUBACK`() {
        val sw = meta("""{"type":"switch"}""")
        val write = WbWrite(k1, "1", awaitsEcho = false, sentAt = 1_000)
        assertEquals(WriteView.Sending("1"), writeView(write, sw, listOf(value("1", 500)), 1_100))
        assertEquals(WriteView.Done, writeView(write.copy(answer = PublishAnswer.Accepted, answeredAt = 1_200), sw, listOf(value("1", 500)), 1_200))
    }

    @Test
    fun `buttons and write-only switches show the broker's answer for a moment`() {
        val button = meta("""{"type":"pushbutton"}""")
        val write = WbWrite(k1, "1", awaitsEcho = false, sentAt = 1_000, answer = PublishAnswer.Accepted, answeredAt = 1_100)
        assertEquals(WriteView.Answered(PublishAnswer.Accepted), writeView(write, button, emptyList(), 1_200))
        assertEquals(WriteView.Done, writeView(write, button, emptyList(), 1_100 + SHORT_ANSWER_MS))
        // 3.1.1: "Sent, not confirmed".
        val v311 = write.copy(answer = PublishAnswer.Unconfirmed)
        assertEquals(WriteView.Answered(PublishAnswer.Unconfirmed), writeView(v311, meta("""{"type":"wo-switch"}"""), emptyList(), 1_200))
    }
}
